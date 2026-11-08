//
//  VoiceRelayServer.swift
//  OpenBot
//
//  WiFi voice-relay host: the phone that holds the robot link (Bluetooth) shares it with
//  other phones on the same network. Remote phones run their own voice control page
//  (mic to Xiaozhi) and forward only drive commands here as newline-delimited JSON, so
//  anyone nearby can drive the robot by voice without pairing Bluetooth:
//
//    {"t":"hello","name":"iPhone","pin":"1234"}  remote greeting + the host's PIN (required)
//    {"t":"move","l":0.75,"r":0.75,"ms":1200}
//    {"t":"stop"}
//    {"t":"ping"}
//    {"t":"mic","on":true}                remote microphone opens (Remote Mic page)
//    {"t":"mic","b":"<base64 opus>","r":0.4}  one 60 ms mic frame + its level
//    {"t":"mic","on":false}               remote microphone closes
//
//  Several phones may stream at once (two people, two Remote Mic pages). The host's Xiaozhi
//  session hears one voice at a time: the phone that is speaking holds the "floor" and its
//  frames are forwarded; another phone takes the floor when it speaks after the holder has
//  been quiet for floorHoldSeconds. Every streaming phone is told who holds it with
//  {"t":"floor","name":"<phone>","you":true|false,"phones":2}.
//
//  Answered by {"t":"hello","name":"<host>","proto":2} on connect,
//  {"t":"auth","ok":true|false} to the remote's hello, {"t":"pong"} to pings and {"t":"micack","live":<bool>} to "mic" "on" (live = the host voice control
//  page is open and hearing the microphone). Advertised as _friendbot-ctrl._tcp. over
//  Bonjour (VoiceRelayClient finds it).
//
//  Pro remote pages (Wi-Fi Mic Pro / Internet Mic Pro) also send
//  {"t":"pro","on":true,"watch":1500,"cam":true,"fps":4} and pings with an "id" (echoed in the
//  pong for the link delay). They then get {"t":"heard"|"said","text":..} (what Xiaozhi heard and
//  answered), {"t":"status","bat":..,"chg":..} every 5 s, {"t":"cam","b":<jpeg>} while the camera
//  is on, and auto-stop: a Pro remote silent longer than its "watch" ms while the robot moves
//  stops it (told with {"t":"safety","why":"link"} when it is heard again).
//
//  Safety rules — the robot always fails safe:
//    - every move is duration-bounded and resent every 250 ms, then auto-stops;
//    - "stop" from anyone stops everyone and latches until the next move;
//    - a remote that disconnects mid-move is cancelled at once;
//    - while a host-side (voice page) move runs, it owns the wire — remote ticks wait;
//    - all output goes through the normal bluetooth link, so the STOP latch applies.
//
//  PIN: the host shows a 4-digit PIN on its voice control page. A remote must send it in
//  its hello before any move, stop or microphone message is accepted; a wrong PIN closes
//  the connection, and after 5 wrong PINs every attempt is refused for 5 minutes. So a
//  stranger on the same WiFi cannot drive the robot or speak into the host's Xiaozhi session.
//
//  Start it from the voice control page once the robot link is up (start()) and stop it when
//  the page goes away (stop()): the robot is shared only while that page is open.
//

import Foundation
import Network
import UIKit

/// Remote-microphone feed for the host voice page (registered while the page is alive).
protocol RelayMicSink: AnyObject {
    /// A remote phone's microphone started/stopped feeding the page's session.
    func onRemoteMic(active: Bool, phoneName: String)
    /// One 60 ms opus frame from that microphone (base64 already decoded).
    func onRemoteMicFrame(opus: Data, rms: Float)
    /// True while the page can pass the microphone on (its Xiaozhi session is connected).
    var isMicReady: Bool { get }
}

final class VoiceRelayServer {

    static let shared = VoiceRelayServer()
    static let serviceType = "_friendbot-ctrl._tcp."
    /// The Bonjour name other phones see (and the client filters out as "us").
    static let advertisedName = UIDevice.current.name
    private static let driveTickSeconds = 0.25
    private static let pinKey = "voice_relay_host_pin"
    private static let maxPinFailures = 5
    private static let pinLockoutSeconds: TimeInterval = 5 * 60
    /// A frame at or above this level (0..1) counts as speech for the floor hand-over.
    private static let floorSpeechRms: Float = 0.03
    /// The floor holder keeps it until it has been quiet this long.
    private static let floorHoldSeconds: TimeInterval = 1.5

