//
//  VoiceInterpreter.swift
//  OpenBot
//
//  Offline keyword understanding for the Voice Control screen.
//
//  The fallback used when no Xiaozhi server is configured (or the server is unreachable, or the
//  phone is offline): the speech-to-text transcript is matched against the same small vocabulary
//  the firmware's "t<text>" channel understands, in English, Vietnamese and Chinese. Order
//  matters and mirrors the firmware exactly: "stop" wins over everything ("don't go" must stop,
//  not drive), and "back" is checked before "go" so "go back" reverses. Mirrors
//  VoiceInterpreter.java on Android.
//

import Foundation

enum VoiceInterpreter {

    private static let stopWords = ["stop", "halt", "dừng", "dừng lại", "dung lai", "đứng lại", "dung", "停", "停止", "别动"]
    private static let backWords = ["back", "reverse", "retreat", "go back", "lùi", "lùi lại", "lui", "倒", "后退", "向后"]
    private static let leftWords = ["left", "turn left", "trái", "rẽ trái", "re trai", "左", "左转"]
    private static let rightWords = ["right", "turn right", "phải", "rẽ phải", "re phai", "右", "右转"]
    private static let fasterWords = ["faster", "speed up", "speedup", "nhanh", "nhanh lên", "tăng tốc", "快", "快点", "加速"]
    private static let slowerWords = ["slower", "slow down", "slowdown", "chậm", "chậm lại", "cham", "giảm tốc", "慢", "慢点", "减速"]
    private static let forwardWords = ["forward", "ahead", "straight", "go", "move", "tiến", "tien", "đi", "前进", "向前", "走"]

    /// Matches a transcript; never fails. Unknown words become `.unknown`.
    static func interpret(_ text: String) -> VoiceCommand {
        let t = text.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else {
            return VoiceCommand(action: .unknown, rawText: text)
        }

        let action: VoiceAction
        if containsAny(t, stopWords) { action = .stop }
        else if containsAny(t, backWords) { action = .backward }
        else if containsAny(t, leftWords) { action = .left }
        else if containsAny(t, rightWords) { action = .right }
        else if containsAny(t, fasterWords) { action = .faster }
        else if containsAny(t, slowerWords) { action = .slower }
        else if containsAny(t, forwardWords) { action = .forward }
        else { action = .unknown }

        return VoiceCommand(action: action, rawText: text)
    }

    private static func containsAny(_ text: String, _ needles: [String]) -> Bool {
        needles.contains { text.contains($0) }
    }
}
