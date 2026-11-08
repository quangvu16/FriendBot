// Links to the robot phone: Remote Mic (same WiFi, TCP 47821) and Internet Mic (any WiFi or
// 4G/5G, encrypted, through a free public MQTT broker). Same protocol as the phone apps.

import CryptoKit
import Darwin
import Foundation
import Network

// MARK: - QR codes

struct LanTarget {
    let name: String
    let endpoint: NWEndpoint
}

enum FBQR {
    static let relayPort: UInt16 = 47821
    static let discoveryPort: UInt16 = 47820

    struct Net {
        let room: String
        let key: Data
        let name: String
    }

    static func query(_ text: String, prefix: String) -> [String: String]? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard t.hasPrefix(prefix) else { return nil }
        var q: [String: String] = [:]
        for part in t.dropFirst(prefix.count).split(separator: "&") {
            guard let eq = part.firstIndex(of: "="), eq != part.startIndex else { continue }
            let raw = String(part[part.index(after: eq)...]).replacingOccurrences(of: "+", with: " ")
            q[String(part[..<eq])] = raw.removingPercentEncoding ?? raw
        }
        return q
    }

    /// friendbot://mic?ip=…&port=…&name=… (Remote Mic QR), or a bare IP[:port].
    static func parseLan(_ text: String) -> LanTarget? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        var ip = "", port = relayPort, name = ""
        if let q = query(t, prefix: "friendbot://mic?") {
            ip = q["ip"] ?? ""
            port = q["port"].flatMap { UInt16($0) } ?? relayPort
            name = q["name"] ?? ""
        } else {
            let parts = t.split(separator: ":")
            ip = parts.first.map(String.init) ?? ""
            if parts.count > 1, let p = UInt16(parts[1]) { port = p }
        }
        guard ip.range(of: "^\\d{1,3}(\\.\\d{1,3}){3}$", options: .regularExpression) != nil,
              let p = NWEndpoint.Port(rawValue: port) else { return nil }
        return LanTarget(name: name.isEmpty ? ip : name, endpoint: .hostPort(host: NWEndpoint.Host(ip), port: p))
    }

    /// friendbot://net?room=…&key=…&name=… (Internet QR).
    static func parseNet(_ text: String) -> Net? {
        guard let q = query(text, prefix: "friendbot://net?"),
              let room = q["room"], room.range(of: "^[a-z0-9]{8,32}$", options: .regularExpression) != nil,
              let keyText = q["key"], let key = unb64(keyText), key.count == 32 else { return nil }
        let name = (q["name"]?.isEmpty == false) ? q["name"]! : "Robot phone"
        return Net(room: room, key: key, name: name)
    }

    static func unb64(_ text: String) -> Data? {
        var s = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while s.count % 4 != 0 { s += "=" }
        return Data(base64Encoded: s)
    }

    static func randomId(_ n: Int) -> String {
        let a = Array("abcdefghijkmnpqrstuvwxyz23456789")
        return String((0..<n).map { _ in a.randomElement()! })
    }

    static func json(_ obj: [String: Any]) -> String {
        guard let d = try? JSONSerialization.data(withJSONObject: obj) else { return "{}" }
        return String(data: d, encoding: .utf8) ?? "{}"
    }
}

// MARK: - Link base

/// A link to the robot phone. All state lives on the main queue.
class MicLink {
    enum State { case idle, searching, connecting, connected, offline, error }

    var onChange: (() -> Void)?
    /// Every other line from the robot phone, for the Pro window. Main queue.
    var onExtra: (([String: Any]) -> Void)?
    private(set) var state: State = .idle
    private(set) var detail = ""
    var hostName = ""
    private(set) var connected = false
    private(set) var micOn = false
    private(set) var micAckLive = false
    private(set) var floorOther: String?

    func setState(_ s: State, _ d: String = "") {
        state = s
        detail = d
        if s != .connected { connected = false; micAckLive = false }
        onChange?()
    }