    /// The 4-digit PIN remotes must send; one per phone, kept across launches.
    let pin: String = {
        if let saved = UserDefaults.standard.string(forKey: VoiceRelayServer.pinKey),
           saved.count == 4, saved.allSatisfy({ $0.isASCII && $0.isNumber }) {
            return saved
        }
        let fresh = String(format: "%04d", Int.random(in: 0..<10000))
        UserDefaults.standard.set(fresh, forKey: VoiceRelayServer.pinKey)
        return fresh
    }()
    // PIN check; touched on the main thread only.
    private var pinFailures = 0
    private var pinLockedUntil: TimeInterval = 0

    private let queue = DispatchQueue(label: "friendbot.relay.server")
    private var listener: NWListener?
    private var connections: [RelayConnection] = []

    // Drive state; touched on the main thread only.
    private var remoteLeft: Float = 0
    private var remoteRight: Float = 0
    private var remoteEndsAt: TimeInterval = 0
    private var remoteOwner: RelayConnection?
    private var localEndsAt: TimeInterval = 0
    private var driveTimer: Timer?
    private(set) var isStopLatched = false

    // Remote microphone state; touched on the main thread only.
    private weak var micSink: RelayMicSink?
    /// Every phone whose Remote Mic page is streaming, in the order they opened.
    private var micClients: [RelayConnection] = []
    /// The streaming phone whose frames reach the session (nil when nobody streams).
    private var floorHolder: RelayConnection?
    /// When the floor holder last sent a speech-level frame.
    private var floorVoiceAt: TimeInterval = 0

    // Pro remote pages; main thread only.
    /// The voice page's camera: newest small JPEG, or nil (set while the page is open).
    var proCamera: (() -> Data?)?
    private var proTimer: Timer?
    private var lastStatusAt: TimeInterval = 0
    private static let proTickSeconds = 0.1
    private static let statusEverySeconds: TimeInterval = 5
    /// Skip camera frames for a socket remote whose unsent data is above this (slow link).
    private static let camBacklogLimit = 96_000

    private init() {}

    var isRunning: Bool { listener != nil }

    /// The TCP port remotes connect to (47821 unless it was busy); nil until listening.
    var port: UInt16? { listener?.port?.rawValue }

    /// Starts serving (idempotent). Safe to call from the main thread only. Tries the fixed
    /// port 47821 first, so the QR / typed IP address reaches it, then any free port.
    func start() {
        start(onFixedPort: true)
    }

