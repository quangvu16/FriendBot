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

import CoreImage
import CryptoKit
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

    /// Several phones are streaming to the same host: (speaker, you, phones) — speaker holds
    /// the microphone floor (you: it is this phone), phones are streaming in all. Main queue.
    var onMicFloor: ((String, Bool, Int) -> Void)?

    /// Every other host line (pong, heard, said, status, cam, safety) for the Pro pages. Main queue.
    var onExtra: (([String: Any]) -> Void)?

    /// A host needs its PIN before this phone may drive through it (the Bool: the saved/typed
    /// PIN was refused). Show promptForPin(from:host:wrong:). Fired on the main queue.
    var onPinRequired: ((String, Bool) -> Void)?

    private(set) var isSearching = false
    private(set) var isHostConnected = false
    private(set) var hostName = ""

    /// The current pick: a host name, "local" or "" (automatic).
    private(set) var choice = ""

    private let choiceKey = "voice_relay_choice"
    /// A robot phone reached by its IP address (QR code or typed): "ip|port|name".
    private static let directKey = "voice_relay_direct"
    /// Choice prefix for a direct (IP) target.
    private static let directPrefix = "ip:"
    /// The name of the host behind the current connection (Bonjour name or QR name).
    private var currentName = ""
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

    /// The host the user picked (nil in auto mode or when this phone's own link is chosen).
    var chosenHost: String? {
        if choice.hasPrefix(Self.directPrefix) { return directTarget()?.name }
        return choice.isEmpty || choice == VoiceRelayClient.choiceLocal ? nil : choice
    }

    /// Connect straight to a robot phone by IP address — from its QR code or typed by hand.
    /// No discovery needed; remembered for the next launch. Main thread.
    func connectDirect(ip: String, port: UInt16, name: String) {
        UserDefaults.standard.set("\(ip)|\(port)|\(name)", forKey: Self.directKey)
        setChoice(Self.directPrefix + ip)
        reconnectAttempts = 0
        reconnectWork?.cancel()
        reconnectWork = nil
        connection?.cancel()
        connection = nil
        isHostConnected = false
        hostName = ""
        updateSearching()
        notify()
        maybeConnect()
    }

    private func directTarget() -> (ip: String, port: UInt16, name: String)? {
        guard let saved = UserDefaults.standard.string(forKey: Self.directKey) else { return nil }
        let parts = saved.components(separatedBy: "|")
        guard parts.count >= 3, let port = UInt16(parts[1]) else { return nil }
        return (parts[0], port, parts[2...].joined(separator: "|"))
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
        pinAsked.remove(Self.canonName(name))  // an explicit pick may ask for the PIN again
        setChoice(name)
        if isHostConnected, Self.canonName(name) != Self.canonName(hostName) {
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
                                  forKey: Self.pinKeyPrefix + Self.canonName(host))
        pinAsked.remove(Self.canonName(host))
        reconnectAttempts = 0
        maybeConnect()
    }

    private func savedPin(_ host: String) -> String {
        UserDefaults.standard.string(forKey: Self.pinKeyPrefix + Self.canonName(host)) ?? ""
    }

    /// NSD may re-register a phone as "Joy 4 (2)" after a restart; the same phone must
    /// keep matching the user's pick and its saved PIN, so compare without the suffix.
    static func canonName(_ name: String) -> String {
        name.replacingOccurrences(of: " \\(\\d+\\)$", with: "", options: .regularExpression)
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

    /// One raw JSON line to the host once linked (Pro pages: pro, ping, move, stop).
    func sendRaw(_ line: String) {
        guard isHostConnected, let connection = connection else { return }
        connection.send(content: (line + "\n").data(using: .utf8), completion: .contentProcessed { _ in })
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
               Self.canonName(name) != Self.canonName(VoiceRelayServer.advertisedName) {
                candidates.append((name, result.endpoint))
            }
        }
        return candidates.sorted { $0.name < $1.name }
    }

    private func maybeConnect() {
        guard !isHostConnected, connection == nil, choice != VoiceRelayClient.choiceLocal else { return }
        if choice.hasPrefix(Self.directPrefix) {
            // A robot phone picked by QR / IP: connect to that address directly.
            guard let direct = directTarget(),
                  let port = NWEndpoint.Port(rawValue: direct.port) else { return }
            connect(to: .hostPort(host: NWEndpoint.Host(direct.ip), port: port), name: direct.name)
            return
        }
        let candidates = candidateEndpoints()
        let target = choice.isEmpty
            ? candidates.first
            : candidates.first(where: {
                Self.canonName($0.name) == Self.canonName(choice)
            })
        guard let target = target else { return }
        // No PIN needed: connect straight away. (A robot phone running an older FriendBot
        // that still wants one answers "auth false", and onPinRequired asks for it then.)
        connect(to: target.endpoint)
    }

    private func connect(to endpoint: NWEndpoint, name givenName: String? = nil) {
        reconnectWork?.cancel()
        reconnectWork = nil
        let name: String
        if let givenName = givenName {
            name = givenName
        } else if case .service(let serviceName, _, _, _) = endpoint {
            name = serviceName
        } else {
            name = ""
        }
        currentName = name
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
                    if let text = String(data: line, encoding: .utf8), text.count <= 262_144 {
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
                guard let self = self, self.connection === connection else { return }
                let name = self.currentName
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
        if (root["t"] as? String) == "floor" {
            let speaker = (root["name"] as? String) ?? ""
            let you = (root["you"] as? Bool) ?? false
            let phones = (root["phones"] as? NSNumber)?.intValue ?? 1
            DispatchQueue.main.async { [weak self] in
                self?.onMicFloor?(speaker, you, phones)
            }
            return
        }
        guard (root["t"] as? String) == "micack" else {
            DispatchQueue.main.async { [weak self] in self?.onExtra?(root) }
            return
        }
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
        guard !isHostConnected, choice != VoiceRelayClient.choiceLocal,
              browser != nil || choice.hasPrefix(Self.directPrefix) else { return }
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

// MARK: - QR pairing (Remote Mic)

/// Remote Mic pairing by QR code, shared with the Android app. The robot phone shows a QR
/// holding its IP address, relay port, name and (when known) WiFi name — no PIN:
///
///     friendbot://mic?ip=192.168.1.23&port=47821&name=Joy%204&wifi=Home
///
/// The Remote Mic page scans it and connects straight away.
enum RelayQr {

    /// The relay's fixed TCP port (when free) on both apps.
    static let relayPort: UInt16 = 47821
    private static let prefix = "friendbot://mic?"

    struct Info {
        let ip: String
        let port: UInt16
        let name: String
        let wifi: String?
    }

    static func build(ip: String, port: UInt16, name: String, wifi: String?) -> String {
        var text = prefix + "ip=" + enc(ip) + "&port=\(port)&name=" + enc(name)
        if let wifi = wifi, !wifi.isEmpty {
            text += "&wifi=" + enc(wifi)
        }
        return text
    }

    /// Reads a scanned QR; nil when it is not a FriendBot Remote Mic code.
    static func parse(_ text: String) -> Info? {
        guard text.hasPrefix(prefix) else { return nil }
        var q: [String: String] = [:]
        for part in text.dropFirst(prefix.count).split(separator: "&") {
            guard let eq = part.firstIndex(of: "="), eq != part.startIndex else { continue }
            let key = String(part[..<eq])
            let raw = String(part[part.index(after: eq)...]).replacingOccurrences(of: "+", with: " ")
            q[key] = raw.removingPercentEncoding ?? raw
        }
        guard let ip = q["ip"],
              ip.range(of: "^\\d{1,3}(\\.\\d{1,3}){3}$", options: .regularExpression) != nil
        else { return nil }
        let port = q["port"].flatMap { UInt16($0) } ?? relayPort
        guard port > 0 else { return nil }
        let name = (q["name"]?.isEmpty == false) ? q["name"]! : ip
        let wifi = (q["wifi"]?.isEmpty == false) ? q["wifi"] : nil
        return Info(ip: ip, port: port, name: name, wifi: wifi)
    }

    /// Black-on-white QR image, crisp at `size` points.
    static func image(for text: String, size: CGFloat) -> UIImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(text.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage else { return nil }
        let scale = max(1, (size * UIScreen.main.scale) / output.extent.width)
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        guard let cg = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg, scale: UIScreen.main.scale, orientation: .up)
    }

    /// This phone's IPv4 address on WiFi (en0) or on its own hotspot (bridge100), or nil.
    static func localIPv4() -> String? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }
        var found: [String: String] = [:]
        var ptr: UnsafeMutablePointer<ifaddrs>? = first
        while let current = ptr {
            let flags = Int32(current.pointee.ifa_flags)
            if let addr = current.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET),
               (flags & IFF_UP) != 0, (flags & IFF_LOOPBACK) == 0 {
                let name = String(cString: current.pointee.ifa_name)
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count),
                               nil, 0, NI_NUMERICHOST) == 0 {
                    found[name] = String(cString: host)
                }
            }
            ptr = current.pointee.ifa_next
        }
        return found["en0"] ?? found["bridge100"]
            ?? found.first(where: { $0.key.hasPrefix("en") })?.value
    }

    private static func enc(_ s: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }
}