    /// One relay line from the robot phone (main queue).
    func robotLine(_ line: String) {
        guard let data = line.data(using: .utf8),
              let m = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return }
        switch m["t"] as? String ?? "" {
        case "auth":
            if (m["ok"] as? Bool) == true {
                connected = true
                state = .connected
                detail = ""
                if micOn { sendLine(FBQR.json(["t": "mic", "on": true])) }
                onChange?()
            } else {
                setState(.error, "the robot phone runs an older FriendBot that wants a PIN — update it")
            }
        case "micack":
            micAckLive = (m["live"] as? Bool) ?? false
            onChange?()
        case "floor":
            floorOther = (m["you"] as? Bool) == true ? nil : (m["name"] as? String)
            onChange?()
        case "hello":
            if let n = m["name"] as? String, !n.isEmpty { hostName = n }
        default:
            onExtra?(m) // pong, heard, said, status, cam, safety (Pro window)
        }
    }

    func hello() {
        sendLine(FBQR.json(["t": "hello", "name": Host.current().localizedName ?? "Mac", "pin": ""]))
    }

    func setMic(_ on: Bool) {
        micOn = on
        micAckLive = false
        if !on { floorOther = nil }
        if connected { sendLine(FBQR.json(["t": "mic", "on": on])) }
        onChange?()
    }

    /// One 60 ms Opus frame (any thread).
    func sendFrame(_ opus: Data, rms: Float) {
        guard connected else { return }
        sendLine(FBQR.json(["t": "mic", "b": opus.base64EncodedString(), "r": rms]))
    }

    func sendLine(_ line: String) {}
    func stop() {}
}

// MARK: - Remote Mic (same WiFi)

enum LanDiscovery {
    /// WiFi broadcast probe (Android robot phones answer) + Bonjour (iPhone robot phones).
    static func find(completion: @escaping ([LanTarget]) -> Void) {
        var bonjour: [LanTarget] = []
        let browser = NWBrowser(for: .bonjour(type: "_friendbot-ctrl._tcp", domain: nil), using: .tcp)
        browser.browseResultsChangedHandler = { results, _ in
            bonjour = results.compactMap { r in
                if case .service(let name, _, _, _) = r.endpoint { return LanTarget(name: name, endpoint: r.endpoint) }
                return nil
            }
        }
        browser.start(queue: .main)
        DispatchQueue.global(qos: .userInitiated).async {
            let probed = probe(timeout: 2)
            DispatchQueue.main.async {
                browser.cancel()
                completion(probed + bonjour)
            }
        }
    }

    private static func probe(timeout: TimeInterval) -> [LanTarget] {
        let fd = socket(AF_INET, SOCK_DGRAM, 0)
        guard fd >= 0 else { return [] }
        defer { close(fd) }
        var yes: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &yes, socklen_t(MemoryLayout<Int32>.size))
        var tv = timeval(tv_sec: 0, tv_usec: 300_000)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        var to = sockaddr_in()
        to.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        to.sin_family = sa_family_t(AF_INET)
        to.sin_port = in_port_t(FBQR.discoveryPort).bigEndian
        to.sin_addr.s_addr = 0xFFFF_FFFF // 255.255.255.255
        let probe = Array("FRIENDBOT?".utf8)
        var found: [String: LanTarget] = [:]
        let end = Date().addingTimeInterval(timeout)
        var nextProbe = Date.distantPast
        while Date() < end {
            if Date() >= nextProbe {
                _ = withUnsafePointer(to: &to) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        sendto(fd, probe, probe.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
                nextProbe = Date().addingTimeInterval(0.7)
            }
            var buf = [UInt8](repeating: 0, count: 1024)
            var from = sockaddr_in()
            var len = socklen_t(MemoryLayout<sockaddr_in>.size)
            let n = withUnsafeMutablePointer(to: &from) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    recvfrom(fd, &buf, buf.count, 0, $0, &len)
                }
            }
            if n <= 0 { continue }
            let ip = String(cString: inet_ntoa(from.sin_addr))
            guard let obj = (try? JSONSerialization.jsonObject(with: Data(buf[0..<n]))) as? [String: Any],
                  obj["t"] as? String == "here" else { continue }
            let port = (obj["port"] as? NSNumber)?.uint16Value ?? FBQR.relayPort
            guard let p = NWEndpoint.Port(rawValue: port) else { continue }
            found[ip] = LanTarget(name: (obj["name"] as? String) ?? ip,
                                  endpoint: .hostPort(host: NWEndpoint.Host(ip), port: p))
        }
        return Array(found.values)
    }
}

