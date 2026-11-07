//
//  XiaozhiSession.swift
//  OpenBot
//
//  Persistent XiaoZhi websocket session: the phone acting as a real XiaoZhi client.
//
//  Protocol per 78/xiaozhi-esp32 docs/websocket.md + mcp-protocol.md and the
//  PiSugar/whisplay-xiaozhi client. The app dials the endpoint issued at pairing
//  (act_ws_url, fallback wss://api.tenclass.net/xiaozhi/v1/) with the bearer token
//  (act_ws_token), says hello with mcp enabled, and then both carries the voice
//  channel (mic opus uplink, TTS opus downlink) and serves MCP tools/call so the
//  assistant can drive the robot. Robot execution is delegated back to the screen
//  (toolHandler) so every command flows through the same safe drive path as the
//  quick buttons.
//
//  Wire protocol summary (hello says "version": 1 -> binary frames are RAW OPUS
//  both directions, no framing header):
//    - out: {"type":"hello","version":1,"transport":"websocket","features":{"mcp":true},
//           "audio_params":{"format":"opus","sample_rate":16000,"channels":1,
//           "frame_duration":60}}
//    - in:  {"type":"hello","transport":"websocket","session_id":"...",
//           "audio_params":{"sample_rate":...}} -> store session_id + playback rate
//    - out: {"session_id":"...","type":"listen","state":"start","mode":"auto"|"manual"}
//           ("auto" = hands-free: keep streaming, server VAD ends utterances;
//            "manual" = push-to-talk: tap to start/stop)
//           then binary raw opus (60 ms / 960-sample 16 kHz mono PCM16 frames)
//    - out: {"session_id":"...","type":"listen","state":"stop"}
//    - out: {"session_id":"...","type":"abort","reason":"wake_word_detected"} (barge-in)
//    - out: {"session_id":"...","type":"listen","state":"detect","text":"<utterance>"}
//    - in:  binary -> raw opus TTS audio (decode at the hello's sample_rate)
//    - in:  {"type":"stt","text":...} / {"type":"tts","state":"sentence_start","text":...} /
//           {"type":"tts","state":"start"|"stop"} / {"type":"llm","emotion":...} /
//           {"type":"mcp","payload":{json-rpc}} / {"type":"goodbye"}
//    - out: {"session_id":"...","type":"mcp","payload":{json-rpc}}
//
//  Unexpected drops reconnect with 1 s / 2 s / 5 s backoff; an intentional disconnect
//  (screen closed) never reconnects. All callbacks arrive on the main queue.
//

import Foundation

/// Connection state as shown by the screen's status line.
enum XiaozhiSessionState {
    case idle        // not connected (or between reconnect attempts)
    case connecting  // socket open, waiting for the server hello
    case ready        // hello done, session_id assigned
}

final class XiaozhiSession {

    static let defaultWsUrl = "wss://api.tenclass.net/xiaozhi/v1/"

    // MARK: Callbacks (main queue)

    var onStateChange: ((XiaozhiSessionState) -> Void)?
    /// Server-side recognition of the current utterance: update the "You said" line.
    var onStt: ((String) -> Void)?
    /// One assistant sentence (tts sentence_start): show it in the conversation list.
    var onTtsSentence: ((String) -> Void)?
    /// Assistant TTS playback state (tts start/stop).
    var onTtsState: ((Bool) -> Void)?
    /// One raw opus TTS packet from the server, with the playback sample rate to
    /// decode it at (the server hello's audio_params.sample_rate).
    var onBinaryAudio: ((Data, Int) -> Void)?
    /// Emotion hint from llm frames (`emotion` and `text` fields, e.g. ("😀", "") or
    /// ("happy", "")): the emotional face's primary source.
    var onLlmEmotion: ((String, String) -> Void)?
    /// Executes a tools/call on the robot on the main queue. Returns false for unknown tools.
    var toolHandler: ((String, [String: Any]) -> Bool)?
    /// Answers `self.camera.take_photo` (phone camera + vision explain); nil = not supported.
    /// The completion takes (photoJson, errorMessage) and is called exactly once per request.
    var photoHandler: ((String, @escaping (String?, String?) -> Void) -> Void)?

    // MARK: State

    private(set) var state: XiaozhiSessionState = .idle
    private(set) var sessionId = ""
    /// Playback sample rate announced by the server hello (audio_params.sample_rate);
    /// 16000 when the server says nothing. The TTS opus stream decodes at this rate.
    private(set) var playbackSampleRate = 16000
    var isReady: Bool { state == .ready }

    private var webSocket: URLSessionWebSocketTask?
    private var urlSession: URLSession?
    private var intentionalClose = false
    private var closeHandled = true
    private var reconnectAttempts = 0
    private var reconnectWork: DispatchWorkItem?

