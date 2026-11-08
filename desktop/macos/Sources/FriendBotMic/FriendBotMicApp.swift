// FriendBot Mic for macOS: the Remote Mic (same WiFi) and Internet Mic (any WiFi or 4G/5G)
// pages in the same simple robotic style as the phone apps.

import AppKit
import SwiftUI

@main
struct FriendBotMicApp: App {
    /// The same program is packed twice (build.sh): "FriendBot Mic" (the simple pages) and
    /// "FriendBot Mic Pro" (bundle id ending in ".pro", or run with --pro).
    static let isPro = Bundle.main.bundleIdentifier?.hasSuffix(".pro") == true
        || CommandLine.arguments.contains("--pro")

    var body: some Scene {
        WindowGroup(Self.isPro ? "FriendBot Mic Pro" : "FriendBot Mic") {
            if Self.isPro {
                ProRoot()
            } else {
                SimpleRoot()
            }
        }
        .windowResizability(.contentSize)
    }
}

/// The simple Remote Mic / Internet Mic window (unchanged).
struct SimpleRoot: View {
    @StateObject private var model = Model()

    var body: some View {
        ContentView().environmentObject(model)
            .frame(minWidth: 390, idealWidth: 390, minHeight: 760, idealHeight: 760)
    }
}

/// The Wi‑Fi Mic Pro / Internet Mic Pro window.
struct ProRoot: View {
    @StateObject private var model = ProModel()

    var body: some View {
        ProView().environmentObject(model)
            .frame(minWidth: 420, idealWidth: 420, minHeight: 900, idealHeight: 900)
    }
}

enum Palette {
    static let ink = Color(hex: 0x05070B)
    static let panel = Color(hex: 0x0B141A)
    static let cyan = Color(hex: 0x7FE7FF)
    static let muted = Color(hex: 0x8FA9B3)
    static let green = Color(hex: 0x3DDC97)
    static let amber = Color(hex: 0xFFB547)
    static let red = Color(hex: 0xFF6B6B)
    static let text = Color(hex: 0xE6F9FF)
    static let kicker = Color(hex: 0x6FA8B8)
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255)
    }
}

// MARK: - Model

final class Model: ObservableObject {
    enum Page: String { case lan, net }

    @Published var page: Page { didSet { pageChanged(from: oldValue) } }
    @Published var lanEntry: String
    @Published var netEntry: String
    @Published var level: Float = 0
    @Published var micOn = false
    @Published var scanning = false
    @Published var note: String?
    @Published private var refresh = 0

    private(set) var link: MicLink?
    private let mic = Mic()
    private let defaults = UserDefaults.standard

    init() {
        page = Page(rawValue: defaults.string(forKey: "page") ?? "") ?? .lan
        lanEntry = defaults.string(forKey: "lan") ?? ""
        netEntry = defaults.string(forKey: "net") ?? ""
        mic.onFrame = { [weak self] opus, rms in
            self?.link?.sendFrame(opus, rms: rms)
            DispatchQueue.main.async { self?.level = min(1, rms * 8) }
        }
        DispatchQueue.main.async { self.autoConnect() }
    }

    var entry: String {
        get { page == .lan ? lanEntry : netEntry }
        set { if page == .lan { lanEntry = newValue } else { netEntry = newValue } }
    }

    private func autoConnect() {
        if page == .lan || !netEntry.isEmpty { connect() }
    }

    private func pageChanged(from old: Page) {
        guard old != page else { return }
        defaults.set(page.rawValue, forKey: "page")
        disconnect()
        autoConnect()
    }

    // Connect with whatever is in the box: empty = search the WiFi (Remote Mic page).
    func connect() {
        disconnect()
        note = nil
        let text = entry.trimmingCharacters(in: .whitespacesAndNewlines)
        switch page {
        case .lan:
            var target: LanTarget?
            if !text.isEmpty {
                guard let t = FBQR.parseLan(text) else { note = "That is not an IP like 192.168.1.20"; return }
                target = t
            }
            defaults.set(text, forKey: "lan")
            start(LanLink(target: target))
        case .net:
            guard let info = FBQR.parseNet(text) else {
                note = text.isEmpty ? nil : "That is not a FriendBot Internet QR"
                refresh += 1
                return
            }
            defaults.set(text, forKey: "net")
            start(NetLink(info: info))
        }
    }

    private func start(_ l: MicLink) {
        link = l
        l.onChange = { [weak self] in
            // The mic stays on through a short drop; it re-opens when the link comes back.
            self?.refresh += 1
        }
        refresh += 1
    }

    func disconnect() {
        setMic(false)
        link?.stop()
        link = nil
        refresh += 1
    }

