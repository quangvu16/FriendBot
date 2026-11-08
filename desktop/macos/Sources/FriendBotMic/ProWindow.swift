// FriendBot Mic Pro for macOS: the Wi‑Fi Mic Pro and Internet Mic Pro pages.
// Same links and microphone as the simple app, plus: STOP (and auto-stop), what Xiaozhi heard
// and answered, the robot camera, push-to-talk, a drive pad, link delay and battery.
// Built as its own app ("FriendBot Mic Pro.app", see build.sh) from the same code.

import AppKit
import SwiftUI

final class ProModel: ObservableObject {
    enum Page: String { case lan, net }

    static let driveMs = 600
    static let driveRepeat: TimeInterval = 0.25
    static let pad: [String: (Float, Float)] = [
        "up": (0.6, 0.6), "down": (-0.6, -0.6), "left": (-0.45, 0.45), "right": (0.45, -0.45),
    ]

    @Published var page: Page { didSet { if oldValue != page { pageChanged() } } }
    @Published var lanEntry: String
    @Published var netEntry: String
    @Published var level: Float = 0
    @Published var talking = false
    @Published var driving: String?
    @Published var camOn = false
    @Published var camImage: NSImage?
    @Published var heard = ""
    @Published var said = ""
    @Published var rttMs: Int?
    @Published var battery = ""
    @Published var batteryColor = Palette.muted
    @Published var note: String?
    @Published var scanning = false
    @Published private var refresh = 0

    private(set) var link: MicLink?
    private let mic = Mic()
    private let defaults = UserDefaults.standard
    private var wasConnected = false
    private var pingId = 0
    private var pingSentAt = Date.distantPast
    private var lastPongAt = Date.distantPast
    private var camAt = Date.distantPast
    private var heartbeat: Timer?
    private var driveTimer: Timer?
    private var keyMonitor: Any?
    private var noteClear: DispatchWorkItem?

    init() {
        page = Page(rawValue: defaults.string(forKey: "pro_page") ?? "") ?? .lan
        lanEntry = defaults.string(forKey: "lan") ?? ""
        netEntry = defaults.string(forKey: "net") ?? ""
        mic.onFrame = { [weak self] opus, rms in
            self?.link?.sendFrame(opus, rms: rms)
            DispatchQueue.main.async { self?.level = min(1, rms * 8) }
        }
        installKeys()
        DispatchQueue.main.async { self.autoConnect() }
    }

    var internet: Bool { page == .net }
    var entry: String {
        get { internet ? netEntry : lanEntry }
        set { if internet { netEntry = newValue } else { lanEntry = newValue } }
    }

    // MARK: link

    private func autoConnect() {
        if !internet || !netEntry.isEmpty { connect() }
    }

    private func pageChanged() {
        defaults.set(page.rawValue, forKey: "pro_page")
        disconnect()
        autoConnect()
    }

    func connect() {
        disconnect()
        note = nil
        let text = entry.trimmingCharacters(in: .whitespacesAndNewlines)
        if internet {
            guard let info = FBQR.parseNet(text) else {
                if !text.isEmpty { flash("That is not a FriendBot Internet QR") }
                return
            }
            defaults.set(text, forKey: "net")
            start(NetLink(info: info))
        } else {
            var target: LanTarget?
            if !text.isEmpty {
                guard let t = FBQR.parseLan(text) else { flash("That is not an IP like 192.168.1.20"); return }
                target = t
            }
            defaults.set(text, forKey: "lan")
            start(LanLink(target: target))
        }
    }

    private func start(_ l: MicLink) {
        link = l
        wasConnected = false
        l.onChange = { [weak self] in self?.linkChanged() }
        l.onExtra = { [weak self] m in self?.extra(m) }
        heartbeat?.invalidate()
        heartbeat = Timer.scheduledTimer(withTimeInterval: internet ? 1.0 : 0.5, repeats: true) {
            [weak self] _ in self?.beat()
        }
        refresh += 1
    }