    private func start(onFixedPort: Bool) {
        guard listener == nil else { return }
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        let made: NWListener?
        if onFixedPort, let fixed = NWEndpoint.Port(rawValue: RelayQr.relayPort) {
            made = (try? NWListener(using: parameters, on: fixed)) ?? (try? NWListener(using: parameters))
        } else {
            made = try? NWListener(using: parameters)
        }
        guard let listener = made else { return }
        listener.service = NWListener.Service(name: Self.advertisedName, type: Self.serviceType)
        listener.stateUpdateHandler = { [weak self, weak listener] state in
            if case .failed(let error) = state {
                NSLog("Voice relay listener failed: %@", String(describing: error))
                // Port 47821 busy: serve on any free port instead (the QR carries the port).
                if onFixedPort {
                    DispatchQueue.main.async {
                        guard let self = self, let failed = listener, self.listener === failed else { return }
                        failed.cancel()
                        self.listener = nil
                        self.start(onFixedPort: false)
                    }
                }
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            self?.accept(connection)
        }
        listener.start(queue: queue)
        self.listener = listener
        NSLog("Voice relay serving as '%@'", Self.advertisedName)
    }

    /// Stops sharing (idempotent): un-advertises, closes every remote and stops any remote
    /// move. Called when the host voice control page goes away. Main thread only.
    func stop() {
        guard let listener = listener else { return }
        listener.cancel()
        self.listener = nil
        let wasDriving = remoteOwner != nil
        remoteOwner = nil
        remoteEndsAt = 0
        micClients.removeAll()
        floorHolder = nil
        stopTick()
        if wasDriving {
            sendDrive(left: 0, right: 0)
        }
        queue.async {
            let all = self.connections
            self.connections.removeAll()
            all.forEach { $0.close() }
        }
        NSLog("Voice relay stopped")
    }

    /// The host voice page started a duration-bounded move: it owns the wire until it ends.
    func noteLocalMove(endsAt: TimeInterval) {
        isStopLatched = false
        localEndsAt = endsAt
    }

    /// The host voice page stopped: cancel every remote move too (STOP is global).
    func noteLocalStop() {
        isStopLatched = true
        remoteEndsAt = 0
        remoteOwner = nil
        stopTick()
    }

    /// The host voice page registers here to receive a remote phone's microphone (Remote
    /// Mic page on the other end). Pass nil when the page goes away. A remote that is
    /// already streaming is announced to the fresh sink at once. Main thread only.
    func setMicSink(_ sink: RelayMicSink?) {
        micSink = sink
        if !micClients.isEmpty {
            if let sink = sink, let holder = floorHolder {
                sink.onRemoteMic(active: true, phoneName: holder.name)
            }
        }
        refreshMicAcks()
    }

    /// True when a streaming remote is actually heard: the page is open and Xiaozhi is up.
    private var micLive: Bool { micSink?.isMicReady ?? false }

    /// Re-tells every streaming remote whether it is heard. The host page calls this when its
    /// Xiaozhi session connects or drops, so the Remote Mic page never shows "Live" while its
    /// voice goes nowhere. Main thread only.
    func refreshMicAcks() {
        let ack = "{\"t\":\"micack\",\"live\":\(micLive)}"
        for c in micClients {
            c.send(ack)
        }
    }

    // MARK: Remotes

    // MARK: Internet remotes

    /// A remote attached through the internet relay: feed it lines, drop it when it goes.
    struct RemoteHandle {
        let deliver: (String) -> Void
        let drop: () -> Void
    }

    /// Attaches a phone reached over the internet (Internet Mic) as if it had connected by
    /// WiFi: same hello, same PIN-free join, same mic floor and STOP rules. Main thread.
    func attachRemote(send: @escaping (String) -> Void, close: @escaping () -> Void) -> RemoteHandle {
        let relay = RelayConnection(externalSend: send, externalClose: close)
        connections.append(relay)
        if let data = try? JSONSerialization.data(
                withJSONObject: ["t": "hello", "name": Self.advertisedName, "proto": 2]),
           let hello = String(data: data, encoding: .utf8) {
            relay.send(hello)
        }
        return RemoteHandle(
            deliver: { [weak self] line in
                guard line.count <= 4096 else { return }
                self?.handle(line: line, from: relay)
            },
            drop: { [weak self] in
                DispatchQueue.main.async {
                    self?.clientDropped(relay)
                    relay.close()
                }
            })
    }

    private func accept(_ connection: NWConnection) {
        let relay = RelayConnection(connection: connection, queue: queue,
                                    onLine: { [weak self] relay, line in
                                        self?.handle(line: line, from: relay)
                                    },
                                    onClose: { [weak self] relay in
                                        self?.clientDropped(relay)
                                    })
        connections.append(relay)
        relay.start()
        // JSONSerialization, not string interpolation: the device name may contain quotes.
        if let data = try? JSONSerialization.data(
                withJSONObject: ["t": "hello", "name": Self.advertisedName, "proto": 2]),
           let hello = String(data: data, encoding: .utf8) {
            relay.send(hello)
        }
    }

    private func handle(line: String, from client: RelayConnection) {
        DispatchQueue.main.async {
            guard let data = line.data(using: .utf8),
                  let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            else { return }
            let type = root["t"] as? String ?? ""
            // Nothing but hello/ping until the remote has sent the right PIN.
            guard client.authorized || type == "hello" || type == "ping" else { return }
            client.lastSeen = ProcessInfo.processInfo.systemUptime
            if client.watchTripped {
                client.watchTripped = false
                if client.watchStopped {
                    client.watchStopped = false
                    client.send("{\"t\":\"safety\",\"why\":\"link\"}")
                }
            }
            switch type {
            case "move":
                let l = (root["l"] as? NSNumber)?.floatValue ?? 0
                let r = (root["r"] as? NSNumber)?.floatValue ?? 0
                let ms = (root["ms"] as? NSNumber)?.intValue ?? 0
                self.handleMove(client, left: self.clampUnit(l), right: self.clampUnit(r),
                                ms: self.clampMs(ms))
            case "stop":
                self.handleStop()
            case "ping":
                if let id = root["id"] as? NSNumber {
                    client.send("{\"t\":\"pong\",\"id\":\(id.int64Value)}")
                } else {
                    client.send("{\"t\":\"pong\"}")
                }
            case "pro":
                self.handlePro(client, root: root)
            case "hello":
                client.name = root["name"] as? String ?? ""
                self.checkPin(client, given: root["pin"] as? String ?? "")
            case "mic":
                self.handleMic(client, root: root)
            default:
                break
            }
        }
    }

    /// Main thread: accept the remote. No PIN is needed any more — a phone joins by WiFi
    /// discovery or by the robot phone's QR / IP address. A "pin" an older remote still sends
    /// is ignored.
    private func checkPin(_ client: RelayConnection, given: String) {
        guard !client.authorized else { return }
        client.authorized = true
        client.send("{\"t\":\"auth\",\"ok\":true}")
        NSLog("Voice relay: '%@' joined", client.name)
    }

    /// A remote phone's microphone (Remote Mic page): "on":true/false opens and closes the
    /// stream, "b" carries one base64 60 ms opus frame with its level "r". The host voice
    /// page's sink receives it; when no page is open the stream is refused
    /// ({"t":"micack","live":false}) so the remote can say so. Main thread only.
    private func handleMic(_ client: RelayConnection, root: [String: Any]) {
        if let on = root["on"] as? Bool {
            if on {
                let first = micClients.isEmpty
                if !micClients.contains(where: { $0 === client }) {
                    micClients.append(client)
                }
                if floorHolder == nil {
                    floorHolder = client
                    floorVoiceAt = ProcessInfo.processInfo.systemUptime
                }
                if first, let sink = micSink {
                    sink.onRemoteMic(active: true, phoneName: client.name)
                }
                client.send("{\"t\":\"micack\",\"live\":\(micLive)}")
                announceFloor()
            } else if micClients.contains(where: { $0 === client }) {
                micClients.removeAll { $0 === client }
                micReleased(client)
            }
            return
        }
        guard let sink = micSink, micClients.contains(where: { $0 === client }),
              let b64 = root["b"] as? String else { return }
        let rms = (root["r"] as? NSNumber)?.floatValue ?? 0
        let now = ProcessInfo.processInfo.systemUptime
        if floorHolder !== client {
            // Another phone may take the floor only by speaking while the holder is quiet.
            let holderQuiet = floorHolder == nil || now - floorVoiceAt >= Self.floorHoldSeconds
            guard rms >= Self.floorSpeechRms, holderQuiet else { return }
            floorHolder = client
            announceFloor()
            NSLog("Voice relay: '%@' took the microphone floor", client.name)
        }
        if rms >= Self.floorSpeechRms {
            floorVoiceAt = now
        }
        guard let opus = Data(base64Encoded: b64) else { return }
        sink.onRemoteMicFrame(opus: opus, rms: rms)
    }

    /// A phone stopped streaming (or left): pass the floor on, or end the remote mic.
    private func micReleased(_ client: RelayConnection) {
        if floorHolder === client {
            floorHolder = micClients.first
            floorVoiceAt = 0 // whoever speaks next may take it at once
        }
        if micClients.isEmpty {
            micSink?.onRemoteMic(active: false, phoneName: client.name)
        } else {
            announceFloor()
        }
    }

    /// Tells every streaming phone who holds the floor and how many phones share the mic.
    private func announceFloor() {
        guard let holder = floorHolder else { return }
        for c in micClients {
            let msg: [String: Any] = ["t": "floor", "name": holder.name,
                                      "you": c === holder, "phones": micClients.count]
            if let data = try? JSONSerialization.data(withJSONObject: msg),
               let text = String(data: data, encoding: .utf8) {
                c.send(text)
            }
        }
    }

    private func handleMove(_ client: RelayConnection, left: Float, right: Float, ms: Int) {
        isStopLatched = false
        remoteLeft = left
        remoteRight = right
        remoteEndsAt = ProcessInfo.processInfo.systemUptime + Double(ms) / 1000
        remoteOwner = client
        startTick()
    }

    private func handleStop() {
        isStopLatched = true
        remoteEndsAt = 0
        remoteOwner = nil
        stopTick()
        sendDrive(left: 0, right: 0)
    }

    private func clientDropped(_ client: RelayConnection) {
        connections.removeAll { $0 === client }
        if remoteOwner === client {
            remoteOwner = nil
            stopTick()
            sendDrive(left: 0, right: 0)
        }
        if micClients.contains(where: { $0 === client }) {
            micClients.removeAll { $0 === client }
            micReleased(client)
        }
        NSLog("Voice relay remote left (%d connected)", connections.count)
    }

    // MARK: Pro remote pages

    /// Tells Pro remotes what Xiaozhi heard ("heard") or answered ("said"). Main thread.
    func sendProText(_ type: String, _ text: String) {
        guard !text.isEmpty else { return }
        let line = Self.json(["t": type, "text": String(text.prefix(400))])
        for c in connections where c.authorized && c.pro { c.send(line) }
    }

    private func handlePro(_ client: RelayConnection, root: [String: Any]) {
        if let on = root["on"] as? Bool { client.pro = on }
        if let w = root["watch"] as? NSNumber {
            let ms = w.intValue
            client.watchMs = ms <= 0 ? 0 : max(800, min(6000, ms))
        }
        if let cam = root["cam"] as? Bool { client.camOn = cam }
        if let fps = root["fps"] as? NSNumber { client.camFps = max(1, min(8, fps.intValue)) }
        guard client.pro else {
            client.camOn = false
            client.watchMs = 0
            return
        }
        client.send(statusLine())
        if proTimer == nil {
            proTimer = Timer.scheduledTimer(withTimeInterval: Self.proTickSeconds, repeats: true) {
                [weak self] _ in self?.proTick()
            }
        }
    }

    /// Every 100 ms while a Pro remote is here: watchdog, status, camera.
    private func proTick() {
        let now = ProcessInfo.processInfo.systemUptime
        let pros = connections.filter { $0.authorized && $0.pro }
        guard !pros.isEmpty else {
            proTimer?.invalidate()
            proTimer = nil
            return
        }
        for c in pros where c.watchMs > 0 && !c.watchTripped
            && (now - c.lastSeen) * 1000 > Double(c.watchMs) {
            c.watchTripped = true
            if robotMoving(now) {
                c.watchStopped = true
                NSLog("Voice relay: '%@' went silent while driving: STOP", c.name)
                handleStop()
            }
        }
        if now - lastStatusAt >= Self.statusEverySeconds {
            lastStatusAt = now
            let status = statusLine()
            for c in pros { c.send(status) }
        }
        let wantCam = pros.filter {
            $0.camOn && now - $0.lastCamSent >= 1 / Double($0.camFps)
                && $0.backlog < Self.camBacklogLimit
        }
        if !wantCam.isEmpty, let jpeg = proCamera?() {
            let line = "{\"t\":\"cam\",\"b\":\"\(jpeg.base64EncodedString())\"}"
            for c in wantCam {
                c.lastCamSent = now
                c.send(line)
            }
        }
    }

    private func robotMoving(_ now: TimeInterval) -> Bool {
        (remoteOwner != nil && now < remoteEndsAt) || now < localEndsAt
    }

    /// {"t":"status","bat":72,"chg":false,"moving":false}
    private func statusLine() -> String {
        let device = UIDevice.current
        device.isBatteryMonitoringEnabled = true
        var s: [String: Any] = ["t": "status",
                                "moving": robotMoving(ProcessInfo.processInfo.systemUptime)]
        if device.batteryLevel >= 0 {
            s["bat"] = Int((device.batteryLevel * 100).rounded())
            s["chg"] = device.batteryState == .charging || device.batteryState == .full
        }
        return Self.json(s)
    }

    private static func json(_ obj: [String: Any]) -> String {
        guard let d = try? JSONSerialization.data(withJSONObject: obj),
              let text = String(data: d, encoding: .utf8) else { return "{}" }
        return text
    }

    // MARK: Drive output

    private func startTick() {
        guard driveTimer == nil else { return }
        driveTimer = Timer.scheduledTimer(withTimeInterval: Self.driveTickSeconds,
                                          repeats: true) { [weak self] _ in
            self?.tick()
        }
    }

    private func stopTick() {
        driveTimer?.invalidate()
        driveTimer = nil
    }

    /// 250 ms resend tick for the active remote move (main thread).
    private func tick() {
        if remoteOwner == nil || ProcessInfo.processInfo.systemUptime >= remoteEndsAt {
            remoteOwner = nil
            stopTick()
            sendDrive(left: 0, right: 0)
            return
        }
        if ProcessInfo.processInfo.systemUptime >= localEndsAt {
            // The host page is idle: the remote owns the wire.
            sendDrive(left: remoteLeft, right: remoteRight)
        }
    }

    private func sendDrive(left: Float, right: Float) {
        guard isBluetoothConnected else { return }
        let l = Int((left * 255).rounded())
        let r = Int((right * 255).rounded())
        bluetoothDataController.shared.sendData(payload: "c\(l),\(r)\n")
    }

    private func clampUnit(_ v: Float) -> Float {
        max(-1, min(1, v))
    }

    private func clampMs(_ ms: Int) -> Int {
        max(300, min(8000, ms))
    }
}

/// One connected remote: reads newline-delimited JSON off its socket.
private final class RelayConnection {

