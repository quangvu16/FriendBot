// Mac microphone → 16 kHz mono → 60 ms Opus frames (same format as the phone apps).

import AVFoundation
import COpus

final class Mic {
    static let rate = 16_000.0
    static let frame = 960 // 60 ms

    /// Called on the audio thread with one Opus frame and its loudness (0…1).
    var onFrame: ((Data, Float) -> Void)?

    private let engine = AVAudioEngine()
    private var converter: AVAudioConverter?
    private var encoder: OpaquePointer?
    private var pending: [Int16] = []
    private(set) var running = false

    static func askPermission(_ done: @escaping (Bool) -> Void) {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized: done(true)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .audio) { ok in DispatchQueue.main.async { done(ok) } }
        default: done(false)
        }
    }

    func start() throws {
        guard !running else { return }
        var err: Int32 = 0
        encoder = opus_encoder_create(Int32(Mic.rate), 1, Int32(OPUS_APPLICATION_VOIP), &err)
        guard err == OPUS_OK, encoder != nil else {
            throw NSError(domain: "FriendBotMic", code: Int(err),
                          userInfo: [NSLocalizedDescriptionKey: "Opus encoder failed (\(err))"])
        }
        let input = engine.inputNode
        let inFormat = input.outputFormat(forBus: 0)
        guard let outFormat = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: Mic.rate,
                                            channels: 1, interleaved: true),
              let conv = AVAudioConverter(from: inFormat, to: outFormat) else {
            throw NSError(domain: "FriendBotMic", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "No microphone input"])
        }
        converter = conv
        pending.removeAll()
        input.installTap(onBus: 0, bufferSize: 4096, format: inFormat) { [weak self] buffer, _ in
            self?.convert(buffer, outFormat)
        }
        engine.prepare()
        try engine.start()
        running = true
    }

    func stop() {
        guard running else { return }
        running = false
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        if let e = encoder { opus_encoder_destroy(e) }
        encoder = nil
        converter = nil
    }

    private func convert(_ buffer: AVAudioPCMBuffer, _ outFormat: AVAudioFormat) {
        guard let conv = converter else { return }
        let ratio = Mic.rate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 64
        guard let out = AVAudioPCMBuffer(pcmFormat: outFormat, frameCapacity: capacity) else { return }
        var given = false
        var error: NSError?
        conv.convert(to: out, error: &error) { _, status in
            if given { status.pointee = .noDataNow; return nil }
            given = true
            status.pointee = .haveData
            return buffer
        }
        guard error == nil, let ch = out.int16ChannelData else { return }
        pending.append(contentsOf: UnsafeBufferPointer(start: ch[0], count: Int(out.frameLength)))
        while pending.count >= Mic.frame {
            let pcm = Array(pending[0..<Mic.frame])
            pending.removeFirst(Mic.frame)
            encode(pcm)
        }
    }

    private func encode(_ pcm: [Int16]) {
        guard let e = encoder else { return }
        var sum: Float = 0
        for s in pcm { let f = Float(s) / 32768; sum += f * f }
        let rms = (sum / Float(pcm.count)).squareRoot()
        var out = [UInt8](repeating: 0, count: 1500)
        let n = opus_encode(e, pcm, Int32(Mic.frame), &out, Int32(out.count))
        if n > 0 { onFrame?(Data(out[0..<Int(n)]), rms) }
    }
}