    func disconnect() {
        setTalking(false)
        drive(nil)
        heartbeat?.invalidate()
        heartbeat = nil
        if let l = link, l.connected { l.sendLine("{\"t\":\"pro\",\"on\":false}") }
        link?.stop()
        link = nil
        rttMs = nil
        camImage = nil
        refresh += 1
    }

    private func linkChanged() {
        let connected = link?.connected ?? false
        if connected && !wasConnected {
            sendPro() // fresh link or reconnect: tell the robot phone what this page wants
            lastPongAt = Date()
        }
        if !connected { rttMs = nil }
        wasConnected = connected
        refresh += 1
    }

    private func sendPro() {
        link?.sendLine(FBQR.json(["t": "pro", "on": true, "watch": internet ? 3000 : 1500,
                                  "cam": camOn, "fps": internet ? 2 : 5]))
    }

    /// Fast pings: the robot phone's auto-stop heartbeat, and the link delay.
    private func beat() {
        guard let l = link, l.connected else { refresh += 1; return }
        pingId += 1
        pingSentAt = Date()
        l.sendLine("{\"t\":\"ping\",\"id\":\(pingId)}")
        refresh += 1
    }

    private func extra(_ m: [String: Any]) {
        switch m["t"] as? String ?? "" {
        case "pong":
            lastPongAt = Date()
            if (m["id"] as? NSNumber)?.intValue == pingId {
                rttMs = Int(Date().timeIntervalSince(pingSentAt) * 1000)
            }
        case "heard":
            heard = m["text"] as? String ?? ""
        case "said":
            said = m["text"] as? String ?? ""
        case "status":
            showBattery(m)
        case "cam":
            guard camOn, let b = m["b"] as? String, let data = Data(base64Encoded: b),
                  let img = NSImage(data: data) else { return }
            camImage = img
            camAt = Date()
        case "safety":
            flash("The robot stopped by itself: the link was lost while it moved", seconds: 5)
        default:
            break
        }
    }

    private func showBattery(_ m: [String: Any]) {
        if let rp = (m["rp"] as? NSNumber)?.intValue {
            battery = "BOT \(rp)%"
            batteryColor = rp < 20 ? Palette.red : rp < 40 ? Palette.amber : Palette.green
        } else if let bat = (m["bat"] as? NSNumber)?.intValue {
            let chg = (m["chg"] as? Bool) ?? false
            battery = "PH \(bat)%" + (chg ? "+" : "")
            batteryColor = chg ? Palette.green : bat < 20 ? Palette.red : bat < 40 ? Palette.amber : Palette.green
        }
    }

    func useQr(_ text: String?) {
        scanning = false
        guard let t = text?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty else { return }
        if FBQR.parseNet(t) != nil {
            netEntry = t
            if page != .net { page = .net } else { connect() }
        } else if FBQR.parseLan(t) != nil {
            lanEntry = t
            if page != .lan { page = .lan } else { connect() }
        } else {
            flash("This QR is not from FriendBot Voice Control")
        }
    }

    // MARK: push-to-talk

    func setTalking(_ on: Bool) {
        if !on {
            guard talking else { return }
            mic.stop()
            talking = false
            level = 0
            link?.setMic(false)
            return
        }
        guard !talking else { return }
        guard let l = link, l.connected else { flash(linkText); return }
        Mic.askPermission { [weak self] ok in
            guard let self = self else { return }
            guard ok else { self.flash("Microphone blocked — allow it in System Settings › Privacy"); return }
            do {
                try self.mic.start()
                self.talking = true
                self.link?.setMic(true)
            } catch {
                self.flash(error.localizedDescription)
            }
        }
    }

    // MARK: driving