    // MARK: Lifecycle

    /// Opens the saved session. No-op while already connected or without saved credentials.
    func connect() {
        guard state == .idle else { return }
        intentionalClose = false
        openSocket()
    }

    /// Tears the session down for good (screen closed); never reconnects afterwards.
    func disconnect() {
        intentionalClose = true
        reconnectWork?.cancel()
        reconnectWork = nil
        sessionId = ""
        closeHandled = true
        let task = webSocket
        webSocket = nil
        task?.cancel(with: .goingAway, reason: nil)
        urlSession?.invalidateAndCancel()
        urlSession = nil
        setState(.idle)
    }

    // MARK: Socket

    private func openSocket() {
        reconnectWork?.cancel()
        reconnectWork = nil
        if let old = webSocket {
            // Replacing a socket (retry after a failed open): its late close callback is expected.
            closeHandled = true
            webSocket = nil
            old.cancel(with: .goingAway, reason: nil)
        }
        urlSession?.invalidateAndCancel()

        let wsUrl = XiaozhiActivator.savedWsUrl.isEmpty ? Self.defaultWsUrl : XiaozhiActivator.savedWsUrl
        let token = XiaozhiActivator.savedWsToken
        guard !token.isEmpty, let url = URL(string: wsUrl) else {
            setState(.idle)
            return
        }
        setState(.connecting)

        var request = URLRequest(url: url)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("1", forHTTPHeaderField: "Protocol-Version")
        request.setValue(XiaozhiActivator.savedDeviceId(), forHTTPHeaderField: "Device-Id")
        request.setValue(XiaozhiActivator.savedClientId(), forHTTPHeaderField: "Client-Id")

        let session = URLSession(configuration: .default)
        urlSession = session
        let task = session.webSocketTask(with: request)
        closeHandled = false
        webSocket = task
        task.resume()
        sendHello()
        receiveLoop(task)
    }

    private func receiveLoop(_ task: URLSessionWebSocketTask) {
        task.receive { [weak self] result in
            DispatchQueue.main.async {
                // Only the live socket may drive state; stale callbacks from a replaced one
                // are dropped here.
                guard let self = self, self.webSocket === task else { return }
                switch result {
                case .failure:
                    self.handleClose()
                case .success(let message):
                    switch message {
                    case .string(let text):
                        self.handle(text: text)
                    case .data(let data):
                        // Hello v1: binary frames are raw opus TTS audio (no header).
                        self.onBinaryAudio?(data, self.playbackSampleRate)
                    @unknown default:
                        break
                    }
                    self.receiveLoop(task)
                }
            }
        }
    }