/// The robot phone's QR, big, for the Remote Mic page on another phone to scan.
final class ShareQrController: UIViewController {

    private let qrText: String
    private let detail: String

    init(qrText: String, detail: String) {
        self.qrText = qrText
        self.detail = detail
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        let title = UILabel()
        title.text = L10n.string("voice_sharing_qr_title")
        title.font = .systemFont(ofSize: 22, weight: .bold)
        title.textAlignment = .center
        let imageView = UIImageView(image: RelayQr.image(for: qrText, size: 260))
        imageView.backgroundColor = .white
        imageView.contentMode = .scaleAspectFit
        imageView.layer.magnificationFilter = .nearest
        imageView.translatesAutoresizingMaskIntoConstraints = false
        imageView.widthAnchor.constraint(equalToConstant: 260).isActive = true
        imageView.heightAnchor.constraint(equalToConstant: 260).isActive = true
        let message = UILabel()
        message.text = L10n.string("voice_sharing_qr_message") + "\n\n" + detail
        message.font = .systemFont(ofSize: 15)
        message.textColor = .secondaryLabel
        message.numberOfLines = 0
        message.textAlignment = .center
        var doneConfig = UIButton.Configuration.filled()
        doneConfig.title = L10n.string("okAction")
        doneConfig.cornerStyle = .capsule
        let done = UIButton(configuration: doneConfig)
        done.addTarget(self, action: #selector(doneTapped), for: .touchUpInside)
        let stack = UIStackView(arrangedSubviews: [title, imageView, message, done])
        stack.axis = .vertical
        stack.spacing = 18
        stack.alignment = .center
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
        ])
    }

    @objc private func doneTapped() {
        dismiss(animated: true)
    }
}