final class LanLink: MicLink {
    private let fixedTarget: LanTarget?
    private var conn: NWConnection?
    private var buffer = Data()
    private var pingTimer: Timer?
    private var stopped = false
    private let queue = DispatchQueue(label: "friendbot.mac.lan")

    init(target: LanTarget?) {
        fixedTarget = target
        super.init()
        DispatchQueue.main.async { self.next() }
    }

    private func next() {
        guard !stopped else { return }
        if let t = fixedTarget { connect(t); return }
        setState(.searching)
        LanDiscovery.find { [weak self] list in
            guard let self = self, !self.stopped else { return }
            if let first = list.first {
                self.connect(first)
            } else {
                DispatchQueue.main.asyncAfter(deadline: .now() + 1) { self.next() }
            }
        }
    }

    private func connect(_ t: LanTarget) {
        hostName = t.name
        setState(.connecting, "connecting to \(t.name)")
        let c = NWConnection(to: t.endpoint, using: .tcp)
        conn = c
        buffer = Data()
        c.stateUpdateHandler = { [weak self] st in
            DispatchQueue.main.async {
                guard let self = self, self.conn === c else { return }
                switch st {
                case .ready:
                    self.hello()
                    self.pingTimer?.invalidate()
                    self.pingTimer = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in
                        self?.sendLine("{\"t\":\"ping\"}")
                    }
                case .failed, .waiting:
                    self.ended(t, reachable: false)
                case .cancelled:
                    self.ended(t, reachable: true)
                default:
                    break
                }
            }
        }
        c.start(queue: queue)
        receive(c, t)
    }

    private func receive(_ c: NWConnection, _ t: LanTarget) {
        c.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] data, _, complete, error in
            guard let self = self else { return }
            if let data = data, !data.isEmpty {
                self.buffer.append(data)
                while let nl = self.buffer.firstIndex(of: UInt8(ascii: "\n")) {
                    let line = String(decoding: self.buffer[self.buffer.startIndex..<nl], as: UTF8.self)
                    self.buffer.removeSubrange(self.buffer.startIndex...nl)
                    DispatchQueue.main.async { self.robotLine(line) }
                }
            }
            if complete || error != nil {
                DispatchQueue.main.async { if self.conn === c { self.ended(t, reachable: true) } }
            } else {
                self.receive(c, t)
            }
        }
    }

    private func ended(_ t: LanTarget, reachable: Bool) {
        guard let c = conn else { return }
        conn = nil
        c.cancel()
        pingTimer?.invalidate()
        pingTimer = nil
        guard !stopped else { return }
        setState(.offline, reachable ? "\(t.name) left — reconnecting" : "can't reach \(t.name) — same WiFi?")
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in self?.next() }
    }

    override func sendLine(_ line: String) {
        conn?.send(content: Data((line + "\n").utf8), completion: .contentProcessed { _ in })
    }

    override func stop() {
        stopped = true
        pingTimer?.invalidate()
        conn?.cancel()
        conn = nil
    }
}

// MARK: - Internet Mic (any WiFi or 4G/5G)