    func drive(_ direction: String?) {
        driveTimer?.invalidate()
        driveTimer = nil
        let was = driving
        driving = direction
        guard let d = direction, let speeds = Self.pad[d] else {
            if was != nil { link?.sendLine("{\"t\":\"stop\"}") } // let go = stop
            return
        }
        guard let l = link, l.connected else {
            driving = nil
            flash(linkText)
            return
        }
        let send = { [weak l] in
            l?.sendLine(FBQR.json(["t": "move", "l": speeds.0, "r": speeds.1, "ms": Self.driveMs]))
        }
        send()
        driveTimer = Timer.scheduledTimer(withTimeInterval: Self.driveRepeat, repeats: true) { _ in send() }
    }

    func stopRobot() {
        drive(nil)
        guard let l = link, l.connected else { flash(linkText); return }
        l.sendLine("{\"t\":\"stop\"}")
        // Twice, a moment apart: one lost packet on 4G must not leave the robot driving.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { [weak l] in l?.sendLine("{\"t\":\"stop\"}") }
        flash("Stop sent", seconds: 1.5)
    }

    func toggleCamera() {
        camOn.toggle()
        if !camOn { camImage = nil }
        camAt = Date()
        if link?.connected == true { sendPro() }
    }

    // MARK: keyboard: arrows drive, Space talks, Esc stops (key repeats ignored)

    private func installKeys() {
        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: [.keyDown, .keyUp]) { [weak self] e in
            guard let self = self else { return e }
            if NSApp.keyWindow?.firstResponder is NSTextView { return e } // typing in the box
            let down = e.type == .keyDown
            let names: [UInt16: String] = [126: "up", 125: "down", 123: "left", 124: "right"]
            if let dir = names[e.keyCode] {
                if down, !e.isARepeat { self.drive(dir) }
                if !down, self.driving == dir { self.drive(nil) }
                return nil
            }
            if e.keyCode == 49 { // space
                if down, !e.isARepeat { self.setTalking(true) }
                if !down { self.setTalking(false) }
                return nil
            }
            if e.keyCode == 53, down { // esc
                self.stopRobot()
                return nil
            }
            return e
        }
    }

    private func flash(_ text: String, seconds: Double = 3) {
        note = text
        noteClear?.cancel()
        let w = DispatchWorkItem { [weak self] in self?.note = nil }
        noteClear = w
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: w)
    }

    // MARK: words and colours

    var linkOk: Bool { (link?.connected ?? false) && Date().timeIntervalSince(lastPongAt) < 3 }

    var linkText: String {
        _ = refresh
        guard let l = link else { return internet ? "SCAN THE INTERNET QR" : "NOT LINKED" }
        let name = l.hostName.uppercased()
        switch l.state {
        case .connected: return "LINKED · \(name)"
        case .searching: return "SEARCHING WI‑FI…"
        case .connecting: return internet ? "RELAY · WAITING \(name)" : "LINKING \(name)…"
        case .offline: return "OFFLINE · \(name)"
        case .error: return l.detail.uppercased()
        case .idle: return "NOT LINKED"
        }
    }

    var linkColor: Color {
        _ = refresh
        guard let l = link else { return Palette.muted }
        switch l.state {
        case .connected: return Palette.green
        case .searching, .connecting: return Palette.amber
        case .offline, .error: return Palette.red
        case .idle: return Palette.muted
        }
    }

    var pingText: String {
        _ = refresh
        guard link?.connected == true else { return "— ms" }
        if !linkOk { return "LOST" }
        return rttMs.map { "\($0) ms" } ?? "— ms"
    }

    var pingColor: Color {
        _ = refresh
        guard link?.connected == true else { return Palette.muted }
        guard linkOk, let ms = rttMs else { return Palette.red }
        return ms < 150 ? Palette.green : ms < 400 ? Palette.amber : Palette.red
    }

    var camFresh: Bool { _ = refresh; return camOn && camImage != nil && Date().timeIntervalSince(camAt) < 4 }

    var talkText: String {
        _ = refresh
        guard talking, let l = link else { return "HOLD TO TALK" }
        if let other = l.floorOther { return "\(other.uppercased()) IS TALKING" }
        return l.micAckLive ? "TALKING_" : "OPENING…"
    }
}