// MARK: - Internet Mic (any WiFi or 4G/5G, through a free public MQTT broker, encrypted)

/// A tiny MQTT 3.1.1 client over secure WebSocket, just enough for the Internet Mic: CONNECT,
/// SUBSCRIBE (QoS 0), PUBLISH (QoS 0), keep-alive pings, DISCONNECT. Same protocol as the
/// Android app's MqttWs. Callbacks run on a background queue.
final class MqttWs {

    let url: URL
    private let clientId: String
    private let topics: [String]
    var onReady: ((MqttWs) -> Void)?
    var onMessage: ((MqttWs, String, Data) -> Void)?
    var onLost: ((MqttWs, String) -> Void)?

    private let queue = DispatchQueue(label: "friendbot.mqtt")
    private var session: URLSession?
    private var task: URLSessionWebSocketTask?
    private var inbox = Data()
    private var pinger: DispatchSourceTimer?
    private(set) var isReady = false
    private var ended = false
    private static let keepAlive: UInt16 = 60

    init(url: URL, clientId: String, topics: [String]) {
        self.url = url
        self.clientId = clientId
        self.topics = topics
    }

    func connect() {
        let session = URLSession(configuration: .default)
        self.session = session
        let task = session.webSocketTask(with: url, protocols: ["mqtt"])
        self.task = task
        task.resume()
        var vh = Data()
        vh.append(Self.mqttString("MQTT"))
        vh.append(4) // protocol level 3.1.1
        vh.append(0x02) // clean session
        vh.append(UInt8(Self.keepAlive >> 8))
        vh.append(UInt8(Self.keepAlive & 0xFF))
        vh.append(Self.mqttString(clientId))
        send(Self.packet(0x10, vh))
        receive()
    }