/// A tiny MQTT 3.1.1 client over secure WebSocket (CONNECT, SUBSCRIBE, PUBLISH QoS 0, pings).
final class MqttWs {
    let url: URL
    private let clientId: String
    private let topics: [String]
    var onReady: (() -> Void)?
    var onMessage: ((Data) -> Void)?
    var onLost: (() -> Void)?
    private let queue = DispatchQueue(label: "friendbot.mac.mqtt")
    private var session: URLSession?
    private var task: URLSessionWebSocketTask?
    private var inbox = Data()
    private var pinger: DispatchSourceTimer?
    private(set) var isReady = false
    private var ended = false

    init(url: URL, clientId: String, topics: [String]) {
        self.url = url
        self.clientId = clientId
        self.topics = topics
    }

    func connect() {
        let s = URLSession(configuration: .default)
        session = s
        let t = s.webSocketTask(with: url, protocols: ["mqtt"])
        task = t
        t.resume()
        var vh = Self.str("MQTT")
        vh.append(contentsOf: [4, 0x02, 0, 60])
        vh.append(Self.str(clientId))
        send(Self.packet(0x10, vh))
        receive()
    }

    func publish(_ topic: String, _ payload: Data) {
        guard isReady, !ended else { return }
        var body = Self.str(topic)
        body.append(payload)
        send(Self.packet(0x30, body))
    }

    func close() {
        queue.async {
            guard !self.ended else { return }
            self.ended = true
            self.isReady = false
            self.pinger?.cancel()
            self.send(Data([0xE0, 0x00]))
            self.task?.cancel(with: .normalClosure, reason: nil)
            self.session?.finishTasksAndInvalidate()
        }
    }

    private func send(_ d: Data) { task?.send(.data(d)) { _ in } }

    private func receive() {
        task?.receive { [weak self] result in
            guard let self = self else { return }
            self.queue.async {
                guard !self.ended else { return }
                switch result {
                case .failure:
                    self.lost()
                case .success(let msg):
                    if case .data(let d) = msg {
                        self.inbox.append(d)
                        self.drain()
                    }
                    self.receive()
                }
            }
        }
    }

    private func drain() {
        let all = [UInt8](inbox)
        var pos = 0
        while pos < all.count {
            let start = pos
            let header = Int(all[pos]); pos += 1
            var length = 0, mult = 1, complete = false
            while pos < all.count {
                let b = Int(all[pos]); pos += 1
                length += (b & 0x7F) * mult
                mult *= 128
                if b & 0x80 == 0 { complete = true; break }
            }
            if !complete || pos + length > all.count { pos = start; break }
            handle(header, Array(all[pos..<(pos + length)]))
            pos += length
        }
        inbox = Data(all[pos...])
    }

    private func handle(_ header: Int, _ body: [UInt8]) {
        switch header >> 4 {
        case 2:
            if body.count >= 2, body[1] == 0 {
                var sub = Data([0x00, 0x01])
                for t in topics { sub.append(Self.str(t)); sub.append(0) }
                send(Self.packet(0x82, sub))
            } else {
                lost()
            }
        case 9:
            isReady = true
            let timer = DispatchSource.makeTimerSource(queue: queue)
            timer.schedule(deadline: .now() + 30, repeating: 30)
            timer.setEventHandler { [weak self] in self?.send(Data([0xC0, 0x00])) }
            timer.resume()
            pinger = timer
            onReady?()
        case 3:
            guard body.count >= 2 else { return }
            let qos = (header >> 1) & 0x03
            let tl = Int(body[0]) << 8 | Int(body[1])
            let start = 2 + tl + (qos > 0 ? 2 : 0)
            guard start <= body.count else { return }
            onMessage?(Data(body[start...]))
        default:
            break
        }
    }

    private func lost() {
        guard !ended else { return }
        ended = true
        isReady = false
        pinger?.cancel()
        task?.cancel(with: .goingAway, reason: nil)
        session?.finishTasksAndInvalidate()
        onLost?()
    }

