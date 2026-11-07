//
//  VoiceCommand.swift
//  OpenBot
//
//  One understood voice command, ready to drive the robot.
//
//  The same model is used whether the words were understood by the Xiaozhi LLM or by the
//  offline keyword interpreter, and whether they came from the microphone or the on-screen
//  buttons. Actions map onto the firmware's "c<left>,<right>" drive values plus its
//  accessory lines ("l<front>,<back>" lights, "s<interval>" sonar reports); moving commands
//  run for their duration and then auto-stop. The trick actions (sing, triangle, circle,
//  360° spin, turn around, wiggle) are choreographed moves made of several drive segments
//  (toScript()). Mirrors VoiceCommand.java on Android.
//

import Foundation

enum VoiceAction {
    case forward, backward, left, right, stop, faster, slower, unknown
    /// Pure conversation ("none" from the LLM): halt and speak, never drive. Named .chat
    /// because .none clashes with Optional in Swift.
    case chat
    /// Front/back lights on (firmware "l255,255").
    case lightsOn
    /// Front/back lights off (firmware "l0,0").
    case lightsOff
    /// Report the sonar distance in cm (firmware "s<interval>" / "s<cm>").
    case sonar
    /// Sing a short tune and turn around (one 360° spin).
    case sing
    /// Drive an equilateral triangle: forward, turn 120°, three times.
    case triangle
    /// Drive one full circle (inside wheel slower than the outside wheel).
    case circle
    /// Spin in place a full 360°.
    case spin360
    /// Spin in place 180° ("turn around").
    case turnAround
    /// Swing left-right-left-right in place ("turn left and right").
    case wiggle
    /// Left turn signal on (firmware "i1,0", nonzero = blinking).
    case indicatorLeft
    /// Right turn signal on (firmware "i0,1").
    case indicatorRight
    /// Both signals — hazard lights (firmware "i1,1").
    case indicatorHazard
    /// All signals off (firmware "i0,0").
    case indicatorOff
}

/// One leg of a choreographed move: drive left/right for ms, then the next leg (or stop).
typealias DriveSegment = (left: Float, right: Float, ms: Int)

struct VoiceCommand {
    let action: VoiceAction
    /// 0.15...1.0, per-side fraction of full scale.
    let speed: Float
    /// How long the move lasts before auto-stop.
    let durationMs: Int
    /// What the user said (or the action name from the LLM).
    let rawText: String
    /// "xiaozhi", "local" or "tap".
    let source: String

    static let defaultSpeed: Float = 0.75
    static let defaultDurationMs = 1200
    /// Voices may ask for a long move ("go forward for 10 seconds"); the resend loop covers it.
    static let maxDurationMs = 15000

    /// Segment timings for the choreographed moves at defaultSpeed, tuned for the FriendBot
    /// chassis: a 360° spin takes about 2.6 s at speed 0.75, so a 180° turn is half of that
    /// and a 120° triangle corner a third.
    private static let spin360Ms = 2600
    private static let turnMs = 1300
    private static let triangleTurnMs = 870
    private static let triangleForwardMs = 1300
    private static let circleMs = 5200
    private static let wiggleMs = 550

    init(action: VoiceAction,
         speed: Float = VoiceCommand.defaultSpeed,
         durationMs: Int = VoiceCommand.defaultDurationMs,
         rawText: String = "",
         source: String = "local") {
        self.action = action
        self.speed = VoiceCommand.clampSpeed(speed)
        self.durationMs = VoiceCommand.clampDuration(durationMs)
        self.rawText = rawText
        self.source = source
    }

    private static func clampSpeed(_ speed: Float) -> Float {
        guard speed > 0 else { return defaultSpeed }
        return min(1, max(0.15, speed))
    }

    private static func clampDuration(_ durationMs: Int) -> Int {
        guard durationMs > 0 else { return defaultDurationMs }
        return min(maxDurationMs, durationMs)
    }

    /// True for commands that make the robot move and so need the auto-stop timer.
    var isMotion: Bool {
        switch action {
        case .forward, .backward, .left, .right: return true
        default: return !toScript().isEmpty
        }
    }

    /// Left/right drive values in -1...1, or nil when this is not a drive command.
    /// Turning spins in place, matching the firmware's text channel.
    func toWheels() -> (left: Float, right: Float)? {
        switch action {
        case .forward: return (speed, speed)
        case .backward: return (-speed, -speed)
        case .left: return (-speed, speed)
        case .right: return (speed, -speed)
        case .stop: return (0, 0)
        default: return nil
        }
    }

    /// The choreographed moves as drive segments — one per leg of the trick, played back in
    /// order. Empty for commands that are not scripts.
    func toScript() -> [DriveSegment] {
        switch action {
        case .spin360:
            return [(-speed, speed, Self.spin360Ms)]
        case .turnAround:
            return [(-speed, speed, Self.turnMs)]
        case .wiggle:
            return [
                (-speed, speed, Self.wiggleMs),
                (speed, -speed, Self.wiggleMs),
                (-speed, speed, Self.wiggleMs),
                (speed, -speed, Self.wiggleMs),
            ]
        case .triangle:
            var segs: [DriveSegment] = []
            for _ in 0..<3 {
                segs.append((speed, speed, Self.triangleForwardMs))
                segs.append((-speed, speed, Self.triangleTurnMs))
            }
            return segs
        case .circle:
            // Slow inside wheel traces one full lap to the left.
            return [(0.35 * speed, speed, Self.circleMs)]
        case .sing:
            return [(-speed, speed, Self.spin360Ms)]
        default:
            return []
        }
    }
}