    /// Publishes `payload` to `topic` (QoS 0). False when not connected.
    @discardableResult
    func publish(_ topic: String, _ payload: Data) -> Bool {
        guard isReady, !ended else { return false }
        var body = Self.mqttString(topic)
        body.append(payload)
        send(Self.packet(0x30, body))
        return true
    }

    func close() {
        queue.async {
            guard !self.ended else { return }
            self.ended = true
            self.isReady = false
            self.pinger?.cancel()
            self.send(Data([0xE0, 0x00])) // DISCONNECT
            self.task?.cancel(with: .normalClosure, reason: nil)
            self.session?.finishTasksAndInvalidate()
        }
    }

    private func send(_ data: Data) {
        task?.send(.data(data)) { _ in }
    }

    private func receive() {
        task?.receive { [weak self] result in
            guard let self = self else { return }
            self.queue.async {
                guard !self.ended else { return }
                switch result {
                case .failure(let error):
                    self.lost(error.localizedDescription)
                case .success(let message):
                    if case .data(let data) = message {
                        self.inbox.append(data)
                        self.drain()
                    }
                    self.receive()
                }
            }
        }
    }

    /// Splits the received bytes into MQTT packets (a frame may hold several, or half of one).
    private func drain() {
        let all = [UInt8](inbox)
        var pos = 0
        while pos < all.count {
            let start = pos
            let header = Int(all[pos]); pos += 1
            var length = 0, multiplier = 1, complete = false
            while pos < all.count {
                let b = Int(all[pos]); pos += 1
                length += (b & 0x7F) * multiplier
                multiplier *= 128
                if b & 0x80 == 0 { complete = true; break }
            }
            if !complete || pos + length > all.count {
                pos = start
                break
            }
            handle(header, Array(all[pos..<(pos + length)]))
            pos += length
        }
        inbox = Data(all[pos...])
    }

    private func handle(_ header: Int, _ body: [UInt8]) {
        switch header >> 4 {
        case 2: // CONNACK
            if body.count >= 2, body[1] == 0 {
                subscribe()
            } else {
                lost("broker refused")
            }
        case 9: // SUBACK
            isReady = true
            let timer = DispatchSource.makeTimerSource(queue: queue)
            timer.schedule(deadline: .now() + .seconds(Int(Self.keepAlive / 2)),
                           repeating: .seconds(Int(Self.keepAlive / 2)))
            timer.setEventHandler { [weak self] in self?.send(Data([0xC0, 0x00])) } // PINGREQ
            timer.resume()
            pinger = timer
            onReady?(self)
        case 3: // PUBLISH
            guard body.count >= 2 else { return }
            let qos = (header >> 1) & 0x03
            let topicLen = Int(body[0]) << 8 | Int(body[1])
            guard 2 + topicLen <= body.count else { return }
            let topic = String(decoding: body[2..<(2 + topicLen)], as: UTF8.self)
            let start = 2 + topicLen + (qos > 0 ? 2 : 0)
            guard start <= body.count else { return }
            onMessage?(self, topic, Data(body[start...]))
        default:
            break // PINGRESP and the rest
        }
    }

    private func subscribe() {
        var body = Data([0x00, 0x01]) // packet id 1
        for topic in topics {
            body.append(Self.mqttString(topic))
            body.append(0x00) // QoS 0
        }
        send(Self.packet(0x82, body))
    }

    private func lost(_ why: String) {
        guard !ended else { return }
        ended = true
        isReady = false
        pinger?.cancel()
        task?.cancel(with: .goingAway, reason: nil)
        session?.finishTasksAndInvalidate()
        NSLog("MQTT %@ lost: %@", url.absoluteString, why)
        onLost?(self, why)
    }

    private static func packet(_ header: UInt8, _ body: Data) -> Data {
        var out = Data([header])
        var len = body.count
        repeat {
            var digit = UInt8(len % 128)
            len /= 128
            if len > 0 { digit |= 0x80 }
            out.append(digit)
        } while len > 0
        out.append(body)
        return out
    }