    /// Unexpected drop: retry with 1 s, 2 s, then 5 s backoff. Intentional closes stop here.
    private func handleClose() {
        guard !closeHandled else { return }
        closeHandled = true
        sessionId = ""
        webSocket = nil
        urlSession?.invalidateAndCancel()
        urlSession = nil
        setState(.idle)
        guard !intentionalClose else { return }
        let delays: [TimeInterval] = [1, 2, 5]
        let delay = delays[min(reconnectAttempts, delays.count - 1)]
        reconnectAttempts += 1
        let work = DispatchWorkItem { [weak self] in self?.openSocket() }
        reconnectWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    // MARK: Outgoing

    /// Text-in: hands a pre-recognized utterance to the assistant (xiaozhi listen/detect).
    func sendListen(text: String) {
        guard isReady else { return }
        send(json: ["session_id": sessionId, "type": "listen", "state": "detect", "text": text])
    }

    /// `listen` mode: hands-free — keep streaming, the server VAD ends each utterance.
    static let modeAuto = "auto"
    /// `listen` mode: push-to-talk — the tap starts and stops each utterance.
    static let modeManual = "manual"

    /// Listening start: the microphone is streaming now. Binary opus frames follow until
    /// sendListenStop() (or the server's tts start). `mode` is `modeAuto` (hands-free,
    /// keep streaming) or `modeManual` (push-to-talk, tap to start/stop).
    func sendListenStart(mode: String) {
        guard isReady else { return }
        send(json: ["session_id": sessionId, "type": "listen", "state": "start",
                    "mode": mode == Self.modeAuto ? Self.modeAuto : Self.modeManual])
    }

    /// Push-to-talk released (or the server began TTS): close the listen stream.
    func sendListenStop() {
        guard isReady else { return }
        send(json: ["session_id": sessionId, "type": "listen", "state": "stop"])
    }

    /// Barge-in: interrupt the assistant's current reply before speaking over it.
    func sendAbort() {
        guard isReady else { return }
        send(json: ["session_id": sessionId, "type": "abort", "reason": "wake_word_detected"])
    }

    /// Sends one raw opus packet as a binary websocket frame (hello v1: no framing
    /// header). Safe to call from the audio thread; frames keep their call order.
    func sendBinary(_ data: Data) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self, let task = self.webSocket else { return }
            task.send(.data(data)) { error in
                if let error = error {
                    NSLog("Xiaozhi binary send failed: %@", error.localizedDescription)
                }
            }
        }
    }

    private func sendHello() {
        send(json: [
            "type": "hello",
            "version": 1,
            "transport": "websocket",
            "features": ["mcp": true],
            "audio_params": [
                "format": "opus",
                "sample_rate": 16000,
                "channels": 1,
                "frame_duration": 60,
            ],
        ])
    }

    /// MCP envelope: the JSON-RPC payload wrapped for the session.
    private func sendMcp(_ payload: [String: Any]) {
        send(json: ["session_id": sessionId, "type": "mcp", "payload": payload])
    }

    private func send(json: [String: Any]) {
        guard
            let task = webSocket,
            let data = try? JSONSerialization.data(withJSONObject: json),
            let text = String(data: data, encoding: .utf8)
        else { return }
        task.send(.string(text)) { error in
            if let error = error {
                NSLog("Xiaozhi send failed: %@", error.localizedDescription)
            }
        }
    }

    // MARK: Incoming

    private func handle(text: String) {
        guard
            let data = text.data(using: .utf8),
            let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return }

        switch root["type"] as? String ?? "" {
        case "hello":
            sessionId = root["session_id"] as? String ?? ""
            // TTS opus decodes at the server's playback rate (often 24000); 16000
            // when the hello carries no audio_params.sample_rate.
            let params = root["audio_params"] as? [String: Any]
            let rate = (params?["sample_rate"] as? NSNumber)?.intValue ?? 16000
            playbackSampleRate = rate > 0 ? rate : 16000
            reconnectAttempts = 0
            setState(.ready)
        case "stt":
            let heard = root["text"] as? String ?? ""
            onStt?(heard)
        case "tts":
            switch root["state"] as? String ?? "" {
            case "sentence_start":
                let sentence = root["text"] as? String ?? ""
                onTtsSentence?(sentence)
            case "start":
                onTtsState?(true)
            case "stop":
                onTtsState?(false)
            default:
                break
            }
        case "llm":
            // Emotion stream ({"type":"llm","emotion":"😀"} and friends): drive the
            // emotional face; never let an odd payload crash the session.
            let emotion = root["emotion"] as? String ?? ""
            let hint = root["text"] as? String ?? ""
            if !emotion.isEmpty || !hint.isEmpty {
                onLlmEmotion?(emotion, hint)
            }
        case "mcp":
            if let payload = root["payload"] as? [String: Any] {
                handleMcp(payload)
            }
        case "goodbye":
            handleClose()
        default:
            break
        }
    }

    /// Explain endpoint for camera photos; the server pushes it in the MCP initialize.
    private(set) var visionExplainUrl = ""
    private(set) var visionExplainToken = ""

    // MARK: MCP (robot as device tools)

    /// The server's MCP `initialize` may carry `params.capabilities.vision = {url, token}` —
    /// the image explain service for camera photos (the S3CAM firmware's SetExplainUrl).
    /// Without it the photo tool answers like the firmware: "Image explain URL or token is not set".
    private func parseVisionCapability(_ payload: [String: Any]) {
        guard let params = payload["params"] as? [String: Any],
              let capabilities = params["capabilities"] as? [String: Any],
              let vision = capabilities["vision"] as? [String: Any],
              let url = vision["url"] as? String, !url.isEmpty else {
            return
        }
        visionExplainUrl = url
        visionExplainToken = vision["token"] as? String ?? ""
    }

    private func handleMcp(_ payload: [String: Any]) {
        // JSON-RPC 2.0: messages without an id are notifications and are ignored.
        guard let id = payload["id"] else { return }
        switch payload["method"] as? String ?? "" {
        case "initialize":
            parseVisionCapability(payload)
            sendMcp([
                "jsonrpc": "2.0",
                "id": id,
                "result": [
                    "protocolVersion": "2024-11-05",
                    "capabilities": ["tools": [String: Any]()],
                    "serverInfo": ["name": "FriendBot", "version": Self.appVersion],
                ],
            ])
        case "tools/list":
            sendMcp([
                "jsonrpc": "2.0",
                "id": id,
                "result": ["tools": Self.tools, "nextCursor": ""],
            ])
        case "tools/call":
            let params = payload["params"] as? [String: Any] ?? [:]
            let name = params["name"] as? String ?? ""
            let args = params["arguments"] as? [String: Any] ?? [:]
            if name == Self.photoToolName {
                // Async tool: the camera + vision answer arrives later via the photoHandler.
                handlePhotoCall(id: id, question: args["question"] as? String ?? "")
                return
            }
            if toolHandler?(name, args) ?? false {
                sendMcp([
                    "jsonrpc": "2.0",
                    "id": id,
                    "result": [
                        "content": [["type": "text", "text": "true"]],
                        "isError": false,
                    ],
                ])
            } else {
                sendMcp([
                    "jsonrpc": "2.0",
                    "id": id,
                    "error": ["code": -32602, "message": "Unknown tool or argument: \(name)"],
                ])
            }
        default:
            break
        }
    }

    /// The robot tools, exactly as the assistant expects them (mcp-protocol.md), plus the
    /// camera tool of the KS5028 S3CAM firmware (take a photo with the phone camera and explain it).
    private static let tools: [[String: Any]] = [
        [
            "name": "self.robot.move",
            "description": "Move the two-wheel differential-drive robot. Turns spin the robot in place. Automatically stops after duration_ms.",
            "inputSchema": [
                "type": "object",
                "properties": [
                    "direction": ["type": "string", "enum": ["forward", "backward", "left", "right"]],
                    "speed": ["type": "number", "minimum": 0.15, "maximum": 1.0, "default": 0.75],
                    "duration_ms": ["type": "integer", "minimum": 300, "maximum": 15000, "default": 1200],
                ],
                "required": ["direction"],
            ],
        ],
        [
            "name": "self.robot.stop",
            "description": "Stop the robot immediately.",
            "inputSchema": ["type": "object", "properties": [String: Any]()],
        ],
        [
            "name": "self.robot.led",
            "description": "Turn the robot's front/back lights on or off. Use when the user asks for the lights or the LEDs.",
            "inputSchema": [
                "type": "object",
                "properties": [
                    "state": ["type": "string", "enum": ["on", "off"]],
                ],
                "required": ["state"],
            ],
        ],
        [
            "name": "self.robot.indicator",
            "description": "Turn the robot's turn-signal indicator LEDs on for a left turn, a right turn, both (hazard lights), or off. Use when the user asks for a signal or blinker.",
            "inputSchema": [
                "type": "object",
                "properties": [
                    "state": ["type": "string", "enum": ["left", "right", "both", "off"]],
                ],
                "required": ["state"],
            ],
        ],
        [
            "name": "self.robot.sonar",
            "description": "Read the robot's sonar distance sensor and answer with the distance in centimetres. Use when the user asks how far away something is or asks for the sonar/distance.",
            "inputSchema": ["type": "object", "properties": [String: Any]()],
        ],
        [
            "name": "self.robot.trick",
            "description": "Make the robot perform a trick: 'sing' (sing and turn around), 'triangle' (drive a triangle), 'circle' (drive a circle), 'spin' (360 degrees in place), 'turn_around' (180 degrees in place), 'wiggle' (turn left and right).",
            "inputSchema": [
                "type": "object",
                "properties": [
                    "name": ["type": "string", "enum": ["sing", "triangle", "circle", "spin", "turn_around", "wiggle"]],
                ],
                "required": ["name"],
            ],
        ],
        [
            "name": photoToolName,
            "description": "Take a photo with the phone camera and explain it. Use this tool after the user asks you to see something or asks about the environment. `question`: The question that you want to ask about the photo. Return: A JSON object that provides the photo information.",
            "inputSchema": [
                "type": "object",
                "properties": [
                    "question": ["type": "string", "description": "The question that you want to ask about the photo."],
                ],
            ],
        ],
    ]

    private static let photoToolName = "self.camera.take_photo"

    /// Async tools/call: answers the photo JSON-RPC exactly once, when the camera and the
    /// vision model are done (a 60 s guard covers a stuck camera).
    private func handlePhotoCall(id: Any, question: String) {
        var answered = false
        let done: (String?, String?) -> Void = { [weak self] json, error in
            DispatchQueue.main.async {
                guard let self = self, !answered else { return }
                answered = true
                if let json = json, !json.isEmpty {
                    self.sendMcp([
                        "jsonrpc": "2.0",
                        "id": id,
                        "result": ["content": [["type": "text", "text": json]], "isError": false],
                    ])
                } else {
                    let message = (error?.isEmpty == false) ? error! : "Failed to capture photo"
                    self.sendMcp([
                        "jsonrpc": "2.0",
                        "id": id,
                        "error": ["code": -32000, "message": message],
                    ])
                }
            }
        }
        if let handler = photoHandler {
            handler(question, done)
        } else {
            done(nil, "Camera is not available on this screen")
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 60) {
            done(nil, "Failed to capture photo")
        }
    }

    private static var appVersion: String {
        (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "1.0"
    }

    private func setState(_ newState: XiaozhiSessionState) {
        guard state != newState else { return }
        state = newState
        onStateChange?(newState)
    }
}
