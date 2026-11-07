//
//  VoiceInterpreter.swift
//  OpenBot
//
//  Offline keyword understanding for the Voice Control screen.
//
//  The fallback used when no Xiaozhi server is configured (or the server is unreachable, or the
//  phone is offline): the speech-to-text transcript is matched against the same small vocabulary
//  the firmware's "t<text>" channel understands, in English, Vietnamese and Chinese — extended
//  with the voice page's trick and accessory commands (lights, sonar, sing, triangle, circle,
//  360° spin, turn around, turn left and right). Order matters and mirrors the firmware exactly:
//  "stop" wins over everything ("don't go" must stop, not drive), and "back" is checked before
//  "go" so "go back" reverses. The tricks are checked before the plain moves because their
//  phrases contain those words ("move in circle" must not become "move", "sing and turn around"
//  must not become "turn around", "turn left and right" must not become "turn left"). A number
//  in the words stretches the move ("go forward 5 seconds", "tiến 10 giây", "go 3000 ms").
//  Mirrors VoiceInterpreter.java on Android.
//

import Foundation

enum VoiceInterpreter {

    private static let stopWords = ["stop", "halt", "dừng", "dừng lại", "dung lai", "đứng lại", "dung", "停", "停止", "别动"]
    private static let singWords = ["sing", "song", "hát", "hat", "唱歌", "唱"]
    private static let sonarWords = ["sonar", "distance", "how far", "khoảng cách", "khoang cach", "cảm biến", "距离", "多远", "超声波"]
    private static let lightsOnWords = ["lights on", "light on", "turn on the light", "turn on light", "turn on lights", "bật đèn", "bat den", "mở đèn", "mo den", "开灯", "打开灯"]
    private static let lightsOffWords = ["lights off", "light off", "turn off the light", "turn off light", "turn off lights", "tắt đèn", "tat den", "关灯", "关掉灯"]
    private static let triangleWords = ["triangle", "tam giác", "tam giac", "三角形", "三角"]
    private static let circleWords = ["circle", "vòng tròn", "vong tron", "vòng", "圆形", "圆圈", "画圆"]
    private static let spinWords = ["360", "spin", "xoay", "một vòng", "mot vong", "旋转", "转一圈", "转圈"]
    private static let turnAroundWords = ["turn around", "turnaround", "quay lại", "quay lai", "quay đầu", "quay dau", "掉头", "转身"]
    private static let wiggleWords = ["left and right", "right and left", "trái phải", "trai phai", "phải trái", "phai trai", "左右", "左右转", "两边"]
    // Turn signals before BACK/LEFT/RIGHT: their phrases contain "left"/"right".
    private static let indicatorLeftWords = ["signal left", "left signal", "left indicator", "left blinker", "xi nhan trái", "xi nhan trai"]
    private static let indicatorRightWords = ["signal right", "right signal", "right indicator", "right blinker", "xi nhan phải", "xi nhan phai"]
    private static let indicatorHazardWords = ["hazard", "hazard lights", "both signals", "đèn hazard", "xi nhan cả hai"]
    private static let indicatorOffWords = ["signals off", "turn off the signal", "indicators off", "tắt xi nhan", "tat xi nhan"]
    private static let backWords = ["back", "reverse", "retreat", "go back", "lùi", "lùi lại", "lui", "倒", "后退", "向后"]
    private static let leftWords = ["left", "turn left", "trái", "rẽ trái", "re trai", "左", "左转"]
    private static let rightWords = ["right", "turn right", "phải", "rẽ phải", "re phai", "右", "右转"]
    private static let fasterWords = ["faster", "speed up", "speedup", "nhanh", "nhanh lên", "tăng tốc", "快", "快点", "加速"]
    private static let slowerWords = ["slower", "slow down", "slowdown", "chậm", "chậm lại", "cham", "giảm tốc", "慢", "慢点", "减速"]
    private static let forwardWords = ["forward", "ahead", "straight", "go", "move", "tiến", "tien", "đi", "前进", "向前", "走"]
    /// "go forward for a long time" — stretches a plain move to 5 s.
    private static let longWords = ["long", "for a while", "lâu", "lau", "xa", "far"]