    /// MQTT string: 2-byte length + UTF-8 bytes.
    private static func mqttString(_ s: String) -> Data {
        let b = Data(s.utf8)
        var out = Data([UInt8(b.count >> 8), UInt8(b.count & 0xFF)])
        out.append(b)
        return out
    }
}

/// Internet Mic encryption: AES-256-GCM with the key from the robot phone's Internet QR.
/// A message is nonce (12) + ciphertext + tag (16) — CryptoKit's combined box, the same
/// layout the Android app writes.
enum NetCrypto {
    private static let alphabet = Array("abcdefghijkmnpqrstuvwxyz23456789")

    static func randomId(_ length: Int) -> String {
        String((0..<length).map { _ in alphabet.randomElement()! })
    }

    static func randomKey() -> Data {
        SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
    }

    static func b64(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func unb64(_ text: String) -> Data? {
        var s = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while s.count % 4 != 0 { s += "=" }
        return Data(base64Encoded: s)
    }

    static func seal(_ key: Data, _ text: String) -> Data? {
        try? AES.GCM.seal(Data(text.utf8), using: SymmetricKey(data: key)).combined
    }

    /// The decrypted text, or nil when it is not ours (wrong key, tampered, garbage).
    static func open(_ key: Data, _ data: Data) -> String? {
        guard data.count >= 28,
              let box = try? AES.GCM.SealedBox(combined: data),
              let plain = try? AES.GCM.open(box, using: SymmetricKey(data: key)) else { return nil }
        return String(data: plain, encoding: .utf8)
    }
}

extension RelayQr {
    /// The free public MQTT brokers both apps know. The robot phone listens on all of them;
    /// the Internet Mic phone uses the first that answers.
    static let brokers: [(name: String, url: String)] = [
        ("hivemq", "wss://broker.hivemq.com:8884/mqtt"),
        ("emqx", "wss://broker.emqx.io:8084/mqtt"),
    ]

    /// What an Internet QR says: a private room, its secret key, the robot phone's name.
    struct NetInfo {
        let room: String
        let key: String
        let name: String
    }

    private static let netPrefix = "friendbot://net?"

    /// `friendbot://net?room=…&key=…&name=…` — works over WiFi internet or 4G/5G.
    static func buildNet(room: String, key: String, name: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        func enc(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s }
        return netPrefix + "room=" + enc(room) + "&key=" + enc(key) + "&name=" + enc(name)
    }

    /// Reads an Internet QR; nil when it is not one.
    static func parseNet(_ text: String) -> NetInfo? {
        guard text.hasPrefix(netPrefix) else { return nil }
        var q: [String: String] = [:]
        for part in text.dropFirst(netPrefix.count).split(separator: "&") {
            guard let eq = part.firstIndex(of: "="), eq != part.startIndex else { continue }
            let raw = String(part[part.index(after: eq)...]).replacingOccurrences(of: "+", with: " ")
            q[String(part[..<eq])] = raw.removingPercentEncoding ?? raw
        }
        guard let room = q["room"], room.range(of: "^[a-z0-9]{8,32}$", options: .regularExpression) != nil,
              let key = q["key"], let raw = NetCrypto.unb64(key), raw.count == 32 else { return nil }
        let name = (q["name"]?.isEmpty == false) ? q["name"]! : "Robot phone"
        return NetInfo(room: room, key: key, name: name)
    }
}

/// Robot phone side of the Internet Mic: listens on a private, encrypted room on the public
/// brokers so a phone anywhere — another WiFi or 4G/5G — can speak into the Voice Control
/// page. Each internet phone becomes a VoiceRelayServer remote (attachRemote), so it gets the
/// WiFi rules. Silent 15 s = dropped. Same messages as the Android app. Main thread.
final class InternetRelayHost {

    static let shared = InternetRelayHost()

    /// (online, phones) for the Voice Control page.
    var onStatus: ((Bool, Int) -> Void)?

    private final class Phone {
        let id: String
        var broker = ""
        var lastSeen: TimeInterval = 0
        var handle: VoiceRelayServer.RemoteHandle?
        var closed = false
        init(id: String) { self.id = id }
    }