    private static func packet(_ h: UInt8, _ body: Data) -> Data {
        var out = Data([h])
        var len = body.count
        repeat {
            var d = UInt8(len % 128)
            len /= 128
            if len > 0 { d |= 0x80 }
            out.append(d)
        } while len > 0
        out.append(body)
        return out
    }

    private static func str(_ s: String) -> Data {
        let b = Data(s.utf8)
        var out = Data([UInt8(b.count >> 8), UInt8(b.count & 0xFF)])
        out.append(b)
        return out
    }
}

final class NetLink: MicLink {
    static let brokers = ["wss://broker.hivemq.com:8884/mqtt", "wss://broker.emqx.io:8084/mqtt"]

    private let info: FBQR.Net
    private let myId = FBQR.randomId(12)
    private var mqtt: MqttWs?
    private var index = 0
    private var reached = false
    private var started = Date()
    private var generation = 0
    private var lastFromRobot = Date.distantPast
    private var timer: Timer?
    private var stopped = false

    init(info: FBQR.Net) {
        self.info = info
        super.init()
        hostName = info.name
        DispatchQueue.main.async {
            self.connectBroker()
            self.timer = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in self?.tick() }
        }
    }

    private var up: String { "friendbot/v1/\(info.room)/up" }
    private var down: String { "friendbot/v1/\(info.room)/down" }

    private func connectBroker() {
        guard !stopped, let url = URL(string: Self.brokers[index % Self.brokers.count]) else { return }
        generation += 1
        let gen = generation
        mqtt?.close()
        reached = false
        started = Date()
        setState(.connecting, "reaching the internet relay")
        let m = MqttWs(url: url, clientId: "fbm-" + myId, topics: [down])
        let key = SymmetricKey(data: info.key)
        m.onReady = { [weak self] in
            DispatchQueue.main.async {
                guard let self = self, gen == self.generation else { return }
                self.reached = true
                self.setState(.connecting, "waiting for \(self.info.name) — open its Voice Control")
                self.hello()
            }
        }
        m.onMessage = { [weak self] payload in
            guard let box = try? AES.GCM.SealedBox(combined: payload),
                  let plain = try? AES.GCM.open(box, using: key),
                  let env = (try? JSONSerialization.jsonObject(with: plain)) as? [String: Any] else { return }
            DispatchQueue.main.async {
                guard let self = self, gen == self.generation, env["t"] as? String == self.myId,
                      let line = env["l"] as? String else { return }
                self.lastFromRobot = Date()
                if self.state == .offline, self.connected { self.setState(.connected) }
                self.robotLine(line)
            }
        }
        m.onLost = { [weak self] in
            DispatchQueue.main.async {
                guard let self = self, gen == self.generation, !self.stopped else { return }
                if !self.reached { self.index += 1 }
                self.setState(.connecting, "reconnecting to the internet relay")
                DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in
                    guard let self = self, gen == self.generation else { return }
                    self.connectBroker()
                }
            }
        }
        mqtt = m
        m.connect()
    }

    private func tick() {
        guard !stopped else { return }
        if !(mqtt?.isReady ?? false) {
            if Date().timeIntervalSince(started) > 12 { index += 1; connectBroker() }
            return
        }
        if !connected {
            hello()
        } else {
            sendLine("{\"t\":\"ping\"}")
            if Date().timeIntervalSince(lastFromRobot) > 12 {
                setState(.offline, "\(info.name) is offline — retrying")
                hello()
            }
        }
    }

    override func sendLine(_ line: String) {
        guard let m = mqtt, m.isReady else { return }
        let env = FBQR.json(["f": myId, "l": line])
        if let sealed = try? AES.GCM.seal(Data(env.utf8), using: SymmetricKey(data: info.key)).combined {
            m.publish(up, sealed)
        }
    }

    override func stop() {
        stopped = true
        timer?.invalidate()
        generation += 1
        mqtt?.close()
        mqtt = nil
    }
}