    /// A scanned or pasted QR: the right page opens for it.
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
            note = "This QR is not from FriendBot Voice Control"
        }
    }

    func toggleMic() {
        guard let l = link, l.connected else {
            note = page == .lan ? "Link a robot phone first" : "Scan the robot phone's Internet QR first"
            return
        }
        if micOn { setMic(false); return }
        Mic.askPermission { [weak self] ok in
            guard let self = self else { return }
            guard ok else { self.note = "Microphone blocked — allow it in System Settings › Privacy"; return }
            do {
                try self.mic.start()
                self.micOn = true
                self.link?.setMic(true)
            } catch {
                self.note = error.localizedDescription
            }
        }
    }

    private func setMic(_ on: Bool) {
        if !on {
            mic.stop()
            if micOn { link?.setMic(false) }
            micOn = false
            level = 0
        }
    }

    // MARK: words and colours for the page

    var kicker: String { page == .lan ? "FRIENDBOT // LAN MIC" : "FRIENDBOT // NET MIC" }
    var title: String { page == .lan ? "Remote Mic" : "Internet Mic" }

    var linkColor: Color {
        guard let l = link else { return Palette.muted }
        switch l.state {
        case .connected: return Palette.green
        case .searching, .connecting: return Palette.amber
        case .offline, .error: return Palette.red
        case .idle: return Palette.muted
        }
    }

    var linkText: String {
        _ = refresh
        guard let l = link else { return page == .net ? "NO CODE · SCAN QR" : "NOT LINKED" }
        let name = l.hostName.uppercased()
        switch l.state {
        case .idle: return "NOT LINKED"
        case .searching: return "SEARCHING WI‑FI…"
        case .connecting: return page == .net ? "RELAY · WAITING \(name)" : "LINKING \(name)…"
        case .connected: return "LINKED · \(name)"
        case .offline: return "OFFLINE · \(name)"
        case .error: return "ERROR"
        }
    }

    var stateText: String {
        _ = refresh
        guard let l = link, l.connected else { return "STANDBY" }
        if micOn {
            if let other = l.floorOther { return "\(other.uppercased()) HAS THE MIC" }
            return l.micAckLive ? "TRANSMITTING" : "OPENING CHANNEL…"
        }
        return "TAP TO TALK"
    }

    var hint: String {
        _ = refresh
        if let n = note { return n }
        if let d = link?.detail, !d.isEmpty { return d }
        if page == .lan {
            return "Same Wi‑Fi as the robot phone. Leave the box empty to find it, or type its IP."
        }
        return "Works on any Wi‑Fi or 4G/5G. Scan the Internet QR on the robot phone's Voice Control."
    }

    var networkTag: String { page == .lan ? "SAME NETWORK" : "AES‑256 · ANY NETWORK" }
    var isLinked: Bool { _ = refresh; return link?.connected ?? false }
    var isLive: Bool { _ = refresh; return micOn && (link?.micAckLive ?? false) }
}

// MARK: - Views

struct ContentView: View {
    @EnvironmentObject var m: Model

    var body: some View {
        VStack(spacing: 0) {
            tabs.padding(.top, 14)
            VStack(alignment: .leading, spacing: 6) {
                Text(m.kicker).font(.system(size: 11, weight: .bold, design: .monospaced))
                    .tracking(2).foregroundColor(Palette.kicker)
                Text(m.title).font(.system(size: 30, weight: .bold, design: .monospaced))
                    .foregroundColor(Palette.text)
                pill.padding(.top, 6)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 24).padding(.top, 18)

            Spacer(minLength: 10)
            MicOrb(live: m.isLive, armed: m.micOn, linked: m.isLinked) { m.toggleMic() }
            LevelBars(level: m.level, active: m.isLive).frame(width: 120, height: 36).padding(.top, 18)
            Text(m.stateText).font(.system(size: 14, weight: .bold, design: .monospaced))
                .tracking(1.5).foregroundColor(m.isLive ? Palette.green : Palette.text).padding(.top, 10)
            Text(m.hint).font(.system(size: 12, design: .monospaced)).foregroundColor(Palette.muted)
                .multilineTextAlignment(.center).frame(maxWidth: 320).padding(.top, 6)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 10)

            bottom
        }
        .background(Palette.ink)
        .preferredColorScheme(.dark)
        .sheet(isPresented: $m.scanning) { QRScanSheet { m.useQr($0) } }
    }

    private var tabs: some View {
        HStack(spacing: 0) {
            tab("REMOTE MIC", .lan)
            tab("INTERNET MIC", .net)
        }
        .padding(3)
        .background(RoundedRectangle(cornerRadius: 10).fill(Palette.panel))
        .padding(.horizontal, 24)
    }

    private func tab(_ title: String, _ p: Model.Page) -> some View {
        Button { m.page = p } label: {
            Text(title).font(.system(size: 11, weight: .bold, design: .monospaced)).tracking(1.2)
                .foregroundColor(m.page == p ? Palette.ink : Palette.muted)
                .frame(maxWidth: .infinity).padding(.vertical, 8)
                .background(RoundedRectangle(cornerRadius: 8).fill(m.page == p ? Palette.cyan : Color.clear))
        }
        .buttonStyle(.plain)
    }

    private var pill: some View {
        HStack(spacing: 8) {
            Circle().fill(m.linkColor).frame(width: 8, height: 8)
                .shadow(color: m.linkColor.opacity(0.8), radius: 4)
            Text(m.linkText).font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundColor(m.linkColor).lineLimit(1)
        }
        .padding(.horizontal, 12).padding(.vertical, 6)
        .background(Capsule().fill(Palette.panel))
        .overlay(Capsule().stroke(m.linkColor.opacity(0.45), lineWidth: 1))
    }

    private var bottom: some View {
        VStack(spacing: 10) {
            HStack {
                Text(m.page == .lan ? "ROBOT IP (EMPTY = AUTO)" : "INTERNET QR TEXT")
                    .font(.system(size: 10, weight: .bold, design: .monospaced)).foregroundColor(Palette.kicker)
                Spacer()
                Text(m.networkTag).font(.system(size: 10, weight: .bold, design: .monospaced))
                    .foregroundColor(Palette.cyan)
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .overlay(Capsule().stroke(Palette.cyan.opacity(0.5), lineWidth: 1))
            }
            TextField(m.page == .lan ? "192.168.1.20" : "friendbot://net?room=…", text: $m.entry)
                .textFieldStyle(.plain)
                .font(.system(size: 13, design: .monospaced)).foregroundColor(Palette.text)
                .padding(10)
                .background(RoundedRectangle(cornerRadius: 10).fill(Palette.panel))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Palette.cyan.opacity(0.25), lineWidth: 1))
                .onSubmit { m.connect() }
            HStack(spacing: 10) {
                RoboButton(title: "CONNECT", filled: false) { m.connect() }
                RoboButton(title: "SCAN QR", filled: m.page == .net && !m.isLinked, icon: "qrcode") {
                    m.scanning = true
                }
            }
        }
        .padding(.horizontal, 24).padding(.bottom, 24)
    }
}