    private var brokers: [String: MqttWs] = [:]
    private var phones: [String: Phone] = [:]
    private var room = ""
    private var key = Data()
    private var keyText = ""
    private var running = false
    private var sweeper: Timer?

    private init() {}

    func start() {
        guard !running else { return }
        running = true
        loadOrCreateRoom()
        for b in RelayQr.brokers { connect(b.name, b.url) }
        sweeper = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in self?.sweep() }
    }

    func stop() {
        guard running else { return }
        running = false
        sweeper?.invalidate()
        sweeper = nil
        brokers.values.forEach { $0.close() }
        brokers.removeAll()
        Array(phones.values).forEach { drop($0) }
        phones.removeAll()
        notify()
    }

    /// The Internet QR text for this robot phone.
    func qrText() -> String {
        if room.isEmpty { loadOrCreateRoom() }
        return RelayQr.buildNet(room: room, key: keyText, name: VoiceRelayServer.advertisedName)
    }

    /// New room and key: phones with the old Internet QR can no longer connect.
    func newCode() {
        let was = running
        stop()
        UserDefaults.standard.removeObject(forKey: "internet_relay_room")
        UserDefaults.standard.removeObject(forKey: "internet_relay_key")
        room = ""
        loadOrCreateRoom()
        if was { start() }
    }

    var isOnline: Bool { brokers.values.contains { $0.isReady } }
    var phoneCount: Int { phones.count }

    private func loadOrCreateRoom() {
        let d = UserDefaults.standard
        if let r = d.string(forKey: "internet_relay_room"), r.count >= 8,
           let k = d.string(forKey: "internet_relay_key"), let raw = NetCrypto.unb64(k), raw.count == 32 {
            room = r; key = raw; keyText = k
            return
        }
        room = NetCrypto.randomId(16)
        key = NetCrypto.randomKey()
        keyText = NetCrypto.b64(key)
        d.set(room, forKey: "internet_relay_room")
        d.set(keyText, forKey: "internet_relay_key")
    }

    private var upTopic: String { "friendbot/v1/\(room)/up" }
    private var downTopic: String { "friendbot/v1/\(room)/down" }

    private func connect(_ name: String, _ urlText: String) {
        guard running, let url = URL(string: urlText) else { return }
        let m = MqttWs(url: url, clientId: "fbh-" + NetCrypto.randomId(10), topics: [upTopic])
        let key = self.key
        m.onReady = { [weak self] _ in DispatchQueue.main.async { self?.notify() } }
        m.onMessage = { [weak self] _, _, payload in
            guard let text = NetCrypto.open(key, payload) else { return }
            DispatchQueue.main.async { self?.onLine(broker: name, text: text) }
        }
        m.onLost = { [weak self] mqtt, _ in
            DispatchQueue.main.async {
                guard let self = self else { return }
                if self.brokers[name] === mqtt { self.brokers[name] = nil }
                self.notify()
                guard self.running else { return }
                DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
                    guard let self = self, self.running, self.brokers[name] == nil else { return }
                    self.connect(name, urlText)
                }
            }
        }
        brokers[name] = m
        m.connect()
    }

    private func onLine(broker: String, text: String) {
        guard running, let data = text.data(using: .utf8),
              let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let from = o["f"] as? String, let line = o["l"] as? String,
              from.range(of: "^[a-z0-9]{6,32}$", options: .regularExpression) != nil else { return }
        var phone = phones[from]
        let fresh = phone == nil
        if phone == nil {
            let p = Phone(id: from)
            phones[from] = p
            p.handle = VoiceRelayServer.shared.attachRemote(
                send: { [weak self, weak p] out in
                    guard let self = self, let p = p else { return }
                    DispatchQueue.main.async { self.sendDown(p, out) }
                },
                close: { [weak self, weak p] in
                    DispatchQueue.main.async {
                        guard let self = self, let p = p else { return }
                        p.closed = true
                        if self.phones[p.id] === p { self.phones[p.id] = nil }
                        self.notify()
                    }
                })
            phone = p
            notify()
        }
        guard let p = phone else { return }
        p.broker = broker
        p.lastSeen = ProcessInfo.processInfo.systemUptime
        if fresh && !line.contains("\"hello\"") {
            // A phone that was already talking before this robot phone restarted: let it back in.
            p.handle?.deliver("{\"t\":\"hello\",\"name\":\"Internet Mic\",\"pin\":\"\"}")
        }
        p.handle?.deliver(line)
    }

    private func sendDown(_ p: Phone, _ line: String) {
        guard !p.closed,
              let data = try? JSONSerialization.data(withJSONObject: ["t": p.id, "l": line]),
              let text = String(data: data, encoding: .utf8),
              let sealed = NetCrypto.seal(key, text) else { return }
        brokers[p.broker]?.publish(downTopic, sealed)
    }

    private func sweep() {
        let now = ProcessInfo.processInfo.systemUptime
        for p in Array(phones.values) where now - p.lastSeen > 15 { drop(p) }
    }

    private func drop(_ p: Phone) {
        phones[p.id] = nil
        if !p.closed {
            p.closed = true
            p.handle?.drop()
        }
        notify()
    }

    private func notify() {
        onStatus?(isOnline, phones.count)
    }
}

