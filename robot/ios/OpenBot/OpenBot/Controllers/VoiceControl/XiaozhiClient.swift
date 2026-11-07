//
//  XiaozhiClient.swift
//  OpenBot
//
//  The "Xiaozhi brain" of the Voice Control screen.
//
//  The phone does its own listening (SFSpeechRecognizer) and talking (AVSpeechSynthesizer);
//  this client only does the understanding: it sends what you said to a Xiaozhi LLM endpoint
//  and gets back a robot command as JSON. It speaks the OpenAI-compatible chat-completions
//  shape exposed by the Xiaozhi API gateway (https://api.tenclass.net/v1, the service behind
//  xiaozhi.me) and by any OpenAI-compatible endpoint the user points it at - their own
//  OpenAI/DeepSeek/... key or a proxy in front of a self-hosted xiaozhi-esp32-server. The
//  model must answer with one JSON object:
//
//    {"action":"forward|backward|left|right|stop|none|lights_on|lights_off|sonar|sing|triangle|circle|spin|turn_around|wiggle|indicator_left|indicator_right|indicator_hazard|indicator_off",
//     "speed":0.15-1.0, "duration_ms":300-15000, "say":"short spoken reply"}
//
//  "none" is pure conversation (no movement) and maps to VoiceAction.chat. The call is
//  multi-turn: the previous exchanges ride along so follow-ups ("faster" after "go
//  forward") resolve against the conversation. When no server is configured (or the call
//  fails) the screen falls back to VoiceInterpreter, so the robot is always drivable by
//  voice, online or not. Mirrors XiaozhiClient.java on Android.
//

import Foundation

struct XiaozhiConfig {
    var endpoint: String
    var token: String
    var model: String

    /// A token is required; the endpoint has a default.
    var isConfigured: Bool {
        !token.trimmingCharacters(in: .whitespaces).isEmpty
    }
}

final class XiaozhiClient {

    static let defaultEndpoint = "https://api.tenclass.net/v1/chat/completions"
    static let defaultModel = "xz-lite"

    private static let endpointKey = "xiaozhi_endpoint"
    private static let tokenKey = "xiaozhi_token"
    private static let modelKey = "xiaozhi_model"

    static func loadConfig() -> XiaozhiConfig {
        let defaults = UserDefaults.standard
        return XiaozhiConfig(
            endpoint: defaults.string(forKey: endpointKey) ?? defaultEndpoint,
            token: defaults.string(forKey: tokenKey) ?? "",
            model: defaults.string(forKey: modelKey) ?? defaultModel)
    }

    static func save(_ config: XiaozhiConfig) {
        let defaults = UserDefaults.standard
        defaults.set(config.endpoint, forKey: endpointKey)
        defaults.set(config.token, forKey: tokenKey)
        defaults.set(config.model, forKey: modelKey)
    }

    private static let systemPrompt = """
    You are the drive controller of FriendBot, a two-wheel differential-drive robot. \
    The user speaks to it in their own language; you decide what the robot should do. \
    Understand driving commands even when phrased loosely or in any language \
    (for example Vietnamese or Chinese). Use the earlier turns as context so follow-ups \
    like "faster" or "again" make sense. \
    Turns spin the robot in place. When in doubt choose the safest action (stop). \
    Answer with ONLY a JSON object, no other text, with these fields: \
    "action": one of "forward", "backward", "left", "right", "stop", \
    "none" (pure conversation, no movement), \
    "lights_on", "lights_off", "sonar", "sing", "triangle", "circle", \
    "spin", "turn_around", "wiggle" (tricks: sing and turn around, drive a triangle, \
    drive a circle, spin 360 degrees, turn 180 degrees, turn left and right), \
    "indicator_left", "indicator_right", "indicator_hazard", "indicator_off" \
    (turn signals: left, right, both/hazard, off); \
    "speed": number 0.15 to 1.0 (default 0.75); \
    "duration_ms": how long to keep moving, 300 to 15000 (default 1200; use 300 for stop); \
    "say": a short spoken confirmation in the user's language, or "" for none.
    """

    private let config: XiaozhiConfig

    init(config: XiaozhiConfig = XiaozhiClient.loadConfig()) {
        self.config = config
    }

