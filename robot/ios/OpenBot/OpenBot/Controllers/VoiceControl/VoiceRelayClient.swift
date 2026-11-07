//
//  VoiceRelayClient.swift
//  OpenBot
//
//  WiFi voice-relay remote: finds the phones that share their robot link (VoiceRelayServer,
//  advertised as _friendbot-ctrl._tcp. over Bonjour) and forwards the voice control page's
//  drive commands to one of them. The remote phone keeps its own Xiaozhi session — mic in,
//  assistant voice out — and only the resulting motion travels over the LAN, so any phone on
//  the network can drive the robot from the voice control page.
//
//  The user picks the drive target from the voice page's connection capsule (hostNames() for
//  the list): chooseLocal() keeps this phone's own robot link, chooseHost(_:) picks one shared
//  robot, chooseAuto() restores the default (auto-connect to the first shared robot). The
//  pick is remembered across launches. Reconnects with 1 s / 2 s / 5 s backoff when the
//  chosen host disappears. State callbacks arrive on the main queue.
//
//  Also the Remote Mic uplink: sendMicState(on:) opens/closes this phone's microphone stream
//  for the host voice control page and sendMicFrame(base64:rms:) streams the opus frames; the
//  host answers each open with a "micack" saying whether its page is actually listening
//  (onMicAck). Received lines are parsed on the connection queue.
//
//  Each host asks for the 4-digit PIN shown on its voice control page. The PIN is remembered
//  per host; when none is saved (or the host says it is wrong) onPinRequired asks the page to
//  prompt the user (promptForPin), and setPin(_:for:) connects.
//

import Foundation
import Network
import UIKit

final class VoiceRelayClient {

    static let shared = VoiceRelayClient()

    /// Host-side status; fired on the main queue.
    var onStateChange: (() -> Void)?

    /// The host answered a sendMicState(on:): live says whether its voice control page is
    /// open and hearing the microphone. Fired on the main queue.
    var onMicAck: ((Bool) -> Void)?

    /// A host needs its PIN before this phone may drive through it (the Bool: the saved/typed
    /// PIN was refused). Show promptForPin(from:host:wrong:). Fired on the main queue.
    var onPinRequired: ((String, Bool) -> Void)?

    private(set) var isSearching = false
    private(set) var isHostConnected = false
    private(set) var hostName = ""

    /// The current pick: a host name, "local" or "" (automatic).
    private(set) var choice = ""

    private let choiceKey = "voice_relay_choice"
    private static let pinKeyPrefix = "voice_relay_pin_"
    /// Hosts the user was already asked about; not asked again until they pick the host.
    private var pinAsked: Set<String> = []
    private static let choiceLocal = "local"

    private let queue = DispatchQueue(label: "friendbot.relay.client")
    private var browser: NWBrowser?
    private var connection: NWConnection?
    private var results: Set<NWBrowser.Result> = []
    private var reconnectAttempts = 0
    private var reconnectWork: DispatchWorkItem?
    /// Server→client lines reassembly; touched on the connection queue only.
    private var receiveBuffer = Data()

    private init() {
        choice = UserDefaults.standard.string(forKey: choiceKey) ?? ""
    }

