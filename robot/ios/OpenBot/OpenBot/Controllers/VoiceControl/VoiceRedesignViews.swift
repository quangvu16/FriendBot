//
//  VoiceRedesignViews.swift
//  OpenBot
//
//  The two custom views of the redesigned Voice Control screen (Lily-style listening):
//
//  - VoiceFaceView: the emotional face hero. A large emoji face with a pulsing accent
//    ring driven by the mic RMS while Listening, a gentle bob while Speaking, and a
//    cross-fade + bounce (≈250 ms) whenever the emoji changes. The emoji is chosen by
//    the controller (server emotion > content heuristics > state default).
//
//  - VoiceWaveformView: 14 rounded level bars between the face and the transcript,
//    driven by the mic RMS while the uplink streams (playback level × 0.6 while the
//    assistant speaks). Per-bar smoothing: fast attack (0.55) / slow release (0.18),
//    a small flat idle floor, flat when idle or disconnected.
//
//  Mirrors WaveformView / the face animations in VoiceControlFragment.java on Android.
//

import UIKit

/// Emotional face hero: emoji label + pulsing CAShapeLayer ring.
final class VoiceFaceView: UIView {

    /// The accent shared with the mic button and the waveform bars (Android 0xFF0A66D6).
    /// Deep system blue — Apple-look, and dark enough for white text on accent bubbles.
    static let accent = UIColor(red: 0.039, green: 0.4, blue: 0.839, alpha: 1)

    private let emojiLabel = UILabel()
    private let ringLayer = CAShapeLayer()
    /// The emoji currently shown (swap animations only run on real changes).
    private var shownEmoji: String?
    /// True while a cross-fade + bounce swap is on (the bob defers to it).
    private var swapRunning = false
    /// Whether the state wants the speaking bob (deferred while swapping).
    private var bobDesired = false
    private var bobRunning = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false

        ringLayer.fillColor = UIColor.clear.cgColor
        ringLayer.strokeColor = Self.accent.cgColor
        ringLayer.lineWidth = 3
        ringLayer.opacity = 0
        layer.addSublayer(ringLayer)

