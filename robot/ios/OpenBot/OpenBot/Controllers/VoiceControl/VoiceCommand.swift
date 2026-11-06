//
//  VoiceCommand.swift
//  OpenBot
//
//  One understood voice command, ready to drive the robot.
//
//  The same model is used whether the words were understood by the Xiaozhi LLM or by the
//  offline keyword interpreter, and whether they came from the microphone or the on-screen
//  buttons. Actions map onto the firmware's "c<left>,<right>" drive values; moving commands
//  run for their duration and then auto-stop. Mirrors VoiceCommand.java on Android.
//

import Foundation

enum VoiceAction {
    case forward, backward, left, right, stop, faster, slower, unknown
    /// Pure conversation ("none" from the LLM): halt and speak, never drive. Named .chat
    /// because .none clashes with Optional in Swift.
    case chat
}

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
    static let maxDurationMs = 8000

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
        default: return false
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
}
