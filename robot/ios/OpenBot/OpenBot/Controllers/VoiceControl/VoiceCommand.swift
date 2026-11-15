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
    /// Close the gripper: hold / grab the object (firmware "g1").
    case grip
    /// Open the gripper: let the object go (firmware "g0").
    case release
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

// MARK: - Xiaozhi tools/call mapping

extension VoiceCommand {

    /// The assistant's robot tools — the same set on every page that hosts the session.
    static let robotToolNames = [
        "self.robot.move", "self.robot.stop", "self.robot.led",
        "self.robot.indicator", "self.robot.sonar", "self.robot.trick",
        "self.robot.gripper",
    ]

    /**
     Maps a `tools/call` to the same VoiceCommand model the quick buttons use — the Voice
     Control page's tool mapping, shared with the Voice Grab page so the assistant's commands
     behave identically wherever they land. Nil = unknown tool or unusable args (the caller
     rejects rather than guessing).
     */
    static func from(tool name: String, args: [String: Any]) -> VoiceCommand? {
        switch name {
        case "self.robot.move":
            let action: VoiceAction
            // LLMs vary: "direction" per the schema, sometimes "action", and synonyms.
            let dir = (args["direction"] as? String ?? args["action"] as? String ?? "")
                .lowercased().trimmingCharacters(in: .whitespaces)
            switch dir {
            case "forward", "ahead", "straight", "go": action = .forward
            case "backward", "back", "reverse": action = .backward
            case "left", "turn_left", "turnleft": action = .left
            case "right", "turn_right", "turnright": action = .right
            case "stop", "halt": action = .stop
            default:
                return nil // unknown direction: reject rather than stop the robot by default
            }
            let speed = (args["speed"] as? NSNumber)?.floatValue ?? VoiceCommand.defaultSpeed
            let duration = (args["duration_ms"] as? NSNumber)?.intValue ?? VoiceCommand.defaultDurationMs
            return VoiceCommand(action: action, speed: speed, durationMs: duration,
                                rawText: name, source: "xiaozhi")
        case "self.robot.stop":
            return VoiceCommand(action: .stop, rawText: name, source: "xiaozhi")
        case "self.robot.led":
            // Front/back lights: {"state": "on" | "off"} (Android parity).
            switch (args["state"] as? String ?? "").lowercased() {
            case "on": return VoiceCommand(action: .lightsOn, rawText: name, source: "xiaozhi")
            case "off": return VoiceCommand(action: .lightsOff, rawText: name, source: "xiaozhi")
            default: return nil // unknown state: reject rather than guess
            }
        case "self.robot.indicator":
            // Turn signals: {"state": "left" | "right" | "both" | "off"}.
            let action: VoiceAction
            switch args["state"] as? String ?? "" {
            case "left": action = .indicatorLeft
            case "right": action = .indicatorRight
            case "both": action = .indicatorHazard
            case "off": action = .indicatorOff
            default: return nil // unknown state: reject rather than guess
            }
            return VoiceCommand(action: action, rawText: name, source: "xiaozhi")
        case "self.robot.sonar":
            return VoiceCommand(action: .sonar, rawText: name, source: "xiaozhi")
        case "self.robot.gripper":
            switch (args["action"] as? String ?? "").lowercased() {
            case "grab": return VoiceCommand(action: .grip, rawText: name, source: "xiaozhi")
            case "release": return VoiceCommand(action: .release, rawText: name, source: "xiaozhi")
            default: return nil // unknown: never guess with the claw
            }
        case "self.robot.trick":
            let action: VoiceAction
            switch args["name"] as? String ?? "" {
            case "triangle": action = .triangle
            case "circle": action = .circle
            case "spin": action = .spin360
            case "turn_around": action = .turnAround
            case "wiggle": action = .wiggle
            case "sing": action = .sing
            default: return nil // unknown trick: reject, never spin the robot by default
            }
            return VoiceCommand(action: action, rawText: name, source: "xiaozhi")
        default:
            return nil
        }
    }
}

// MARK: - Fetch: find an object with the camera, drive to it, grip it

/// "Pick up the red cup" (same logic as Android's ObjectFetcher, tested there in a simulation).
/// Step by step: look (photo + vision question) → turn toward the object / drive a little →
/// look again → when it is big in the photo (or the sonar says close) creep in and grip.
/// One photo cannot tell distance or angle exactly, so every step corrects the last one.
/// The final creep is gated by the front sonar (the "5 cm rule"): the claw only closes
/// once the sonar reads within 5 cm of the object — never from farther, never under 2 cm.
final class ObjectFetcher {