    private let connection: NWConnection?
    /// Set for a remote reached through the internet relay (Internet Mic) instead of a socket.
    private var externalSend: ((String) -> Void)?
    private var externalClose: (() -> Void)?
    private let queue: DispatchQueue
    private let onLine: (RelayConnection, String) -> Void
    private let onClose: (RelayConnection) -> Void
    private var buffer = Data()
    private var closed = false
    /// The remote phone's name (its hello); empty until it greets.
    var name = ""
    /// Sent the right PIN in its hello (main thread only).
    var authorized = false

    // Pro remote page wishes and watchdog (main thread only).
    var pro = false
    var camOn = false
    var camFps = 3
    var watchMs = 0
    var lastSeen = ProcessInfo.processInfo.systemUptime
    var watchTripped = false
    var watchStopped = false
    var lastCamSent: TimeInterval = 0
    /// Bytes handed to the socket but not sent yet.
    private let pendingLock = NSLock()
    private var pending = 0
    var backlog: Int {
        pendingLock.lock()
        defer { pendingLock.unlock() }
        return pending
    }

    init(connection: NWConnection, queue: DispatchQueue,
         onLine: @escaping (RelayConnection, String) -> Void,
         onClose: @escaping (RelayConnection) -> Void) {
        self.connection = connection
        self.queue = queue
        self.onLine = onLine
        self.onClose = onClose
    }