    /// A spoken duration: number + optional unit ("5 seconds", "3000 ms", "10 giây", "2 phút").
    private static let durationRegex = try! NSRegularExpression(
        pattern: "(\\d+(?:[.,]\\d+)?)\\s*(ms|milliseconds?|mili\\w*|s|secs?|seconds?|giây|giay|phút|phut|minutes?|min)?")

    private static let longMoveMs = 5000

    /// Matches a transcript; never fails. Unknown words become `.unknown`.
    static func interpret(_ text: String) -> VoiceCommand {
        let t = text.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else {
            return VoiceCommand(action: .unknown, rawText: text)
        }

        let action: VoiceAction
        // Tricks before plain moves: their phrases contain the plain move words.
        if containsAny(t, stopWords) { action = .stop }
        else if containsAny(t, singWords) { action = .sing }
        else if containsAny(t, sonarWords) { action = .sonar }
        else if containsAny(t, lightsOnWords) { action = .lightsOn }
        else if containsAny(t, lightsOffWords) { action = .lightsOff }
        else if containsAny(t, triangleWords) { action = .triangle }
        else if containsAny(t, circleWords) { action = .circle }
        else if containsAny(t, spinWords) { action = .spin360 }
        else if containsAny(t, turnAroundWords) { action = .turnAround }
        else if containsAny(t, wiggleWords) { action = .wiggle }
        else if containsAny(t, indicatorLeftWords) { action = .indicatorLeft }
        else if containsAny(t, indicatorRightWords) { action = .indicatorRight }
        else if containsAny(t, indicatorHazardWords) { action = .indicatorHazard }
        else if containsAny(t, indicatorOffWords) { action = .indicatorOff }
        else if containsAny(t, backWords) { action = .backward }
        else if containsAny(t, leftWords) { action = .left }
        else if containsAny(t, rightWords) { action = .right }
        else if containsAny(t, fasterWords) { action = .faster }
        else if containsAny(t, slowerWords) { action = .slower }
        else if containsAny(t, forwardWords) { action = .forward }
        else { action = .unknown }

        var durationMs = parseDurationMs(t)
        if durationMs <= 0, containsAny(t, longWords) { durationMs = longMoveMs }
        guard durationMs > 0 else {
            return VoiceCommand(action: action, rawText: text)
        }
        return VoiceCommand(action: action, speed: VoiceCommand.defaultSpeed,
                            durationMs: durationMs, rawText: text)
    }

    /// The duration the words asked for in ms ("go forward 5 seconds" → 5000), or -1 when the
    /// words carry no duration. A bare number is seconds when small (≤ 60) and milliseconds when
    /// large ("go 3000" runs 3000 ms). Clamped by VoiceCommand.maxDurationMs.
    static func parseDurationMs(_ text: String) -> Int {
        let range = NSRange(text.startIndex..., in: text)
        guard let match = durationRegex.firstMatch(in: text, range: range),
              let valueRange = Range(match.range(at: 1), in: text),
              let value = Double(text[valueRange].replacingOccurrences(of: ",", with: "."))
        else { return -1 }
        var unit = ""
        if match.range(at: 2).location != NSNotFound, let unitRange = Range(match.range(at: 2), in: text) {
            unit = String(text[unitRange])
        }
        let ms: Double
        if unit.hasPrefix("ms") || unit.hasPrefix("mili") { ms = value }
        else if unit.hasPrefix("ph") || unit.hasPrefix("min") || unit.hasPrefix("mi") { ms = value * 60000 }
        else if unit.isEmpty { ms = value <= 60 ? value * 1000 : value }
        else { ms = value * 1000 } // s, sec, second, giây…
        let rounded = Int(ms.rounded())
        return max(1, min(VoiceCommand.maxDurationMs, rounded))
    }

    private static func containsAny(_ text: String, _ needles: [String]) -> Bool {
        needles.contains { text.contains($0) }
    }
}
