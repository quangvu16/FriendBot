//
//  XiaozhiActivator.swift
//  OpenBot
//
//  Device login for the Xiaozhi platform, following the pairing flow of
//  github.com/PiSugar/whisplay-xiaozhi (protocol/ota_client.py) instead of asking the user
//  to copy an API key by hand.
//
//  The app registers itself as a Xiaozhi device at the OTA endpoint
//  (https://api.tenclass.net/xiaozhi/ota/), receives a verification code, and waits while the
//  user signs in at xiaozhi.me and binds the device with that code. Once bound, the platform
//  hands the credentials (WebSocket token, MQTT login) back over the same OTA channel and the
//  token becomes the saved API key.
//
//  Wire protocol (mirrors the reference client):
//    - POST <ota>/ with headers Device-Id, Client-Id, Activation-Version and body
//      {"application":{"version","elf_sha256"},"board":{"type","name","mac"}}. The response
//      carries activation {code,challenge} when unbound, or websocket {url,token} /
//      mqtt {...} credentials when bound.
//    - POST <ota>/activate with {"Payload":{"algorithm":"hmac-sha256","serial_number",
//      "challenge","hmac"}} where hmac is HMAC-SHA256 of the challenge under the device
//      fingerprint. HTTP 200 = bound, 202 = still pending, poll again.
//
//  The identity (device id, client id, HMAC fingerprint) is generated once per install and
//  kept in UserDefaults. The serial number follows the reference format
//  SN-<8 hex>-<mac> but hashes with SHA-256 rather than MD5 - it is an opaque identifier and
//  the server never verifies the digest. Mirrors XiaozhiActivator.java on Android.
//
//  Once the device is bound, the reply also carries the live-session credentials
//  (websocket.url / websocket.token). Those are persisted as act_ws_url / act_ws_token and
//  handed to the onBound listener so XiaozhiSession can connect - and later boots skip the
//  pairing step entirely.
//

import CryptoKit
import Foundation

final class XiaozhiActivator {

    /// onBound hands over the live-session credentials: (websocket.url, websocket.token).
    typealias Listener = (onCode: (String) -> Void,
                          onBound: (String, String) -> Void,
                          onError: (String) -> Void)

    static let defaultOtaUrl = "https://api.tenclass.net/xiaozhi/ota/"

    private static let deviceIdKey = "act_device_id"
    private static let clientIdKey = "act_client_id"
    private static let hmacKeyKey = "act_hmac_key"
    private static let tokenKey = "xiaozhi_token"
    static let wsUrlKey = "act_ws_url"
    static let wsTokenKey = "act_ws_token"

    private static let pollInterval: TimeInterval = 2.5
    private static let totalTimeout: TimeInterval = 5 * 60

    private let deviceId: String
    private let clientId: String
    private let hmacKey: String
    private let serialNumber: String

    private let queue = DispatchQueue(label: "xiaozhi-activator")
    private var cancelled = false

    init() {
        let defaults = UserDefaults.standard
        let deviceId = Self.savedDeviceId()
        let clientId = Self.savedClientId()
        let hmacKey = Self.getOrCreate(defaults, key: Self.hmacKeyKey) {
            Self.sha256Hex("\(deviceId)|\(clientId)|\(UUID().uuidString)")
        }
        self.deviceId = deviceId
        self.clientId = clientId
        self.hmacKey = hmacKey
        self.serialNumber = "SN-" + String(Self.sha256Hex(deviceId).prefix(8)) + "-" + deviceId
    }

    /// Starts the pairing flow. All callbacks arrive on the main thread.
    func start(_ listener: Listener) {
        queue.async { self.run(listener) }
    }

    func cancel() {
        cancelled = true
    }

    // MARK: - Flow

    private struct State {
        var bound = false
        var token = ""
        var wsUrl = ""
        var code = ""
        var challenge = ""
    }

    private func run(_ listener: Listener) {
        let deadline = Date().addingTimeInterval(Self.totalTimeout)
        do {
            let state = try parseState(checkVersion())
            if state.bound {
                finish(listener, state: state)
                return
            }
            DispatchQueue.main.async { listener.onCode(state.code) }

            if !state.challenge.isEmpty {
                try pollActivate(listener, challenge: state.challenge, deadline: deadline)
            } else {
                try pollCheckVersion(listener, lastCode: state.code, deadline: deadline)
            }
        } catch {
            DispatchQueue.main.async { listener.onError(error.localizedDescription) }
        }
    }

    /// v2 flow: sign the challenge and post it back until the server answers 200.
    private func pollActivate(_ listener: Listener, challenge: String, deadline: Date) throws {
        while !cancelled && Date() < deadline {
            let status = try activate(challenge: challenge)
            if status == "ok" {
                let state = try parseState(checkVersion())
                if state.bound {
                    finish(listener, state: state)
                    return
                }
            }
            usleep(UInt32(Self.pollInterval * 1_000_000))
        }
        timeout(listener)
    }

    /// v1 flow (code but no challenge): just re-check the version until credentials appear.
    private func pollCheckVersion(_ listener: Listener, lastCode: String, deadline: Date) throws {
        var lastCode = lastCode
        while !cancelled && Date() < deadline {
            usleep(UInt32(Self.pollInterval * 1_000_000))
            let state = try parseState(checkVersion())
            if state.bound {
                finish(listener, state: state)
                return
            }
            if state.code != lastCode {
                lastCode = state.code
                let code = lastCode
                DispatchQueue.main.async { listener.onCode(code) }
            }
        }
        timeout(listener)
    }

