//
//  RemoteMicController.swift
//  OpenBot
//
//  Remote Mic page: this phone's microphone feeds the Voice Control page on the phone
//  that holds the robot link. The mic is captured here, opus-encoded (16 kHz mono, 60 ms
//  frames) and streamed over the WiFi voice relay (VoiceRelayClient) into that page's
//  Xiaozhi session — so the second phone needs no Xiaozhi pairing. The conversation and
//  Xiaozhi's replies stay on the host phone; only the microphone travels.
//
//  Tap the orb to open/close the stream ({"t":"mic","on":...} + frame lines). The host
//  answers "micack" saying whether its voice control page is actually listening. The
//  microphone is released on exit so it is never held while the app is in the background.
//

import AVFoundation
import UIKit

final class RemoteMicController: UIViewController {

    private let relayClient = VoiceRelayClient.shared

    // MARK: UI

    private let connectionCapsule = UIView()
    private let connectionLabel = UILabel()
    private let micButton = UIButton(type: .system)
    private let stateLabel = UILabel()
    private let waveformView = VoiceWaveformView()

    // MARK: Capture state

    private let audioEngine = AVAudioEngine()
    private var micConverter: AVAudioConverter?
    private var opusEncoder: OpusCodecEncoder?
    private var pcmAccumulator: [Int16] = []
    private var tapInstalled = false
    private var streaming = false
    /// The host's voice control page confirmed it is hearing the microphone.
    private var micAckLive = false
    /// True while the relay link is up (detects reconnects to re-announce the stream).
    private var wasConnected = false