    /// A remote reached over the internet: lines go out through `send`, `close` drops it.
    init(externalSend send: @escaping (String) -> Void, externalClose close: @escaping () -> Void) {
        self.connection = nil
        self.queue = .main
        self.onLine = { _, _ in }
        self.onClose = { _ in }
        self.externalSend = send
        self.externalClose = close
    }

    func start() {
        guard let connection = connection else { return }
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled:
                self?.finish()
            default:
                break
            }
        }
        connection.start(queue: queue)
        receive()
    }

    private func receive() {
        guard let connection = connection else { return }
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] data, _, isComplete, error in
            guard let self = self else { return }
            if let data = data, !data.isEmpty {
                self.buffer.append(data)
                while let newline = self.buffer.firstIndex(of: UInt8(ascii: "\n")) {
                    let line = self.buffer.subdata(in: 0..<newline)
                    self.buffer.removeSubrange(0...newline)
                    if let text = String(data: line, encoding: .utf8), text.count <= 4096 {
                        self.onLine(self, text)
                    }
                }
            }
            if isComplete || error != nil {
                self.finish()
            } else {
                self.receive()
            }
        }
    }

    private func finish() {
        guard !closed else { return }
        closed = true
        onClose(self)
    }

    func send(_ text: String) {
        if let external = externalSend {
            external(text)
            return
        }
        guard let data = (text + "\n").data(using: .utf8) else { return }
        pendingLock.lock()
        pending += data.count
        pendingLock.unlock()
        connection?.send(content: data, completion: .contentProcessed { [weak self] _ in
            guard let self = self else { return }
            self.pendingLock.lock()
            self.pending -= data.count
            self.pendingLock.unlock()
        })
    }

    /// Closes the socket after any queued send; the state handler then reports the drop.
    func close() {
        if externalSend != nil {
            let done = externalClose
            externalSend = nil
            externalClose = nil
            done?()
            return
        }
        connection?.send(content: nil, contentContext: .finalMessage, isComplete: true,
                         completion: .contentProcessed { [weak self] _ in
                             self?.connection?.cancel()
                         })
    }
}