    /// What the fetcher needs from the voice page. All calls on the main queue.
    struct Robot {
        /// Take a photo and ask the vision service; answer text, or nil on failure.
        let look: (String, @escaping (String?) -> Void) -> Void
        /// Drive both wheels (-1...1) for ms, stop, then call back.
        let drive: (Float, Float, Int, @escaping () -> Void) -> Void
        let gripper: (Bool) -> Void
        let approach: (Int) -> Void
        let sonarCm: () -> Float
        let progress: (String) -> Void
    }

    // Tuning (FriendBot chassis; see the README "Gripper" section).
    static let spin360Ms = 3250          // a 360° spin at turnSpeed
    static let turnSpeed: Float = 0.6
    static let fineTurnSpeed: Float = 0.45
    static let fineTurnDeg: Float = 15
    static let minTurnMs = 120
    static let driveSpeed: Float = 0.5
    static let finalSpeed: Float = 0.2 // slow creep toward the object (sonar-gated)
    static let halfFovDeg: Float = 30
    static let centreTolerance: Float = 10
    static let centreToleranceNear: Float = 18
    static let grabWidth: Float = 38
    static let grabSonarCm: Float = 20
    /// The "5 cm rule": the claw only closes once the front sonar reads within this of the object.
    static let gripCm: Float = 5
    /// Under this the object is already under the claw body: stop, never close.
    static let tooCloseCm: Float = 2
    /// One sonar-gated creep step: short drive, settle for a fresh reading, decide again.
    static let creepStepMs = 250
    static let creepSettle = 0.25
    static let maxCreepSteps = 16
    /// Without a working sonar: this many blind steps, then grab anyway.
    static let blindCreepSteps = 5
    static let searchTurnDeg: Float = 45
    static let maxLooks = 24
    static let maxMisses = 9
    static let settle = 0.35
    static let gripperOpenWait = 0.7
    static let gripperCloseWait = 1.0

    private let robot: Robot
    private var object = ""
    private var done: ((Bool, String) -> Void)?
    private var run = 0
    private(set) var isRunning = false
    private var looks = 0
    private var misses = 0

    init(robot: Robot) {
        self.robot = robot
    }