        emojiLabel.font = .systemFont(ofSize: 96)
        emojiLabel.textAlignment = .center
        emojiLabel.adjustsFontSizeToFitWidth = true
        emojiLabel.minimumScaleFactor = 0.5
        emojiLabel.text = "😊"
        emojiLabel.translatesAutoresizingMaskIntoConstraints = false
        addSubview(emojiLabel)
        NSLayoutConstraint.activate([
            emojiLabel.centerXAnchor.constraint(equalTo: centerXAnchor),
            emojiLabel.centerYAnchor.constraint(equalTo: centerYAnchor),
            emojiLabel.widthAnchor.constraint(lessThanOrEqualTo: widthAnchor, constant: -16),
            emojiLabel.heightAnchor.constraint(lessThanOrEqualTo: heightAnchor, constant: -8),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func layoutSubviews() {
        super.layoutSubviews()
        ringLayer.frame = bounds
        let diameter = min(bounds.width, bounds.height) - 6
        let rect = CGRect(x: (bounds.width - diameter) / 2,
                          y: (bounds.height - diameter) / 2,
                          width: diameter, height: diameter)
        ringLayer.path = UIBezierPath(ovalIn: rect).cgPath
    }

    /// Shows the face emoji, cross-fade + small bounce (≈250 ms) when it changes.
    func show(emoji: String) {
        guard emoji != shownEmoji else { return }
        let first = shownEmoji == nil
        shownEmoji = emoji
        emojiLabel.layer.removeAllAnimations()
        if first {
            emojiLabel.text = emoji
            emojiLabel.alpha = 1
            emojiLabel.transform = .identity
            return
        }
        swapRunning = true
        stopBob(resetTransform: false)
        UIView.animate(withDuration: 0.11, animations: {
            self.emojiLabel.alpha = 0
            self.emojiLabel.transform = CGAffineTransform(scaleX: 0.85, y: 0.85)
        }, completion: { _ in
            self.emojiLabel.text = emoji
            UIView.animate(withDuration: 0.14, delay: 0,
                           usingSpringWithDamping: 0.62, initialSpringVelocity: 0,
                           options: [.allowUserInteraction], animations: {
                self.emojiLabel.alpha = 1
                self.emojiLabel.transform = .identity
            }, completion: { _ in
                self.swapRunning = false
                if self.bobDesired {
                    self.startBob()
                }
            })
        })
    }

    /// The pulsing ring around the face: syncs to the mic RMS while Listening only.
    /// `active` false hides the ring (all states except Listening).
    func setRing(active: Bool, pulse: Float = 0) {
        let clamped = max(0, min(1, pulse))
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        if active {
            ringLayer.opacity = 0.25 + 0.75 * clamped
            let scale = 1 + 0.05 * CGFloat(clamped)
            ringLayer.setAffineTransform(CGAffineTransform(scaleX: scale, y: scale))
        } else {
            ringLayer.opacity = 0
            ringLayer.setAffineTransform(.identity)
        }
        CATransaction.commit()
    }

    /// Gentle idle bob (slow scale 1.0↔1.02, 1.2 s cycle) while Speaking.
    func setBobbing(_ on: Bool) {
        bobDesired = on
        if on {
            if !swapRunning {
                startBob()
            }
        } else {
            stopBob(resetTransform: true)
        }
    }

    private func startBob() {
        guard !bobRunning, bobDesired else { return }
        bobRunning = true
        emojiLabel.transform = .identity
        UIView.animate(withDuration: 0.6, delay: 0,
                       options: [.autoreverse, .repeat, .curveEaseInOut, .allowUserInteraction],
                       animations: {
            self.emojiLabel.transform = CGAffineTransform(scaleX: 1.02, y: 1.02)
        })
    }

    private func stopBob(resetTransform: Bool) {
        if bobRunning {
            emojiLabel.layer.removeAllAnimations()
            bobRunning = false
        }
        if resetTransform {
            emojiLabel.transform = .identity
        }
    }
}

/// Lily-style level meter: rounded bars driven by the mic/playback RMS (0..1), flat idle floor.
final class VoiceWaveformView: UIView {

    private static let barCount = 14
    /// Per-bar shape so the row reads as a wave instead of a solid block.
    private static let shape: [CGFloat] = [
        0.55, 0.85, 1, 0.7, 0.9, 0.6, 1, 0.8, 0.5, 0.95, 0.65, 1, 0.75, 0.85,
    ]

    private var bars = [CGFloat](repeating: 0, count: VoiceWaveformView.barCount)
    private var target: CGFloat = 0
    private var link: CADisplayLink?

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        backgroundColor = .clear
        contentMode = .redraw
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    deinit {
        link?.invalidate()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil {
            stopTicking()
        }
    }

    /// New RMS level (0..1); the bars spring toward it and decay back to the floor.
    func setLevel(_ level: Float) {
        target = CGFloat(max(0, min(1, level)))
        startTicking()
    }

    private func startTicking() {
        guard link == nil else { return }
        let link = CADisplayLink(target: self, selector: #selector(tick))
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    private func stopTicking() {
        link?.invalidate()
        link = nil
    }

    @objc private func tick() {
        var moving = false
        for i in 0..<Self.barCount {
            let goal = target * Self.shape[i]
            let k: CGFloat = goal > bars[i] ? 0.55 : 0.18 // fast attack, slow release
            bars[i] += (goal - bars[i]) * k
            if abs(goal - bars[i]) > 0.01 {
                moving = true
            } else {
                bars[i] = goal
            }
        }
        setNeedsDisplay()
        if !moving {
            stopTicking()
        }
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        let w = bounds.width
        let h = bounds.height
        guard w > 0, h > 0 else { return }
        let slot = w / CGFloat(Self.barCount)
        let barWidth = slot * 0.55
        let minH = min(3, h * 0.12)
        let radius = barWidth / 2
        ctx.setFillColor(VoiceFaceView.accent.cgColor)
        for i in 0..<Self.barCount {
            let barH = minH + bars[i] * (h - minH)
            let left = CGFloat(i) * slot + (slot - barWidth) / 2
            let top = (h - barH) / 2
            ctx.addPath(UIBezierPath(roundedRect: CGRect(x: left, y: top, width: barWidth, height: barH),
                                     cornerRadius: radius).cgPath)
            ctx.fillPath()
        }
    }
}

// MARK: - Face-only screen

/// The Voice Control page's full-screen robot face: two glowing eyes and one mouth on black,
/// nothing else (design: "FriendBot Face Voice Control"; same shapes and timings as Android's
/// RobotFaceView). The page sets the emotion; the view animates by itself — blinks every
/// ~4.6 s, a small motion per emotion, a moving mouth while Xiaozhi speaks, sleepy after 60 s
/// with nothing happening. showPhoto(_:seconds:) replaces the face with the captured picture
/// (white flash, countdown bar), then the face smiles for 1.5 s.
/// Gestures: long-press = STOP, double-tap = show the page's controls for a while.
final class RobotFaceView: UIView {

    enum Emotion {
        case neutral, listening, thinking, happy, surprised, sad, sleepy, angry, confused
    }

    static let accent = UIColor(red: 0.498, green: 0.906, blue: 1.0, alpha: 1) // #7FE7FF
    private static let ground = UIColor(red: 0.02, green: 0.027, blue: 0.043, alpha: 1)

    var onLongPress: (() -> Void)?
    var onDoubleTap: (() -> Void)?

    private var emotion: Emotion = .neutral
    private var talking = false
    private var changedAt = CACurrentMediaTime()
    private var popStart: CFTimeInterval = 0
    private var photo: UIImage?
    private var photoStart: CFTimeInterval = 0
    private var photoSeconds: CFTimeInterval = 3
    private var happyUntil: CFTimeInterval = 0
    private var displayLink: CADisplayLink?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = Self.ground
        isOpaque = true
        contentMode = .redraw
        isAccessibilityElement = true
        accessibilityLabel = "FriendBot face"
        accessibilityHint = "Long-press to stop. Double-tap to show controls."
        let long = UILongPressGestureRecognizer(target: self, action: #selector(longPressed(_:)))
        long.minimumPressDuration = 0.6
        addGestureRecognizer(long)
        let double = UITapGestureRecognizer(target: self, action: #selector(doubleTapped))
        double.numberOfTapsRequired = 2
        addGestureRecognizer(double)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        displayLink?.invalidate()
        displayLink = nil
        if window != nil {
            let link = CADisplayLink(target: self, selector: #selector(tick))
            link.add(to: .main, forMode: .common)
            displayLink = link
        }
    }

    @objc private func tick() {
        if !isHidden { setNeedsDisplay() }
    }

    @objc private func longPressed(_ g: UILongPressGestureRecognizer) {
        guard g.state == .began else { return }
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        onLongPress?()
    }

    @objc private func doubleTapped() {
        onDoubleTap?()
    }

    /// The emotion to show; `talking` animates the mouth (Xiaozhi's voice is playing).
    func setFace(_ e: Emotion, talking isTalking: Bool) {
        let now = CACurrentMediaTime()
        if e != emotion || isTalking != talking {
            changedAt = now
            if e == .surprised && emotion != .surprised { popStart = now }
        }
        emotion = e
        talking = isTalking
    }

    /// Shows the captured picture full screen for `seconds`, then the face smiles.
    func showPhoto(_ image: UIImage, seconds: CFTimeInterval = 3) {
        photo = image
        photoStart = CACurrentMediaTime()
        photoSeconds = max(0.5, seconds)
        changedAt = photoStart
    }

    // MARK: Drawing

    private struct Geo {
        var w: CGFloat = 56, h: CGFloat = 88, r: CGFloat = 28
        var tilt: CGFloat = 0, dy: CGFloat = 0, lx: CGFloat = 0, ly: CGFloat = 0
        var rightScale: CGFloat = 1
        var arc = false, blink = true, o = false
        var anim = "breathe"
    }

    private static func geo(_ e: Emotion) -> Geo {
        var g = Geo()
        switch e {
        case .neutral: break
        case .listening: g.w = 60; g.h = 100; g.r = 30; g.anim = "listen"
        case .thinking: g.h = 60; g.r = 26; g.lx = 14; g.ly = -18; g.anim = "think"
        case .happy: g.arc = true; g.w = 70; g.h = 36; g.blink = false; g.anim = "bounce"
        case .surprised: g.w = 70; g.h = 70; g.r = 35; g.o = true; g.anim = "pop"
        case .sad: g.h = 60; g.r = 26; g.tilt = -14; g.dy = 12; g.anim = "droop"
        case .sleepy: g.w = 68; g.h = 10; g.r = 5; g.blink = false; g.anim = "snooze"
        case .angry: g.w = 60; g.h = 60; g.r = 14; g.tilt = 16; g.anim = "shake"
        case .confused: g.h = 76; g.r = 28; g.rightScale = 0.6; g.anim = "tilt"
        }
        return g
    }

    private static func wave(_ t: CFTimeInterval, _ period: CFTimeInterval) -> CGFloat {
        CGFloat(sin(2 * Double.pi * t.truncatingRemainder(dividingBy: period) / period))
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        let now = CACurrentMediaTime()
        Self.ground.setFill()
        ctx.fill(bounds)
        if let image = photo {
            let elapsed = now - photoStart
            if elapsed < photoSeconds {
                drawPhoto(ctx, image: image, elapsed: elapsed)
                return
            }
            photo = nil
            happyUntil = now + 1.5
            changedAt = now
        }
        var shown = emotion
        if now < happyUntil {
            shown = .happy
        } else if !talking && emotion == .neutral && now - changedAt > 60 {
            shown = .sleepy
        }
        drawFace(ctx, shown, talking: talking && now >= happyUntil, now: now)
    }

    private func drawFace(_ ctx: CGContext, _ e: Emotion, talking isTalking: Bool, now t: CFTimeInterval) {
        let w = bounds.width, h = bounds.height
        guard w > 0, h > 0 else { return }
        let s = min(w, h * 0.62) / 260
        let g = Self.geo(e)
        var tx: CGFloat = 0, ty: CGFloat = 0, scale: CGFloat = 1, rot: CGFloat = 0, alpha: CGFloat = 1
        switch g.anim {
        case "listen": scale = 1 + 0.0225 * (1 + Self.wave(t, 1.3))
        case "think": tx = 4 * s * (1 + Self.wave(t, 2.2))
        case "bounce": ty = -10 * s * abs(Self.wave(t, 1.8))
        case "pop":
            let p = CGFloat(min(1, (t - popStart) / 0.5))
            scale = p < 0.6 ? 0.85 + 0.23 * (p / 0.6) : 1.08 - 0.08 * ((p - 0.6) / 0.4)
        case "droop": ty = 3 * s * (1 + Self.wave(t, 3))
        case "snooze": alpha = 0.775 + 0.225 * Self.wave(t, 3.2)
        case "shake": tx = 4 * s * Self.wave(t, 0.45)
        case "tilt": rot = -3 * (1 + Self.wave(t, 2.4)) * .pi / 180
        default: ty = -3 * s * (1 + Self.wave(t, 4))
        }
        let color = Self.accent.withAlphaComponent(alpha)
        color.setFill()
        color.setStroke()
        ctx.saveGState()
        ctx.setShadow(offset: .zero, blur: 18, color: Self.accent.withAlphaComponent(0.4).cgColor)
        let cx = w / 2, cy = h / 2
        ctx.translateBy(x: cx + tx, y: cy + ty)
        ctx.rotate(by: rot)
        ctx.scaleBy(x: scale, y: scale)
        ctx.translateBy(x: -cx, y: -cy)

        let eyeBoxH = 100 * s, gap = 36 * s, mouthH = 100 * s
        let top = cy - (eyeBoxH + gap + mouthH) / 2
        let eyeCy = top + eyeBoxH / 2

        var blink: CGFloat = 1
        if g.blink {
            let ph = CGFloat(t.truncatingRemainder(dividingBy: 4.6) / 4.6)
            if ph > 0.93 {
                let k = ph < 0.96 ? (ph - 0.93) / 0.03 : (1 - ph) / 0.04
                blink = 1 - 0.92 * min(1, k)
            }
        }
        for side in [-1, 1] as [CGFloat] {
            let k = side > 0 ? g.rightScale : 1
            let ew = (g.arc ? g.w : g.w * k) * s
            let eh = (g.arc ? g.h : g.h * k) * s
            let ecx = cx + side * (32 * s + g.w * s / 2) + g.lx * s
            let ecy = eyeCy + g.ly * s + g.dy * s
            ctx.saveGState()
            ctx.translateBy(x: ecx, y: ecy)
            ctx.rotate(by: (side < 0 ? g.tilt : -g.tilt) * .pi / 180)
            if g.arc {
                let sw = 11 * s
                let arc = UIBezierPath(arcCenter: CGPoint(x: 0, y: eh / 2), radius: ew / 2 - sw / 2,
                                       startAngle: .pi, endAngle: 0, clockwise: true)
                arc.lineWidth = sw
                arc.lineCapStyle = .round
                arc.stroke()
            } else {
                let bh = eh * blink
                let r = min(g.r * k * s, bh / 2)
                UIBezierPath(roundedRect: CGRect(x: -ew / 2, y: -bh / 2, width: ew, height: bh),
                             cornerRadius: r).fill()
            }
            ctx.restoreGState()
        }

        let mx = cx - 100 * s
        let my = top + eyeBoxH + gap
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: mx + x * s, y: my + y * s) }
        if isTalking {
            let open = 0.3 + 0.35 * (1 + Self.wave(t, 0.56))
            UIBezierPath(ovalIn: CGRect(x: mx + 70 * s, y: my + (50 - 22 * open) * s,
                                        width: 60 * s, height: 44 * open * s)).fill()
        } else if g.o {
            let o = UIBezierPath(ovalIn: CGRect(x: mx + 84 * s, y: my + 30 * s, width: 32 * s, height: 40 * s))
            o.lineWidth = 10 * s
            o.stroke()
        } else {
            let m = UIBezierPath()
            switch e {
            case .listening: m.move(to: p(84, 50)); m.addQuadCurve(to: p(116, 50), controlPoint: p(100, 56))
            case .thinking: m.move(to: p(78, 56)); m.addLine(to: p(126, 48))
            case .happy: m.move(to: p(46, 30)); m.addQuadCurve(to: p(154, 30), controlPoint: p(100, 100))
            case .sad: m.move(to: p(62, 70)); m.addQuadCurve(to: p(138, 70), controlPoint: p(100, 36))
            case .sleepy: m.move(to: p(88, 54)); m.addQuadCurve(to: p(112, 54), controlPoint: p(100, 60))
            case .angry: m.move(to: p(66, 62)); m.addLine(to: p(134, 62))
            case .confused:
                m.move(to: p(60, 56)); m.addQuadCurve(to: p(100, 56), controlPoint: p(80, 44))
                m.addQuadCurve(to: p(140, 56), controlPoint: p(120, 68))
            default: m.move(to: p(64, 46)); m.addQuadCurve(to: p(136, 46), controlPoint: p(100, 62))
            }
            m.lineWidth = 10 * s
            m.lineCapStyle = .round
            m.lineJoinStyle = .round
            m.stroke()
        }
        ctx.restoreGState()
    }

    private func drawPhoto(_ ctx: CGContext, image: UIImage, elapsed: CFTimeInterval) {
        UIColor.black.setFill()
        ctx.fill(bounds)
        let insets = safeAreaInsets
        let left: CGFloat = 20, right = bounds.width - 20
        let barTop = max(56, insets.top + 12)
        UIColor.white.withAlphaComponent(0.13).setFill()
        UIBezierPath(roundedRect: CGRect(x: left, y: barTop, width: right - left, height: 4), cornerRadius: 2).fill()
        let leftFrac = CGFloat(1 - min(1, elapsed / photoSeconds))
        Self.accent.setFill()
        UIBezierPath(roundedRect: CGRect(x: left, y: barTop, width: (right - left) * leftFrac, height: 4),
                     cornerRadius: 2).fill()
        let box = CGRect(x: left, y: barTop + 20, width: right - left,
                         height: bounds.height - max(40, insets.bottom + 16) - (barTop + 20))
        ctx.saveGState()
        UIBezierPath(roundedRect: box, cornerRadius: 24).addClip()
        // Centre-crop (aspect fill) into the frame.
        let iw = image.size.width, ih = image.size.height
        let k = max(box.width / max(iw, 1), box.height / max(ih, 1))
        let dw = iw * k, dh = ih * k
        image.draw(in: CGRect(x: box.midX - dw / 2, y: box.midY - dh / 2, width: dw, height: dh))
        ctx.restoreGState()
        if elapsed < 0.35 {
            UIColor.white.withAlphaComponent(0.95 * CGFloat(1 - elapsed / 0.35)).setFill()
            ctx.fill(bounds)
        }
    }
}