    /// Asks the LLM what to do with `text`. `history` carries the earlier user/assistant
    /// turns (plain text) so follow-ups work; the screen passes the last few exchanges.
    /// Completes on the main thread with either a command (plus a short spoken reply) or an
    /// error; the screen falls back to VoiceInterpreter on error.
    func interpret(text: String,
                   languageTag: String,
                   history: [(role: String, text: String)] = [],
                   completion: @escaping (Result<(command: VoiceCommand, say: String), Error>) -> Void) {
        struct ApiError: Error, LocalizedError {
            let message: String
            var errorDescription: String? { message }
        }

        guard let url = URL(string: config.endpoint) else {
            completion(.failure(ApiError(message: "bad endpoint")))
            return
        }

        let userContent = languageTag.isEmpty ? text : "[user language: \(languageTag)] \(text)"
        var messages: [[String: Any]] = [["role": "system", "content": Self.systemPrompt]]
        messages.append(contentsOf: history.map { ["role": $0.role, "content": $0.text] })
        messages.append(["role": "user", "content": userContent])
        let body: [String: Any] = [
            "model": config.model,
            "temperature": 0.2,
            "max_tokens": 200,
            "messages": messages,
        ]

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 15
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if !config.token.isEmpty {
            request.setValue("Bearer \(config.token)", forHTTPHeaderField: "Authorization")
        }
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)

        URLSession.shared.dataTask(with: request) { data, response, error in
            DispatchQueue.main.async {
                if let error = error {
                    completion(.failure(error))
                    return
                }
                guard let http = response as? HTTPURLResponse else {
                    completion(.failure(ApiError(message: "no response")))
                    return
                }
                guard (200..<300).contains(http.statusCode) else {
                    completion(.failure(ApiError(message: "HTTP \(http.statusCode)")))
                    return
                }
                guard let data = data else {
                    completion(.failure(ApiError(message: "empty reply")))
                    return
                }
                do {
                    let parsed = try Self.parseReply(data)
                    completion(.success(parsed))
                } catch {
                    completion(.failure(error))
                }
            }
        }.resume()
    }

    /// Pulls the command JSON out of a chat-completions response. Tolerates code fences and
    /// prose around the object, since small models sometimes add both despite instructions.
    private static func parseReply(_ data: Data) throws -> (VoiceCommand, String) {
        struct ApiError: Error, LocalizedError {
            let message: String
            var errorDescription: String? { message }
        }

        guard
            let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let choices = root["choices"] as? [[String: Any]],
            let message = choices.first?["message"] as? [String: Any],
            let content = message["content"] as? String
        else {
            throw ApiError(message: "unexpected reply shape")
        }

        guard
            let start = content.firstIndex(of: "{"),
            let end = content.lastIndex(of: "}"),
            start < end
        else {
            throw ApiError(message: "no JSON object in reply")
        }

        let jsonData = Data(content[start...end].utf8)
        guard
            let cmd = try JSONSerialization.jsonObject(with: jsonData) as? [String: Any],
            let actionName = cmd["action"] as? String
        else {
            throw ApiError(message: "command JSON not understood")
        }

        let speed = (cmd["speed"] as? NSNumber)?.floatValue ?? VoiceCommand.defaultSpeed
        let duration = (cmd["duration_ms"] as? NSNumber)?.intValue ?? VoiceCommand.defaultDurationMs
        let say = cmd["say"] as? String ?? ""

        return (VoiceCommand(action: parseAction(actionName),
                             speed: speed,
                             durationMs: duration,
                             rawText: actionName,
                             source: "xiaozhi"),
                say)
    }

    private static func parseAction(_ name: String) -> VoiceAction {
        switch name.lowercased().trimmingCharacters(in: .whitespaces) {
        case "forward", "go": return .forward
        case "backward", "back", "reverse": return .backward
        case "left", "turn_left": return .left
        case "right", "turn_right": return .right
        case "lights_on": return .lightsOn
        case "lights_off": return .lightsOff
        case "sonar": return .sonar
        case "sing": return .sing
        case "triangle": return .triangle
        case "circle": return .circle
        case "spin", "spin_360": return .spin360
        case "turn_around": return .turnAround
        case "wiggle": return .wiggle
        case "indicator_left": return .indicatorLeft
        case "indicator_right": return .indicatorRight
        case "indicator_hazard": return .indicatorHazard
        case "indicator_off": return .indicatorOff
        case "stop": return .stop
        case "none", "chat": return .chat
        default: return .stop
        }
    }
}