    // MARK: Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemGroupedBackground
        layout()
        relayClient.onStateChange = { [weak self] in self?.updateStatus() }
        relayClient.onMicAck = { [weak self] live in
            self?.micAckLive = live
            self?.updateStatus()
        }
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        relayClient.start()
        updateStatus()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        // Never hold the microphone while the app is in the background.
        stopStreaming()
        relayClient.onMicAck = nil
        relayClient.onStateChange = nil
    }

    // MARK: Layout

    private func layout() {
        let bigTitle = UILabel()
        bigTitle.text = L10n.string("tile_remote_mic")
        bigTitle.font = .systemFont(ofSize: 32, weight: .bold)
        bigTitle.textColor = .label

        // Connection capsule: the status line, tappable to pick the target robot.
        let connectionIcon = UIImageView(image: UIImage(systemName: "antenna.radiowaves.left.and.right"))
        connectionIcon.tintColor = .secondaryLabel
        connectionIcon.contentMode = .scaleAspectFit
        connectionIcon.translatesAutoresizingMaskIntoConstraints = false
        connectionIcon.widthAnchor.constraint(equalToConstant: 14).isActive = true
        connectionIcon.heightAnchor.constraint(equalToConstant: 14).isActive = true
        connectionIcon.setContentHuggingPriority(.required, for: .horizontal)
        connectionLabel.font = .systemFont(ofSize: 12, weight: .semibold)
        connectionLabel.textColor = .secondaryLabel
        let chevron = UIImageView(image: UIImage(systemName: "chevron.up.chevron.down"))
        chevron.tintColor = .secondaryLabel
        chevron.contentMode = .scaleAspectFit
        chevron.translatesAutoresizingMaskIntoConstraints = false
        chevron.widthAnchor.constraint(equalToConstant: 8).isActive = true
        chevron.heightAnchor.constraint(equalToConstant: 12).isActive = true
        chevron.setContentHuggingPriority(.required, for: .horizontal)
        let capsule = connectionCapsule
        capsule.backgroundColor = .systemGray5
        capsule.layer.cornerRadius = 999
        let capsuleInner = UIStackView(arrangedSubviews: [connectionIcon, connectionLabel, chevron])
        capsuleInner.axis = .horizontal
        capsuleInner.spacing = 6
        capsuleInner.alignment = .center
        capsuleInner.translatesAutoresizingMaskIntoConstraints = false
        capsule.addSubview(capsuleInner)
        NSLayoutConstraint.activate([
            capsuleInner.topAnchor.constraint(equalTo: capsule.topAnchor, constant: 6),
            capsuleInner.bottomAnchor.constraint(equalTo: capsule.bottomAnchor, constant: -6),
            capsuleInner.leadingAnchor.constraint(equalTo: capsule.leadingAnchor, constant: 12),
            capsuleInner.trailingAnchor.constraint(equalTo: capsule.trailingAnchor, constant: -12),
        ])
        capsule.isUserInteractionEnabled = true
        capsule.accessibilityLabel = L10n.string("voice_robot_picker_title")
        capsule.accessibilityTraits = .button
        capsule.addGestureRecognizer(UITapGestureRecognizer(target: self,
                                                            action: #selector(robotPickerTapped)))

        // Help card: what this page is for, in the voice screen's card style.
        let helpLabel = UILabel()
        helpLabel.text = L10n.string("remote_mic_help")
        helpLabel.font = .systemFont(ofSize: 13)
        helpLabel.textColor = .secondaryLabel
        helpLabel.numberOfLines = 0
        let helpCard = UIView()
        helpCard.backgroundColor = .secondarySystemGroupedBackground
        helpCard.layer.cornerRadius = 14
        helpLabel.translatesAutoresizingMaskIntoConstraints = false
        helpCard.addSubview(helpLabel)
        NSLayoutConstraint.activate([
            helpLabel.topAnchor.constraint(equalTo: helpCard.topAnchor, constant: 14),
            helpLabel.bottomAnchor.constraint(equalTo: helpCard.bottomAnchor, constant: -14),
            helpLabel.leadingAnchor.constraint(equalTo: helpCard.leadingAnchor, constant: 14),
            helpLabel.trailingAnchor.constraint(equalTo: helpCard.trailingAnchor, constant: -14),
        ])

        // Center group: the mic orb, its state, and the voice level waveform.
        var micConfig = UIButton.Configuration.filled()
        micConfig.image = UIImage(systemName: "mic.fill")
        micConfig.cornerStyle = .capsule
        micConfig.baseBackgroundColor = VoiceFaceView.accent
        micConfig.baseForegroundColor = .white
        micConfig.preferredSymbolConfigurationForImage =
            UIImage.SymbolConfiguration(pointSize: 26, weight: .semibold)
        micButton.configuration = micConfig
        micButton.translatesAutoresizingMaskIntoConstraints = false
        micButton.widthAnchor.constraint(equalToConstant: 72).isActive = true
        micButton.heightAnchor.constraint(equalToConstant: 72).isActive = true
        micButton.accessibilityLabel = L10n.string("remote_mic_tap_to_start")
        micButton.addTarget(self, action: #selector(micTapped), for: .touchUpInside)

        stateLabel.text = L10n.string("remote_mic_tap_to_start")
        stateLabel.font = .systemFont(ofSize: 15, weight: .semibold)
        stateLabel.textColor = .label
        stateLabel.textAlignment = .center

        waveformView.translatesAutoresizingMaskIntoConstraints = false
        waveformView.widthAnchor.constraint(equalToConstant: 220).isActive = true
        waveformView.heightAnchor.constraint(equalToConstant: 36).isActive = true

        let centerStack = UIStackView(arrangedSubviews: [micButton, stateLabel, waveformView])
        centerStack.axis = .vertical
        centerStack.spacing = 14
        centerStack.alignment = .center

        let centerWrap = UIView()
        centerStack.translatesAutoresizingMaskIntoConstraints = false
        centerWrap.addSubview(centerStack)
        NSLayoutConstraint.activate([
            centerStack.centerXAnchor.constraint(equalTo: centerWrap.centerXAnchor),
            centerStack.centerYAnchor.constraint(equalTo: centerWrap.centerYAnchor),
            centerStack.leadingAnchor.constraint(greaterThanOrEqualTo: centerWrap.leadingAnchor),
            centerStack.trailingAnchor.constraint(lessThanOrEqualTo: centerWrap.trailingAnchor),
        ])

        let stack = UIStackView(arrangedSubviews: [bigTitle, capsule, helpCard, centerWrap])
        stack.axis = .vertical
        stack.spacing = 12
        stack.alignment = .leading
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            stack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -12),
            helpCard.widthAnchor.constraint(equalTo: stack.widthAnchor),
            centerWrap.widthAnchor.constraint(equalTo: stack.widthAnchor),
            centerWrap.heightAnchor.constraint(equalTo: stack.heightAnchor, multiplier: 0.55),
        ])
    }

    // MARK: Streaming

    @objc private func micTapped() {
        if streaming {
            stopStreaming()
            return
        }
        guard relayClient.isHostConnected else {
            alert(L10n.string("voice_relay_searching"))
            return
        }
        AVAudioSession.sharedInstance().requestRecordPermission { [weak self] granted in
            DispatchQueue.main.async {
                guard let self = self else { return }
                guard granted else {
                    self.alert(L10n.string("voice_mic_permission_denied"))
                    return
                }
                self.startStreaming()
            }
        }
    }

    private func startStreaming() {
        guard !streaming, relayClient.isHostConnected else { return }
        do {
            // Record only: the conversation and replies play on the host phone.
            try AVAudioSession.sharedInstance().setCategory(.record, mode: .default)
            try AVAudioSession.sharedInstance().setActive(true, options: .notifyOthersOnDeactivation)
        } catch {
            alert(error.localizedDescription)
            return
        }
        let input = audioEngine.inputNode
        let inputFormat = input.outputFormat(forBus: 0)
        guard
            let pcmFormat = AVAudioFormat(commonFormat: .pcmFormatInt16,
                                          sampleRate: 16000, channels: 1, interleaved: false),
            let converter = AVAudioConverter(from: inputFormat, to: pcmFormat)
        else {
            alert(L10n.string("remote_mic_mic_unavailable"))
            return
        }
        micConverter = converter
        opusEncoder = OpusCodecEncoder(sampleRate: 16000, channels: 1, frameSize: 960)
        pcmAccumulator.removeAll(keepingCapacity: true)

        input.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { [weak self] buffer, _ in
            self?.consumeMicBuffer(buffer)
        }
        tapInstalled = true
        audioEngine.prepare()
        do {
            try audioEngine.start()
        } catch {
            stopCapture()
            alert(error.localizedDescription)
            return
        }
        streaming = true
        micAckLive = false
        relayClient.sendMicState(on: true)
        updateStatus()
    }

    private func stopStreaming() {
        guard streaming else { return }
        streaming = false
        relayClient.sendMicState(on: false)
        stopCapture()
        DispatchQueue.main.async { [weak self] in
            self?.waveformView.setLevel(0)
            self?.updateStatus()
        }
    }

    /// Mic tap -> 16 kHz mono Int16 -> 960-sample (60 ms) raw opus packets, streamed to
    /// the host voice control page; each frame's RMS feeds the waveform.
    private func consumeMicBuffer(_ buffer: AVAudioPCMBuffer) {
        guard streaming, let converter = micConverter, let encoder = opusEncoder else { return }
        var supplied = false
        let inputBlock: AVAudioConverterInputBlock = { _, status in
            if supplied {
                status.pointee = .noDataNow
                return nil
            }
            supplied = true
            status.pointee = .haveData
            return buffer
        }
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * 16000.0 / buffer.format.sampleRate) + 960
        guard
            let converted = AVAudioPCMBuffer(pcmFormat: converter.outputFormat, frameCapacity: capacity),
            let channel = converted.int16ChannelData?[0]
        else { return }
        var error: NSError?
        converter.convert(to: converted, error: &error, withInputFrom: inputBlock)
        guard error == nil, converted.frameLength > 0 else { return }
        let sampleCount = Int(converted.frameLength)
        for i in 0..<sampleCount {
            pcmAccumulator.append(channel[i])
        }
        while pcmAccumulator.count >= 960 {
            let frame = Array(pcmAccumulator.prefix(960))
            pcmAccumulator.removeFirst(960)
            let rms = Self.frameRms(frame)
            DispatchQueue.main.async { [weak self] in
                self?.waveformView.setLevel(rms)
            }
            let pcm = frame.withUnsafeBufferPointer { Data(buffer: $0) }
            if let packet = encoder.encodePCM16(pcm) {
                relayClient.sendMicFrame(base64: packet.base64EncodedString(), rms: rms)
            }
        }
    }

    /// Tap off, encoder state dropped.
    private func stopCapture() {
        if tapInstalled {
            audioEngine.inputNode.removeTap(onBus: 0)
            tapInstalled = false
        }
        pcmAccumulator.removeAll(keepingCapacity: true)
        micConverter = nil
        opusEncoder = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// RMS of one PCM16 frame, normalized 0..1 (full scale = 32768).
    private static func frameRms(_ samples: [Int16]) -> Float {
        guard !samples.isEmpty else { return 0 }
        var sum: Double = 0
        for s in samples {
            let v = Double(s)
            sum += v * v
        }
        return Float((sum / Double(samples.count)).squareRoot() / 32768.0)
    }

    // MARK: Status and target picker

    private func updateStatus() {
        if relayClient.isHostConnected {
            if !wasConnected, streaming {
                // Fresh link: re-announce the stream so the host re-registers it.
                micAckLive = false
                relayClient.sendMicState(on: true)
            }
            wasConnected = true
            let host = relayClient.hostName
            if streaming {
                connectionLabel.text = String(
                    format: L10n.string(micAckLive ? "remote_mic_live_on" : "remote_mic_host_not_ready"),
                    host)
            } else {
                connectionLabel.text = String(format: L10n.string("remote_mic_host"), host)
            }
        } else {
            wasConnected = false
            connectionLabel.text = L10n.string("voice_relay_searching")
        }
        stateLabel.text = L10n.string(streaming ? "remote_mic_streaming" : "remote_mic_tap_to_start")
        micButton.accessibilityLabel = stateLabel.text
    }

    /// Pops the shared-robot picker (only WiFi hosts — this page always speaks over the relay).
    @objc private func robotPickerTapped() {
        let hosts = relayClient.hostNames()
        guard !hosts.isEmpty else {
            alert(L10n.string("voice_robot_none_found"))
            return
        }
        let sheet = UIAlertController(title: L10n.string("voice_robot_picker_title"),
                                     message: nil, preferredStyle: .actionSheet)
        for host in hosts {
            sheet.addAction(UIAlertAction(
                title: String(format: L10n.string("voice_robot_wifi_item"), host),
                style: .default) { [weak self] _ in
                    self?.relayClient.chooseHost(host)
                    self?.updateStatus()
                })
        }
        sheet.addAction(UIAlertAction(title: L10n.string("voice_cancel"), style: .cancel))
        if let popover = sheet.popoverPresentationController {
            popover.sourceView = connectionCapsule
            popover.sourceRect = connectionCapsule.bounds
        }
        present(sheet, animated: true)
    }

    private func alert(_ message: String) {
        let alertBox = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alertBox.addAction(UIAlertAction(title: L10n.string("okAction"), style: .default))
        present(alertBox, animated: true)
    }
}