// MARK: - Views

struct ProView: View {
    @EnvironmentObject var m: ProModel

    var body: some View {
        VStack(spacing: 10) {
            HStack(spacing: 0) {
                tab("WI‑FI PRO", .lan)
                tab("NET PRO", .net)
            }
            .padding(3)
            .background(RoundedRectangle(cornerRadius: 10).fill(Palette.panel))

            VStack(alignment: .leading, spacing: 4) {
                Text(m.internet ? "FRIENDBOT // NET PRO" : "FRIENDBOT // LAN PRO")
                    .font(.system(size: 11, weight: .bold, design: .monospaced)).tracking(2)
                    .foregroundColor(Palette.kicker)
                Text(m.internet ? "Internet Mic Pro" : "Wi‑Fi Mic Pro")
                    .font(.system(size: 26, weight: .bold, design: .monospaced)).foregroundColor(Palette.text)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: 6) {
                pill
                chip(m.pingText, m.pingColor)
                chip(m.battery.isEmpty ? "BAT —" : m.battery, m.battery.isEmpty ? Palette.muted : m.batteryColor)
            }

            camera
            words
            HStack(spacing: 10) {
                DrivePad()
                Spacer(minLength: 0)
                VStack(spacing: 4) {
                    MicOrb(live: m.talking, armed: m.talking, linked: m.link?.connected ?? false) {}
                        .scaleEffect(0.62)
                        .frame(width: 150, height: 150)
                        .highPriorityGesture(DragGesture(minimumDistance: 0)
                            .onChanged { _ in if !m.talking { m.setTalking(true) } }
                            .onEnded { _ in m.setTalking(false) })
                    LevelBars(level: m.level, active: m.talking).frame(width: 80, height: 18)
                    Text(m.talkText).font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundColor(m.talking ? Palette.cyan : Palette.text)
                }
                .frame(maxWidth: .infinity)
            }

            Button(action: m.stopRobot) {
                Text("■  STOP   (Esc)").font(.system(size: 17, weight: .bold, design: .monospaced))
                    .tracking(2).foregroundColor(.white)
                    .frame(maxWidth: .infinity).padding(.vertical, 14)
                    .background(RoundedRectangle(cornerRadius: 14).fill(Palette.red))
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Text("HOLD SPACE = TALK · ARROWS = DRIVE · ESC = STOP")
                .font(.system(size: 9, design: .monospaced)).foregroundColor(Palette.muted)

            TextField(m.internet ? "friendbot://net?room=…" : "Robot phone IP (empty = find it)", text: $m.entry)
                .textFieldStyle(.plain).font(.system(size: 12, design: .monospaced))
                .foregroundColor(Palette.text).padding(8)
                .background(RoundedRectangle(cornerRadius: 8).fill(Palette.panel))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Palette.cyan.opacity(0.25), lineWidth: 1))
                .onSubmit { m.connect() }
            HStack(spacing: 10) {
                RoboButton(title: "CONNECT", filled: false) { m.connect() }
                RoboButton(title: "SCAN QR", filled: !(m.link?.connected ?? false), icon: "qrcode") {
                    m.scanning = true
                }
            }
        }
        .padding(.horizontal, 22).padding(.vertical, 16)
        .background(Palette.ink)
        .preferredColorScheme(.dark)
        .sheet(isPresented: $m.scanning) { QRScanSheet { m.useQr($0) } }
    }

    private func tab(_ title: String, _ p: ProModel.Page) -> some View {
        Button { m.page = p } label: {
            Text(title).font(.system(size: 11, weight: .bold, design: .monospaced)).tracking(1.2)
                .foregroundColor(m.page == p ? Palette.ink : Palette.muted)
                .frame(maxWidth: .infinity).padding(.vertical, 7)
                .background(RoundedRectangle(cornerRadius: 8).fill(m.page == p ? Palette.cyan : Color.clear))
        }
        .buttonStyle(.plain)
    }

    private var pill: some View {
        let color = m.note == nil ? m.linkColor : Palette.red
        return HStack(spacing: 6) {
            Circle().fill(color).frame(width: 8, height: 8).shadow(color: color.opacity(0.8), radius: 4)
            Text(m.note ?? m.linkText).font(.system(size: 10, weight: .bold, design: .monospaced))
                .foregroundColor(color).lineLimit(1).truncationMode(.tail)
        }
        .padding(.horizontal, 10).padding(.vertical, 7)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Capsule().fill(Palette.panel))
        .overlay(Capsule().stroke(color.opacity(0.45), lineWidth: 1))
    }

    private func chip(_ text: String, _ color: Color) -> some View {
        Text(text).font(.system(size: 10, weight: .bold, design: .monospaced)).foregroundColor(color)
            .lineLimit(1).fixedSize()
            .padding(.horizontal, 8).padding(.vertical, 7)
            .background(Capsule().fill(Palette.panel))
            .overlay(Capsule().stroke(Palette.cyan.opacity(0.2), lineWidth: 1))
    }

    private var camera: some View {
        ZStack(alignment: .topLeading) {
            RoundedRectangle(cornerRadius: 14).fill(Palette.panel)
            if m.camFresh, let img = m.camImage {
                Image(nsImage: img).resizable().aspectRatio(contentMode: .fill)
                    .frame(maxWidth: .infinity, maxHeight: .infinity).clipped()
            } else {
                Text(m.camOn ? "WAITING FOR PICTURES…\nKeep Voice Control open on the robot phone"
                             : "CAMERA OFF · CLICK TO VIEW")
                    .font(.system(size: 11, design: .monospaced)).foregroundColor(Palette.kicker)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            Text(m.camOn ? "● LIVE \(m.internet ? 2 : 5) FPS" : "CAM OFF")
                .font(.system(size: 9, weight: .bold, design: .monospaced))
                .foregroundColor(m.camOn ? Palette.green : Palette.muted)
                .padding(.horizontal, 7).padding(.vertical, 3)
                .background(Capsule().fill(Palette.panel.opacity(0.85)))
                .padding(10)
        }
        .frame(height: 200)
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Palette.cyan.opacity(0.27), lineWidth: 1))
        .contentShape(Rectangle())
        .onTapGesture { m.toggleCamera() }
    }

    private var words: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("HEARD › " + (m.heard.isEmpty ? "—" : m.heard)).foregroundColor(Palette.text)
            Text("ROBOT › " + (m.said.isEmpty ? "—" : m.said)).foregroundColor(Palette.cyan)
        }
        .font(.system(size: 11, design: .monospaced)).lineLimit(2)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 12).fill(Palette.panel))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Palette.cyan.opacity(0.2), lineWidth: 1))
    }
}

/// Four arrow keys: hold to drive, let go to stop.
struct DrivePad: View {
    @EnvironmentObject var m: ProModel

    var body: some View {
        VStack(spacing: 2) {
            key("up", "arrow.up")
            HStack(spacing: 2) {
                key("left", "arrow.left")
                Text("DRIVE").font(.system(size: 9, design: .monospaced)).foregroundColor(Palette.kicker)
                    .frame(width: 52, height: 52)
                key("right", "arrow.right")
            }
            key("down", "arrow.down")
        }
    }

    private func key(_ dir: String, _ icon: String) -> some View {
        let held = m.driving == dir
        return Image(systemName: icon).font(.system(size: 20, weight: .bold))
            .foregroundColor(held ? Palette.ink : Palette.cyan)
            .frame(width: 52, height: 52)
            .background(RoundedRectangle(cornerRadius: 12).fill(held ? Palette.cyan : Palette.panel))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Palette.cyan.opacity(0.6), lineWidth: 1.5))
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { _ in if m.driving != dir { m.drive(dir) } }
                .onEnded { _ in if m.driving == dir { m.drive(nil) } })
    }
}