    private func finish(_ listener: Listener, state: State) {
        guard !state.token.isEmpty else {
            DispatchQueue.main.async { listener.onError("no token in server reply") }
            return
        }
        let defaults = UserDefaults.standard
        defaults.set(state.token, forKey: Self.tokenKey)
        // Keep the live-session credentials too: the websocket session uses them directly and
        // their presence is what lets the next boot skip pairing (spec: "subsequent boots
        // skip the pairing step").
        defaults.set(state.wsUrl, forKey: Self.wsUrlKey)
        defaults.set(state.token, forKey: Self.wsTokenKey)
        let wsUrl = state.wsUrl
        let wsToken = state.token
        DispatchQueue.main.async { listener.onBound(wsUrl, wsToken) }
    }

    private func timeout(_ listener: Listener) {
        guard !cancelled else { return }
        DispatchQueue.main.async { listener.onError("timed out waiting for binding") }
    }

    // MARK: - Network

    private struct ApiError: Error, LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    private func parseState(_ payload: String) throws -> State {
        var state = State()
        guard
            let data = payload.data(using: .utf8),
            let root = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        else {
            throw ApiError(message: "unexpected server reply")
        }

        if let ws = root["websocket"] as? [String: Any] {
            state.token = ws["token"] as? String ?? ""
            state.wsUrl = ws["url"] as? String ?? ""
        }
        let mqttUser = (root["mqtt"] as? [String: Any])?["username"] as? String ?? ""
        state.bound = !state.token.isEmpty || !mqttUser.isEmpty

        if let activation = root["activation"] as? [String: Any] {
            state.code = activation["code"] as? String ?? ""
            state.challenge = activation["challenge"] as? String ?? ""
            // A code/challenge means the device is not bound yet, even if other keys are present.
            if !state.code.isEmpty || !state.challenge.isEmpty {
                state.bound = false
            }
        }
        return state
    }

    private func checkVersion() throws -> String {
        let body: [String: Any] = [
            "application": ["version": Self.appVersion, "elf_sha256": hmacKey],
            "board": ["type": "openbot-phone", "name": "FriendBot iOS", "mac": deviceId],
        ]
        return try postOta(url: Self.defaultOtaUrl, body: body)
    }

    private func activate(challenge: String) throws -> String {
        let payload: [String: Any] = [
            "algorithm": "hmac-sha256",
            "serial_number": serialNumber,
            "challenge": challenge,
            "hmac": Self.hmacSha256Hex(key: hmacKey, message: challenge),
        ]
        let (code, _) = try post(url: Self.defaultOtaUrl + "activate", body: ["Payload": payload])
        switch code {
        case 200: return "ok"
        case 202: return "pending"
        default:
            NSLog("Xiaozhi activate returned HTTP %d", code)
            return "error"
        }
    }

    private func postOta(url: String, body: [String: Any]) throws -> String {
        let (status, text) = try post(url: url, body: body)
        guard (200..<300).contains(status) else {
            throw ApiError(message: "HTTP \(status)")
        }
        return text
    }

    private func post(url: String, body: [String: Any]) throws -> (Int, String) {
        guard let url = URL(string: url) else {
            throw ApiError(message: "bad OTA URL")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 15
        request.setValue(deviceId, forHTTPHeaderField: "Device-Id")
        request.setValue(clientId, forHTTPHeaderField: "Client-Id")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("ios/openbot-voice-" + Self.appVersion, forHTTPHeaderField: "User-Agent")
        request.setValue(Locale.current.identifier, forHTTPHeaderField: "Accept-Language")
        request.setValue(Self.appVersion, forHTTPHeaderField: "Activation-Version")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let semaphore = DispatchSemaphore(value: 0)
        var result: (Int, String) = (0, "")
        var failure: Error?
        URLSession.shared.dataTask(with: request) { data, response, error in
            if let error = error {
                failure = error
            } else {
                let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                result = (status, data.flatMap { String(data: $0, encoding: .utf8) } ?? "{}")
            }
            semaphore.signal()
        }.resume()
        semaphore.wait()
        if let failure = failure {
            throw failure
        }
        return result
    }

    // MARK: - Identity

    /// Websocket credentials saved when the device became bound; empty before pairing.
    static var savedWsUrl: String { UserDefaults.standard.string(forKey: wsUrlKey) ?? "" }

    static var savedWsToken: String { UserDefaults.standard.string(forKey: wsTokenKey) ?? "" }

    /// True once pairing produced session credentials (spec: saved credentials skip pairing).
    static var isBound: Bool { !savedWsToken.isEmpty }

    /// Identity shared by the OTA handshake and the websocket headers; created on first use.
    static func savedDeviceId() -> String {
        getOrCreate(UserDefaults.standard, key: deviceIdKey) { newDeviceId() }
    }

    static func savedClientId() -> String {
        getOrCreate(UserDefaults.standard, key: clientIdKey) { UUID().uuidString }
    }

    private static func getOrCreate(_ defaults: UserDefaults, key: String, _ factory: () -> String) -> String {
        if let value = defaults.string(forKey: key), !value.isEmpty {
            return value
        }
        let value = factory()
        defaults.set(value, forKey: key)
        return value
    }

    private static var appVersion: String {
        (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "1.0"
    }

    /// Random locally-administered MAC-shaped id; the platform treats Device-Id as opaque.
    private static func newDeviceId() -> String {
        var bytes = (0..<6).map { _ in UInt8.random(in: 0...255) }
        bytes[0] = (bytes[0] & 0xFC) | 0x02
        return bytes.map { String(format: "%02x", $0) }.joined(separator: ":")
    }

    private static func hmacSha256Hex(key: String, message: String) -> String {
        let code = HMAC<SHA256>.authenticationCode(
            for: Data(message.utf8), using: SymmetricKey(data: Data(key.utf8)))
        return code.map { String(format: "%02x", $0) }.joined()
    }

    private static func sha256Hex(_ text: String) -> String {
        SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}