    /// Starts browsing for shared robots (idempotent); the saved pick is applied on discovery.
    /// Main thread.
    func start() {
        if browser == nil {
            updateSearching()
            let browser = NWBrowser(for: .bonjour(type: VoiceRelayServer.serviceType, domain: nil),
                                    using: .tcp)
            browser.stateUpdateHandler = { [weak self] state in
                if case .failed(let error) = state {
                    NSLog("Voice relay browse failed: %@", String(describing: error))
                    DispatchQueue.main.async { self?.stop() }
                }
            }
            browser.browseResultsChangedHandler = { [weak self] results, _ in
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    self.results = results
                    self.updateSearching()
                    self.maybeConnect()
                    self.notify()
                }
            }
            browser.start(queue: queue)
            self.browser = browser
            notify()
            NSLog("Voice relay: searching for shared robots")
        }
        // Browsing may already know hosts (the client outlives the pages): re-evaluate
        // now instead of waiting for the next browse change, or a page re-entry would
        // never reconnect or ask for the PIN.
        DispatchQueue.main.async { [weak self] in self?.maybeConnect() }
    }

    /// Page entry: give the PIN prompt another chance and re-evaluate the connection.
    /// Automatic asks fire once per host per session; an explicit entry may ask again.
    /// Main thread.
    func retryPending() {
        pinAsked.removeAll()
        DispatchQueue.main.async { [weak self] in self?.maybeConnect() }
    }

    /// True when the saved pick is this phone's own robot link (the voice page's mode).
    /// The Remote Mic page always speaks over the relay and resets this on entry.
    var isLocalChoice: Bool { choice == VoiceRelayClient.choiceLocal }

    func stop() {
        reconnectWork?.cancel()
        reconnectWork = nil
        browser?.cancel()
        browser = nil
        results = []
        connection?.cancel()
        connection = nil
        isSearching = false
        isHostConnected = false
        hostName = ""
        notify()
    }

    /// Names of the shared robots discovered on the LAN (connected one first).
    func hostNames() -> [String] {
        var names = candidateEndpoints().map { $0.name }
        if isHostConnected, let index = names.firstIndex(of: hostName) {
            names.remove(at: index)
            names.insert(hostName, at: 0)
        }
        return names
    }

    /// This phone's own robot link is the target: never auto-connect to a shared robot.
    func chooseLocal() {
        setChoice(VoiceRelayClient.choiceLocal)
        connectionEnded()
    }

    /// Drive the robot shared by `name` (from hostNames()).
    func chooseHost(_ name: String) {
        pinAsked.remove(name)  // an explicit pick may ask for the PIN again
        setChoice(name)
        if isHostConnected, name != hostName {
            connectionEnded()
        }
        maybeConnect()
    }

    /// Back to the default: auto-connect to the first shared robot.
    func chooseAuto() {
        setChoice("")
        maybeConnect()
    }

    /// This phone's own robot link just came up: auto mode defers to it.
    func onLocalLinkUp() {
        if choice.isEmpty {
            chooseLocal()
        }
    }

    /// The user typed `pin` for `host`: remember it and connect.
    func setPin(_ pin: String, for host: String) {
        UserDefaults.standard.set(pin.trimmingCharacters(in: .whitespaces),
                                  forKey: Self.pinKeyPrefix + host)
        pinAsked.remove(host)
        reconnectAttempts = 0
        maybeConnect()
    }

    private func savedPin(_ host: String) -> String {
        UserDefaults.standard.string(forKey: Self.pinKeyPrefix + host) ?? ""
    }

    /// Asks for the 4-digit PIN that `host` shows on its voice control page, then calls
    /// setPin(_:for:). Used by the voice control and Remote Mic pages. Retries briefly
    /// while the page is mid-transition or another alert is up — the caller's
    /// onPinRequired is a one-shot and must not be dropped.
    static func promptForPin(from presenter: UIViewController, host: String, wrong: Bool) {
        presentPinPrompt(on: presenter, host: host, wrong: wrong, attempt: 0)
    }

    private static func presentPinPrompt(on presenter: UIViewController, host: String,
                                         wrong: Bool, attempt: Int) {
        guard presenter.viewIfLoaded?.window != nil, presenter.presentedViewController == nil else {
            if attempt < 8 {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
                    presentPinPrompt(on: presenter, host: host, wrong: wrong, attempt: attempt + 1)
                }
            }
            return
        }
        let format = L10n.string(wrong ? "voice_relay_pin_wrong" : "voice_relay_pin_message")
        let alert = UIAlertController(title: L10n.string("voice_relay_pin_title"),
                                      message: String(format: format, host),
                                      preferredStyle: .alert)
        alert.addTextField { field in
            field.keyboardType = .numberPad
            field.isSecureTextEntry = true
            field.placeholder = "0000"
        }
        alert.addAction(UIAlertAction(title: L10n.string("voice_cancel"), style: .cancel))
        alert.addAction(UIAlertAction(title: L10n.string("voice_relay_pin_connect"),
                                      style: .default) { [weak alert] _ in
            let pin = alert?.textFields?.first?.text?
                .trimmingCharacters(in: .whitespaces) ?? ""
            if pin.count == 4 {
                VoiceRelayClient.shared.setPin(pin, for: host)
            } else {
                // Not a PIN: ask again instead of silently giving up.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                    presentPinPrompt(on: presenter, host: host, wrong: false, attempt: 0)
                }
            }
        })
        presenter.present(alert, animated: true)
    }

    private func setChoice(_ newChoice: String) {
        choice = newChoice
        UserDefaults.standard.set(newChoice, forKey: choiceKey)
        updateSearching()
        notify()
    }

    /// Forwards a duration-bounded move to the host (it auto-stops when the duration ends).
    func sendMove(l: Float, r: Float, ms: Int) {
        send(["t": "move", "l": l, "r": r, "ms": ms])
    }

    func sendStop() {
        send(["t": "stop"])
    }

    /// Streams one 60 ms opus frame of this phone's microphone to the host voice control
    /// page (Remote Mic page); rms is the frame's level (0..1) for its waveform display.
    func sendMicFrame(base64: String, rms: Float) {
        send(["t": "mic", "b": base64, "r": rms])
    }

    /// Opens/closes the microphone stream; the host answers with onMicAck.
    func sendMicState(on: Bool) {
        send(["t": "mic", "on": on])
    }

    private func send(_ object: [String: Any]) {
        guard isHostConnected, let connection = connection,
              let data = try? JSONSerialization.data(withJSONObject: object),
              let line = String(data: data, encoding: .utf8)
        else { return }
        connection.send(content: (line + "\n").data(using: .utf8),
                        completion: .contentProcessed { _ in })
    }

    // MARK: Discovery and connection

    /// Browse results as (name, endpoint), our own advertisement filtered out, sorted by name.
    private func candidateEndpoints() -> [(name: String, endpoint: NWEndpoint)] {
        var candidates: [(name: String, endpoint: NWEndpoint)] = []
        for result in results {
            if case .service(let name, _, _, _) = result.endpoint,
               name != VoiceRelayServer.advertisedName {
                candidates.append((name, result.endpoint))
            }
        }
        return candidates.sorted { $0.name < $1.name }
    }

    private func maybeConnect() {
        guard !isHostConnected, connection == nil, choice != VoiceRelayClient.choiceLocal else { return }
        let candidates = candidateEndpoints()
        let target = choice.isEmpty
            ? candidates.first
            : candidates.first(where: { $0.name == choice })
        guard let target = target else { return }
        if savedPin(target.name).isEmpty {
            // No PIN for this host yet: ask the user once instead of connecting.
            if pinAsked.insert(target.name).inserted {
                onPinRequired?(target.name, false)
            }
            return
        }
        connect(to: target.endpoint)
    }

    private func connect(to endpoint: NWEndpoint) {
        reconnectWork?.cancel()
        reconnectWork = nil
        let name: String
        if case .service(let serviceName, _, _, _) = endpoint {
            name = serviceName
        } else {
            name = ""
        }
        let connection = NWConnection(to: endpoint, using: .tcp)
        self.connection = connection
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            DispatchQueue.main.async {
                // Ignore a stale socket's callbacks: only the current one may end the link.
                guard let self = self, let connection = connection,
                      self.connection === connection else { return }
                switch state {
                case .ready:
                    // Not connected yet: send the PIN, the host answers {"t":"auth","ok":...}.
                    if let data = try? JSONSerialization.data(withJSONObject: [
                        "t": "hello", "name": VoiceRelayServer.advertisedName,
                        "pin": self.savedPin(name),
                    ]), let line = String(data: data, encoding: .utf8) {
                        connection.send(content: (line + "\n").data(using: .utf8),
                                        completion: .contentProcessed { _ in })
                    }
                case .failed, .cancelled:
                    self.connectionEnded()
                default:
                    break
                }
            }
        }
        connection.start(queue: queue)
        queue.async {
            self.receiveBuffer = Data()
            self.receive(on: connection)
        }
    }

    /// Host → remote lines: only the microphone ack matters here. Runs on the queue.
    private func receive(on connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) {
            [weak self] data, _, isComplete, error in
            guard let self = self else { return }
            // A stale socket's tail must not touch the live link's buffer.
            guard self.connection === connection else { return }
            if let data = data, !data.isEmpty {
                self.receiveBuffer.append(data)
                while let newline = self.receiveBuffer.firstIndex(of: UInt8(ascii: "\n")) {
                    let line = self.receiveBuffer.subdata(in: 0..<newline)
                    self.receiveBuffer.removeSubrange(0...newline)
                    if let text = String(data: line, encoding: .utf8), text.count <= 4096 {
                        self.handleServerLine(text, from: connection)
                    }
                }
            }
            if isComplete || error != nil {
                DispatchQueue.main.async {
                    guard self.connection === connection else { return }
                    self.connectionEnded()
                }
            } else {
                self.receive(on: connection)
            }
        }
    }

    private func handleServerLine(_ line: String, from connection: NWConnection) {
        guard let data = line.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        else { return }
        if (root["t"] as? String) == "auth" {
            let ok = (root["ok"] as? Bool) ?? false
            DispatchQueue.main.async { [weak self] in
                guard let self = self, self.connection === connection,
                      case .service(let name, _, _, _) = connection.endpoint else { return }
                if ok {
                    self.isHostConnected = true
                    self.hostName = name
                    self.reconnectAttempts = 0
                    self.updateSearching()
                    self.notify()
                    NSLog("Voice relay connected to '%@'", name)
                } else {
                    // Wrong PIN: forget it and ask; the host closes the socket.
                    UserDefaults.standard.removeObject(forKey: Self.pinKeyPrefix + name)
                    self.pinAsked.insert(name)
                    self.onPinRequired?(name, true)
                }
            }
            return
        }
        guard (root["t"] as? String) == "micack" else { return }
        let live = (root["live"] as? Bool) ?? false
        DispatchQueue.main.async { [weak self] in
            self?.onMicAck?(live)
        }
    }

    private func connectionEnded() {
        connection?.cancel()
        connection = nil
        let wasConnected = isHostConnected
        isHostConnected = false
        hostName = ""
        updateSearching()
        if wasConnected {
            notify()
        }
        scheduleRetry()
    }

    private func scheduleRetry() {
        guard !isHostConnected, browser != nil, choice != VoiceRelayClient.choiceLocal else { return }
        reconnectWork?.cancel()
        let delays: [TimeInterval] = [1, 2, 5]
        let delay = delays[min(reconnectAttempts, delays.count - 1)]
        reconnectAttempts += 1
        let work = DispatchWorkItem { [weak self] in self?.maybeConnect() }
        reconnectWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func updateSearching() {
        isSearching = browser != nil && !isHostConnected && choice != VoiceRelayClient.choiceLocal
    }

    private func notify() {
        onStateChange?()
    }
}
