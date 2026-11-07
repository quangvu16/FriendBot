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
//  Answered by {"t":"hello","name":"<host>","proto":2} on connect,
//  {"t":"auth","ok":true|false} to the remote's hello, {"t":"pong"} to pings and {"t":"micack","live":<bool>} to "mic" "on" (live = the host voice control
//  page is open and hearing the microphone). Advertised as _friendbot-ctrl._tcp. over
//  Bonjour (VoiceRelayClient finds it).
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
    private var micOwner: RelayConnection?

    private init() {}

    var isRunning: Bool { listener != nil }

    /// Starts serving (idempotent). Safe to call from the main thread only.
    func start() {
        guard listener == nil else { return }
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        guard let listener = try? NWListener(using: parameters) else { return }
        listener.service = NWListener.Service(name: Self.advertisedName, type: Self.serviceType)
        listener.stateUpdateHandler = { state in
            if case .failed(let error) = state {
                NSLog("Voice relay listener failed: %@", String(describing: error))
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
        micOwner = nil
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
        if let owner = micOwner {
            if let sink = sink {
                sink.onRemoteMic(active: true, phoneName: owner.name)
            }
            // Keep the streaming remote's liveness in step with the page.
            owner.send("{\"t\":\"micack\",\"live\":\(sink != nil)}")
        }
    }

    // MARK: Remotes

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
                client.send("{\"t\":\"pong\"}")
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

    /// Main thread: accept or refuse a remote's PIN, with a lockout against guessing.
    private func checkPin(_ client: RelayConnection, given: String) {
        guard !client.authorized else { return }
        let now = ProcessInfo.processInfo.systemUptime
        let locked = now < pinLockedUntil
        if !locked && given == pin {
            client.authorized = true
            pinFailures = 0
            client.send("{\"t\":\"auth\",\"ok\":true}")
            NSLog("Voice relay: '%@' joined with the PIN", client.name)
            return
        }
        if !locked {
            pinFailures += 1
            if pinFailures >= Self.maxPinFailures {
                pinFailures = 0
                pinLockedUntil = now + Self.pinLockoutSeconds
                NSLog("Voice relay: too many wrong PINs, refusing remotes for 5 minutes")
            }
        }
        client.send("{\"t\":\"auth\",\"ok\":false}")
        client.close()
    }

    /// A remote phone's microphone (Remote Mic page): "on":true/false opens and closes the
    /// stream, "b" carries one base64 60 ms opus frame with its level "r". The host voice
    /// page's sink receives it; when no page is open the stream is refused
    /// ({"t":"micack","live":false}) so the remote can say so. Main thread only.
    private func handleMic(_ client: RelayConnection, root: [String: Any]) {
        if let on = root["on"] as? Bool {
            if on {
                if let owner = micOwner, owner !== client, let sink = micSink {
                    sink.onRemoteMic(active: false, phoneName: owner.name)
                }
                micOwner = client
                if let sink = micSink {
                    sink.onRemoteMic(active: true, phoneName: client.name)
                }
                client.send("{\"t\":\"micack\",\"live\":\(micSink != nil)}")
            } else if micOwner === client {
                micOwner = nil
                micSink?.onRemoteMic(active: false, phoneName: client.name)
            }
            return
        }
        guard micOwner === client, let sink = micSink, let b64 = root["b"] as? String,
              let opus = Data(base64Encoded: b64) else { return }
        let rms = (root["r"] as? NSNumber)?.floatValue ?? 0
        sink.onRemoteMicFrame(opus: opus, rms: rms)
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
        if micOwner === client {
            micOwner = nil
            micSink?.onRemoteMic(active: false, phoneName: client.name)
        }
        NSLog("Voice relay remote left (%d connected)", connections.count)
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

    private let connection: NWConnection
    private let queue: DispatchQueue
    private let onLine: (RelayConnection, String) -> Void
    private let onClose: (RelayConnection) -> Void
    private var buffer = Data()
    private var closed = false
    /// The remote phone's name (its hello); empty until it greets.
    var name = ""
    /// Sent the right PIN in its hello (main thread only).
    var authorized = false

    init(connection: NWConnection, queue: DispatchQueue,
         onLine: @escaping (RelayConnection, String) -> Void,
         onClose: @escaping (RelayConnection) -> Void) {
        self.connection = connection
        self.queue = queue
        self.onLine = onLine
        self.onClose = onClose
    }

    func start() {
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
        connection.send(content: (text + "\n").data(using: .utf8),
                        completion: .contentProcessed { _ in })
    }

    /// Closes the socket after any queued send; the state handler then reports the drop.
    func close() {
        connection.send(content: nil, contentContext: .finalMessage, isComplete: true,
                        completion: .contentProcessed { [weak self] _ in
                            self?.connection.cancel()
                        })
    }
}