struct RoboButton: View {
    let title: String
    let filled: Bool
    var icon: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let icon = icon { Image(systemName: icon) }
                Text(title).font(.system(size: 13, weight: .bold, design: .monospaced)).tracking(1.5)
            }
            .foregroundColor(filled ? Palette.ink : Palette.cyan)
            .frame(maxWidth: .infinity).padding(.vertical, 12)
            .background(RoundedRectangle(cornerRadius: 12).fill(filled ? Palette.cyan : Color.clear))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Palette.cyan, lineWidth: 1.5))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// The big round mic button: dashed ring when idle, pulsing green rings while it transmits.
struct MicOrb: View {
    let live: Bool
    let armed: Bool
    let linked: Bool
    let action: () -> Void

    var body: some View {
        let color = live ? Palette.green : (armed ? Palette.amber : Palette.cyan)
        ZStack {
            if live {
                TimelineView(.animation) { tl in
                    let t = tl.date.timeIntervalSinceReferenceDate
                    ZStack {
                        ForEach(0..<2, id: \.self) { i in
                            let p = (t / 1.6 + Double(i) * 0.5).truncatingRemainder(dividingBy: 1)
                            Circle().stroke(Palette.green.opacity(0.6 * (1 - p)), lineWidth: 2)
                                .frame(width: 160 + 80 * p, height: 160 + 80 * p)
                        }
                    }
                }
            } else {
                Circle().stroke(color.opacity(linked ? 0.5 : 0.25),
                                style: StrokeStyle(lineWidth: 1.5, dash: [4, 6]))
                    .frame(width: 196, height: 196)
            }
            Button(action: action) {
                ZStack {
                    Circle().fill(live ? Palette.green.opacity(0.18) : Palette.panel)
                    Circle().stroke(color, lineWidth: 2.5)
                    Image(systemName: live ? "mic.fill" : "mic")
                        .font(.system(size: 52, weight: .regular)).foregroundColor(color)
                }
                .frame(width: 160, height: 160)
                .shadow(color: color.opacity(live ? 0.6 : 0.25), radius: live ? 24 : 12)
                .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .keyboardShortcut(.space, modifiers: [])
        }
        .frame(width: 240, height: 240)
        .opacity(linked ? 1 : 0.55)
    }
}

/// Nine bars that follow the voice level.
struct LevelBars: View {
    let level: Float
    let active: Bool
    private let shape: [CGFloat] = [0.35, 0.55, 0.75, 0.9, 1, 0.9, 0.75, 0.55, 0.35]

    var body: some View {
        GeometryReader { g in
            HStack(alignment: .center, spacing: 5) {
                ForEach(0..<9, id: \.self) { i in
                    let h = active ? max(0.12, CGFloat(level) * shape[i]) : 0.12
                    RoundedRectangle(cornerRadius: 2)
                        .fill(active ? Palette.green : Palette.muted.opacity(0.35))
                        .frame(height: g.size.height * h)
                        .frame(maxHeight: .infinity)
                }
            }
            .animation(.easeOut(duration: 0.08), value: level)
        }
    }
}
