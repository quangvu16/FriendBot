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

import Foundation
import Network

final class VoiceRelayClient {

    static let shared = VoiceRelayClient()

    /// Host-side status; fired on the main queue.
    var onStateChange: (() -> Void)?

    /// The host answered a sendMicState(on:): live says whether its voice control page is
    /// open and hearing the microphone. Fired on the main queue.
    var onMicAck: ((Bool) -> Void)?

    private(set) var isSearching = false
    private(set) var isHostConnected = false
    private(set) var hostName = ""

    /// The current pick: a host name, "local" or "" (automatic).
    private(set) var choice = ""

    private let choiceKey = "voice_relay_choice"
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
    func start() {
        guard browser == nil else { return }
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
                    self.isHostConnected = true
                    self.hostName = name
                    self.reconnectAttempts = 0
                    self.updateSearching()
                    self.notify()
                    NSLog("Voice relay connected to '%@'", name)
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
                        self.handleServerLine(text)
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

    private func handleServerLine(_ line: String) {
        guard let data = line.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              (root["t"] as? String) == "micack"
        else { return }
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