/// The Internet Mic page's link: speaks into a robot phone's Voice Control page over the
/// internet — any WiFi or 4G/5G — encrypted with the key in its Internet QR. Tries the brokers
/// in order, says hello, pings every 3 s, reports the robot phone offline after 12 s of
/// silence, and re-opens the microphone after a reconnect. Callbacks on the main queue.
final class InternetMicLink {

    enum State { case noCode, connecting, waitingRobot, connected, offline }

    static let shared = InternetMicLink()

    var onChange: (() -> Void)?
    var onMicAck: ((Bool) -> Void)?
    var onMicFloor: ((String, Bool, Int) -> Void)?
    /// Every other robot line (pong, heard, said, status, cam, safety) for the Pro pages.
    var onExtra: (([String: Any]) -> Void)?

    private(set) var state: State = .noCode
    private(set) var authed = false
    var isConnected: Bool { authed && state == .connected }
    var robotName: String { info?.name ?? "" }

    private let myId = NetCrypto.randomId(12)
    private var info: RelayQr.NetInfo?
    private var key: Data?
    private var mqtt: MqttWs?
    private var brokerIndex = 0
    private var reachedRelay = false
    private var connectStarted: TimeInterval = 0
    private var generation = 0
    private var micOn = false
    private var lastFromRobot: TimeInterval = 0
    private var ticker: Timer?
    private var running = false
    private static let savedKey = "internet_mic_qr"

    private init() {}