    func start(_ what: String, done: @escaping (Bool, String) -> Void) {
        cancel()
        let trimmed = what.trimmingCharacters(in: .whitespacesAndNewlines)
        object = trimmed.isEmpty ? "the object in front of the robot" : trimmed
        self.done = done
        isRunning = true
        looks = 0
        misses = 0
        run += 1
        let me = run
        robot.progress("Fetching \(object): opening the gripper")
        robot.gripper(false)
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.gripperOpenWait) { [weak self] in self?.look(me) }
    }

    /// STOP: ends the fetch at once (the caller stops the motors).
    func cancel() {
        run += 1
        guard isRunning else { return }
        robot.approach(0) // a creep's approach mode ends with the fetch
        isRunning = false
        let d = done
        done = nil
        d?(false, "Fetch stopped.")
    }

    private func alive(_ me: Int) -> Bool { isRunning && me == run }

    private func finish(_ me: Int, _ ok: Bool, _ message: String) {
        guard alive(me) else { return }
        isRunning = false
        let d = done
        done = nil
        d?(ok, message)
    }

    static func question(_ object: String) -> String {
        "Is there \(object) in this photo? Answer in exactly this format and nothing else:"
            + " FOUND x=<number> w=<number> . x is where the centre of \(object) is, from the left"
            + " edge 0 to the right edge 100. w is how wide it looks, in percent of the photo width."
            + " If you cannot see it, answer exactly: NONE"
    }

    private func look(_ me: Int) {
        guard alive(me) else { return }
        looks += 1
        guard looks <= Self.maxLooks else {
            finish(me, false, "I could not reach \(object) - please point me at it and try again.")
            return
        }
        robot.look(Self.question(object)) { [weak self] text in
            guard let self = self, self.alive(me) else { return }
            self.decide(me, Self.parse(text))
        }
    }

    private func decide(_ me: Int, _ s: (x: Float, width: Float)?) {
        let sonar = robot.sonarCm()
        guard let s = s else {
            misses += 1
            guard misses <= Self.maxMisses else {
                finish(me, false, "I looked all around but could not find \(object).")
                return
            }
            robot.progress("Looking for \(object) (turning)")
            turn(me, Self.searchTurnDeg)
            return
        }
        misses = 0
        let offset = s.x - 50
        let tolerance = s.width >= 25 ? Self.centreToleranceNear : Self.centreTolerance
        if abs(offset) > tolerance {
            let deg = offset / 50 * Self.halfFovDeg
            robot.progress(String(format: "I see %@ - turning %@ %.0f°", object, deg < 0 ? "left" : "right", abs(deg)))
            turn(me, deg)
            return
        }
        let close = s.width >= Self.grabWidth || (sonar > 0 && sonar <= Self.grabSonarCm)
        if !close {
            let ms = s.width < 10 ? 1100 : s.width < 20 ? 750 : s.width < 30 ? 500 : 320
            robot.progress("Driving to \(object)")
            robot.drive(Self.driveSpeed, Self.driveSpeed, ms) { [weak self] in self?.settleThenLook(me) }
            return
        }
        robot.progress("Picking up \(object)")
        creepThenGrip(me, 0, false)
    }

    /// The final approach: creep toward the object with the front sonar managing the distance
    /// and close the claw only once it reads within 5 cm (the "5 cm rule"). Already in reach
    /// → grip at once; the sonar never produced a reading → grab after a short blind creep;
    /// it produced readings but the object never came within 5 cm → stop without gripping.
    private func creepThenGrip(_ me: Int, _ step: Int, _ sonarSeen: Bool) {
        guard alive(me) else { return }
        var seen = sonarSeen
        let d = robot.sonarCm()
        if d >= 0 {
            if d < Self.tooCloseCm {
                robot.approach(0)
                finish(me, false, "I am too close to \(object) to grab it.")
                return
            }
            if d <= Self.gripCm {
                grip(me)
                return
            }
            seen = true
        }
        if seen ? step >= Self.maxCreepSteps : step >= Self.blindCreepSteps {
            if seen {
                robot.approach(0)
                finish(me, false, "I could not get within \(Int(Self.gripCm)) cm of \(object).")
            } else {
                grip(me) // no sonar on this robot: grab after the short blind creep
            }
            return
        }
        // Firmware safety stops forward at 15 cm; approach mode lets it creep on to 4 cm.
        robot.approach(2000)
        robot.drive(Self.finalSpeed, Self.finalSpeed, Self.creepStepMs) { [weak self] in
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.creepSettle) { [weak self] in
                self?.creepThenGrip(me, step + 1, seen)
            }
        }
    }

    /// The object is at the grip distance (or the blind creep ran out): close the claw.
    private func grip(_ me: Int) {
        guard alive(me) else { return }
        robot.gripper(true)
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.gripperCloseWait) { [weak self] in
            guard let self = self else { return }
            self.finish(me, true, "I am holding \(self.object). Where should I take it?")
        }
    }

    private func turn(_ me: Int, _ deg: Float) {
        let fine = abs(deg) < Self.fineTurnDeg
        let speed = fine ? Self.fineTurnSpeed : Self.turnSpeed
        let ms = max(Self.minTurnMs, Int((abs(deg) / 360 * Float(Self.spin360Ms) * Self.turnSpeed / speed).rounded()))
        let l: Float = deg < 0 ? -speed : speed
        robot.drive(l, -l, ms) { [weak self] in self?.settleThenLook(me) }
    }

    private func settleThenLook(_ me: Int) {
        guard alive(me) else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.settle) { [weak self] in self?.look(me) }
    }

    /// Reads "FOUND x=62 w=18" from the vision answer; nil for NONE, garbage or no answer.
    static func parse(_ text: String?) -> (x: Float, width: Float)? {
        guard let text = text,
              let re = try? NSRegularExpression(
                pattern: "FOUND\\W*x\\s*[=:]\\s*(-?\\d+(?:\\.\\d+)?)\\s*%?\\W*w\\s*[=:]\\s*(\\d+(?:\\.\\d+)?)",
                options: [.caseInsensitive]),
              let m = re.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              let xr = Range(m.range(at: 1), in: text), let wr = Range(m.range(at: 2), in: text),
              var x = Float(text[xr]), var w = Float(text[wr]) else { return nil }
        if x <= 1, w <= 1, x > 0 || w > 0, text.contains(".") {
            x *= 100  // some models answer 0...1 instead of 0...100
            w *= 100
        }
        return (min(100, max(0, x)), min(100, max(0, w)))
    }
}
