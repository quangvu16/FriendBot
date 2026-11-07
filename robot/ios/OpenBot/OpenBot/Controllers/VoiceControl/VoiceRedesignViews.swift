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