    func start() {
        guard !running else { notify(); return }
        running = true
        if let saved = UserDefaults.standard.string(forKey: Self.savedKey), let qr = RelayQr.parseNet(saved) {
            use(qr)
        } else {
            setState(.noCode)
        }
        ticker = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in self?.tick() }
    }

    func stop() {
        guard running else { return }
        running = false
        ticker?.invalidate()
        ticker = nil
        generation += 1
        mqtt?.close()
        mqtt = nil
        authed = false
        micOn = false
    }

    /// The user scanned a robot phone's Internet QR: remember it and connect.
    func setCode(_ qr: RelayQr.NetInfo, raw: String) {
        UserDefaults.standard.set(raw, forKey: Self.savedKey)
        brokerIndex = 0
        use(qr)
    }

    /// Forget the robot phone (stop connecting until a new QR is scanned).
    func forget() {
        UserDefaults.standard.removeObject(forKey: Self.savedKey)
        generation += 1
        mqtt?.close()
        mqtt = nil
        info = nil
        authed = false
        setState(.noCode)
    }

    func sendMicState(on: Bool) {
        micOn = on
        if authed { sendLine("{\"t\":\"mic\",\"on\":\(on)}") }
    }

    /// One raw JSON line to the robot phone once linked (Pro pages). Any thread.
    func sendRaw(_ line: String) {
        DispatchQueue.main.async { if self.authed { self.sendLine(line) } }
    }

    func sendMicFrame(base64: String, rms: Float) {
        guard authed,
              let data = try? JSONSerialization.data(withJSONObject: ["t": "mic", "b": base64, "r": rms]),
              let line = String(data: data, encoding: .utf8) else { return }
        DispatchQueue.main.async { self.sendLine(line) }
    }

    private func use(_ qr: RelayQr.NetInfo) {
        info = qr
        key = NetCrypto.unb64(qr.key)
        authed = false
        connectBroker()
    }

    private func connectBroker() {
        guard running, let info = info else { return }
        generation += 1
        let gen = generation
        mqtt?.close()
        let b = RelayQr.brokers[brokerIndex % RelayQr.brokers.count]
        guard let url = URL(string: b.url) else { return }
        setState(.connecting)
        reachedRelay = false
        connectStarted = ProcessInfo.processInfo.systemUptime
        let m = MqttWs(url: url, clientId: "fbr-" + myId, topics: ["friendbot/v1/\(info.room)/down"])
        m.onReady = { [weak self] _ in
            DispatchQueue.main.async {
                guard let self = self, gen == self.generation else { return }
                self.reachedRelay = true
                self.setState(.waitingRobot)
                self.sayHello()
            }
        }
        let key = self.key
        m.onMessage = { [weak self] _, _, payload in
            guard let key = key, let text = NetCrypto.open(key, payload) else { return }
            DispatchQueue.main.async {
                guard let self = self, gen == self.generation else { return }
                self.onDown(text)
            }
        }
        m.onLost = { [weak self] _, _ in
            DispatchQueue.main.async {
                guard let self = self, gen == self.generation else { return }
                if self.authed { self.onMicAck?(false) }
                self.authed = false
                if !self.reachedRelay { self.brokerIndex += 1 } // never reached it: next broker
                self.setState(.connecting)
                DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in
                    guard let self = self, gen == self.generation else { return }
                    self.connectBroker()
                }
            }
        }
        mqtt = m
        m.connect()
    }

    private func sayHello() {
        guard let data = try? JSONSerialization.data(
                withJSONObject: ["t": "hello", "name": UIDevice.current.name, "pin": ""]),
              let line = String(data: data, encoding: .utf8) else { return }
        sendLine(line)
    }

    private func onDown(_ text: String) {
        guard let data = text.data(using: .utf8),
              let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              (o["t"] as? String) == myId, let line = o["l"] as? String,
              let ld = line.data(using: .utf8),
              let msg = (try? JSONSerialization.jsonObject(with: ld)) as? [String: Any] else { return }
        lastFromRobot = ProcessInfo.processInfo.systemUptime
        switch msg["t"] as? String ?? "" {
        case "auth":
            if (msg["ok"] as? Bool) == true {
                authed = true
                setState(.connected)
                if micOn { sendLine("{\"t\":\"mic\",\"on\":true}") } // re-open after a reconnect
            }
        case "micack":
            onMicAck?((msg["live"] as? Bool) ?? false)
        case "floor":
            onMicFloor?(msg["name"] as? String ?? "", (msg["you"] as? Bool) ?? false,
                        (msg["phones"] as? NSNumber)?.intValue ?? 1)
        default:
            if state == .offline && authed { setState(.connected) }
            onExtra?(msg)
        }
    }

    private func tick() {
        guard running, info != nil else { return }
        let now = ProcessInfo.processInfo.systemUptime
        if state == .connecting && now - connectStarted > 12 {
            brokerIndex += 1 // this broker does not answer: next one
            connectBroker()
        } else if mqtt?.isReady == true {
            if !authed {
                sayHello()
            } else {
                sendLine("{\"t\":\"ping\"}")
                if now - lastFromRobot > 12 {
                    authed = false
                    onMicAck?(false)
                    setState(.offline)
                    sayHello()
                }
            }
        }
    }

    private func sendLine(_ line: String) {
        guard let info = info, let key = key, let m = mqtt, m.isReady,
              let data = try? JSONSerialization.data(withJSONObject: ["f": myId, "l": line]),
              let text = String(data: data, encoding: .utf8),
              let sealed = NetCrypto.seal(key, text) else { return }
        m.publish("friendbot/v1/\(info.room)/up", sealed)
    }

    private func setState(_ s: State) {
        guard s != state else { return }
        state = s
        notify()
    }

    private func notify() {
        onChange?()
    }
}
