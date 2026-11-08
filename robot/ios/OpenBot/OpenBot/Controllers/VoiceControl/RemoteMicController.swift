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
    /// Internet Mic mode: the same page, but it speaks to the robot phone over the internet
    /// (any WiFi or 4G/5G) through InternetMicLink instead of the same-WiFi relay.
    private let internet: Bool
    private let net = InternetMicLink.shared

    init(internet: Bool = false) {
        self.internet = internet
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        self.internet = false
        super.init(coder: coder)
    }

    private var linkConnected: Bool { internet ? net.isConnected : relayClient.isHostConnected }
    private var linkHostName: String { internet ? net.robotName : relayClient.hostName }

    private func sendMic(on: Bool) {
        if internet { net.sendMicState(on: on) } else { relayClient.sendMicState(on: on) }
    }

    // MARK: UI

    private let connectionCapsule = UIView()
    private let connectionLabel = UILabel()
    private let micButton = UIButton(type: .system)
    private let stateLabel = UILabel()
    private let waveformView = MicLevelBarsView()
    private let scanButton = UIButton(type: .system)
    private let statusDot = UIView()
    private let pulseRing = UIView()
    private var pulsing = false

    // Robotic palette (design "FriendBot Robotic Mic Pages").
    private static let ink = UIColor(red: 0.02, green: 0.027, blue: 0.043, alpha: 1)      // #05070B
    private static let panel = UIColor(red: 0.043, green: 0.078, blue: 0.102, alpha: 1)   // #0B141A
    private static let cyan = RobotFaceView.accent                                         // #7FE7FF
    private static let muted = UIColor(red: 0.56, green: 0.66, blue: 0.70, alpha: 1)      // #8FA9B3
    private static let green = UIColor(red: 0.24, green: 0.86, blue: 0.59, alpha: 1)      // #3DDC97
    private static let amber = UIColor(red: 1.0, green: 0.71, blue: 0.28, alpha: 1)       // #FFB547
    private static let red = UIColor(red: 1.0, green: 0.42, blue: 0.42, alpha: 1)         // #FF6B6B
    private static func mono(_ size: CGFloat, _ weight: UIFont.Weight = .regular) -> UIFont {
        .monospacedSystemFont(ofSize: size, weight: weight)
    }

    // MARK: Capture state

    private let audioEngine = AVAudioEngine()
    private var micConverter: AVAudioConverter?
    private var opusEncoder: OpusCodecEncoder?
    private var pcmAccumulator: [Int16] = []
    private var tapInstalled = false
    private var streaming = false
    /// The host's voice control page confirmed it is hearing the microphone.
    private var micAckLive = false
    /// Another phone holding the host's microphone floor (nil: this phone, or nobody).
    private var floorOther: String?
    /// Phones streaming to the host, this one included (from the host's floor message).
    private var floorPhones = 1
    /// True while the relay link is up (detects reconnects to re-announce the stream).
    private var wasConnected = false

    // MARK: Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemGroupedBackground
        layout()
        if internet {
            net.onChange = { [weak self] in self?.updateStatus() }
            net.onMicAck = { [weak self] live in
                self?.micAckLive = live
                self?.updateStatus()
            }
            net.onMicFloor = { [weak self] speaker, you, phones in
                self?.floorOther = you ? nil : speaker
                self?.floorPhones = phones
                self?.updateStatus()
            }
            return
        }
        relayClient.onStateChange = { [weak self] in self?.updateStatus() }
        relayClient.onMicAck = { [weak self] live in
            self?.micAckLive = live
            self?.updateStatus()
        }
        relayClient.onMicFloor = { [weak self] speaker, you, phones in
            self?.floorOther = you ? nil : speaker
            self?.floorPhones = phones
            self?.updateStatus()
        }
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        if internet {
            net.start()
            updateStatus()
            return
        }
        // This page always speaks over the relay: a remembered "this phone's own robot"
        // pick from the voice page would block the connection forever — undo it here.
        if relayClient.isLocalChoice {
            relayClient.chooseAuto()
        }
        relayClient.onPinRequired = { [weak self] host, wrong in
            guard let self = self else { return }
            VoiceRelayClient.promptForPin(from: self, host: host, wrong: wrong)
        }
        relayClient.start()
        relayClient.retryPending()
        updateStatus()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        // Never hold the microphone while the app is in the background.
        stopStreaming()
        if internet {
            net.stop()
            return
        }
        relayClient.onMicAck = nil
        relayClient.onMicFloor = nil
        relayClient.onStateChange = nil
        relayClient.onPinRequired = nil
    }

    // MARK: Layout

    /// Robotic, simple: kicker + title + link pill with a status light; one big round mic
    /// with a pulsing ring and level bars; the network line and the scan button at the bottom.
    private func layout() {
        overrideUserInterfaceStyle = .dark
        view.backgroundColor = Self.ink

        let kicker = UILabel()
        kicker.text = internet ? "FRIENDBOT // MIC LINK 02 · NET" : "FRIENDBOT // MIC LINK 01"
        kicker.font = Self.mono(11)
        kicker.textColor = UIColor(red: 0.44, green: 0.66, blue: 0.72, alpha: 1)

        let bigTitle = UILabel()
        bigTitle.text = L10n.string(internet ? "tile_internet_mic" : "tile_remote_mic")
        bigTitle.font = .systemFont(ofSize: 34, weight: .semibold)
        bigTitle.textColor = .white

        // Link pill: status light + one status line; tap = options / robot picker.
        statusDot.backgroundColor = Self.amber
        statusDot.layer.cornerRadius = 4
        statusDot.translatesAutoresizingMaskIntoConstraints = false
        statusDot.widthAnchor.constraint(equalToConstant: 8).isActive = true
        statusDot.heightAnchor.constraint(equalToConstant: 8).isActive = true
        connectionLabel.font = Self.mono(12)
        connectionLabel.textColor = UIColor(red: 0.81, green: 0.96, blue: 1, alpha: 1)
        connectionLabel.numberOfLines = 2
        let capsule = connectionCapsule
        capsule.backgroundColor = Self.panel
        capsule.layer.cornerRadius = 10
        capsule.layer.borderWidth = 1
        capsule.layer.borderColor = Self.cyan.withAlphaComponent(0.25).cgColor
        let capsuleInner = UIStackView(arrangedSubviews: [statusDot, connectionLabel])
        capsuleInner.axis = .horizontal
        capsuleInner.spacing = 10
        capsuleInner.alignment = .center
        capsuleInner.translatesAutoresizingMaskIntoConstraints = false
        capsule.addSubview(capsuleInner)
        NSLayoutConstraint.activate([
            capsule.heightAnchor.constraint(greaterThanOrEqualToConstant: 44),
            capsuleInner.topAnchor.constraint(equalTo: capsule.topAnchor, constant: 8),
            capsuleInner.bottomAnchor.constraint(equalTo: capsule.bottomAnchor, constant: -8),
            capsuleInner.leadingAnchor.constraint(equalTo: capsule.leadingAnchor, constant: 14),
            capsuleInner.trailingAnchor.constraint(equalTo: capsule.trailingAnchor, constant: -14),
        ])
        capsule.isUserInteractionEnabled = true
        capsule.accessibilityLabel = L10n.string("voice_robot_picker_title")
        capsule.accessibilityTraits = .button
        capsule.addGestureRecognizer(UITapGestureRecognizer(target: self,
                                                            action: #selector(robotPickerTapped)))

        // The mic: dashed outer ring, pulsing ring while live, the round button.
        let orbSize: CGFloat = 160
        let ringBox = UIView()
        ringBox.translatesAutoresizingMaskIntoConstraints = false
        ringBox.widthAnchor.constraint(equalToConstant: 210).isActive = true
        ringBox.heightAnchor.constraint(equalToConstant: 210).isActive = true
        let dashed = CAShapeLayer()
        dashed.path = UIBezierPath(ovalIn: CGRect(x: 0.5, y: 0.5, width: 209, height: 209)).cgPath
        dashed.fillColor = UIColor.clear.cgColor
        dashed.strokeColor = Self.cyan.withAlphaComponent(0.2).cgColor
        dashed.lineWidth = 1
        dashed.lineDashPattern = [4, 4]
        ringBox.layer.addSublayer(dashed)
        pulseRing.layer.cornerRadius = orbSize / 2
        pulseRing.layer.borderWidth = 2
        pulseRing.layer.borderColor = Self.cyan.cgColor
        pulseRing.alpha = 0
        pulseRing.isUserInteractionEnabled = false
        pulseRing.translatesAutoresizingMaskIntoConstraints = false
        ringBox.addSubview(pulseRing)
        var micConfig = UIButton.Configuration.plain()
        micConfig.image = UIImage(systemName: "mic",
                                  withConfiguration: UIImage.SymbolConfiguration(pointSize: 46, weight: .regular))
        micButton.configuration = micConfig
        micButton.layer.cornerRadius = orbSize / 2
        micButton.layer.borderWidth = 2
        micButton.layer.borderColor = Self.cyan.cgColor
        micButton.layer.shadowColor = Self.cyan.cgColor
        micButton.layer.shadowRadius = 18
        micButton.layer.shadowOpacity = 0.45
        micButton.layer.shadowOffset = .zero
        micButton.translatesAutoresizingMaskIntoConstraints = false
        micButton.accessibilityLabel = L10n.string("remote_mic_tap_to_start")
        micButton.addTarget(self, action: #selector(micTapped), for: .touchUpInside)
        ringBox.addSubview(micButton)
        NSLayoutConstraint.activate([
            micButton.widthAnchor.constraint(equalToConstant: orbSize),
            micButton.heightAnchor.constraint(equalToConstant: orbSize),
            micButton.centerXAnchor.constraint(equalTo: ringBox.centerXAnchor),
            micButton.centerYAnchor.constraint(equalTo: ringBox.centerYAnchor),
            pulseRing.widthAnchor.constraint(equalToConstant: orbSize),
            pulseRing.heightAnchor.constraint(equalToConstant: orbSize),
            pulseRing.centerXAnchor.constraint(equalTo: ringBox.centerXAnchor),
            pulseRing.centerYAnchor.constraint(equalTo: ringBox.centerYAnchor),
        ])

        waveformView.translatesAutoresizingMaskIntoConstraints = false
        waveformView.widthAnchor.constraint(equalToConstant: 120).isActive = true
        waveformView.heightAnchor.constraint(equalToConstant: 36).isActive = true

        stateLabel.text = L10n.string("remote_mic_tap_to_start").uppercased()
        stateLabel.font = Self.mono(15, .semibold)
        stateLabel.textColor = UIColor(red: 0.9, green: 0.98, blue: 1, alpha: 1)
        stateLabel.textAlignment = .center
        let hintLabel = UILabel()
        hintLabel.text = "Xiaozhi answers on the robot phone"
        hintLabel.font = Self.mono(11)
        hintLabel.textColor = Self.muted
        hintLabel.textAlignment = .center

        let centerStack = UIStackView(arrangedSubviews: [ringBox, waveformView, stateLabel, hintLabel])
        centerStack.axis = .vertical
        centerStack.spacing = 22
        centerStack.setCustomSpacing(6, after: stateLabel)
        centerStack.alignment = .center
        let centerWrap = UIView()
        centerStack.translatesAutoresizingMaskIntoConstraints = false
        centerWrap.addSubview(centerStack)
        NSLayoutConstraint.activate([
            centerStack.centerXAnchor.constraint(equalTo: centerWrap.centerXAnchor),
            centerStack.centerYAnchor.constraint(equalTo: centerWrap.centerYAnchor),
        ])

        // Bottom: network line + the scan button.
        let netLeft = UILabel()
        netLeft.text = internet ? "WIFI OR 4G/5G" : "SAME WIFI"
        netLeft.font = Self.mono(11)
        netLeft.textColor = Self.muted
        let netRight = UILabel()
        netRight.text = internet ? "ENCRYPTED" : "LOCAL NETWORK"
        netRight.font = Self.mono(11)
        netRight.textColor = Self.muted
        netRight.textAlignment = .right
        let netRow = UIStackView(arrangedSubviews: [netLeft, netRight])
        netRow.axis = .horizontal
        netRow.distribution = .equalSpacing

        var scanConfig = UIButton.Configuration.plain()
        scanConfig.title = L10n.string(internet ? "internet_mic_scan_qr" : "remote_mic_scan_qr").uppercased()
        scanConfig.image = UIImage(systemName: "qrcode.viewfinder")
        scanConfig.imagePadding = 10
        scanConfig.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { attrs in
            var a = attrs
            a.font = Self.mono(13, .semibold)
            return a
        }
        scanButton.configuration = scanConfig
        scanButton.layer.cornerRadius = 12
        scanButton.layer.borderWidth = 1
        scanButton.layer.borderColor = Self.cyan.cgColor
        scanButton.tintColor = Self.cyan
        scanButton.addTarget(self, action: #selector(scanTapped), for: .touchUpInside)

        let stack = UIStackView(arrangedSubviews: [kicker, bigTitle, capsule, centerWrap, netRow, scanButton])
        stack.axis = .vertical
        stack.spacing = 12
        stack.setCustomSpacing(6, after: kicker)
        stack.alignment = .leading
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            stack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -16),
            centerWrap.widthAnchor.constraint(equalTo: stack.widthAnchor),
            netRow.widthAnchor.constraint(equalTo: stack.widthAnchor),
            scanButton.widthAnchor.constraint(equalTo: stack.widthAnchor),
            scanButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 52),
        ])
        applyStyle(linkColor: Self.amber, scanFilled: false)
    }

    /// Live / idle orb, pulsing ring, status light and scan emphasis.
    private func applyStyle(linkColor: UIColor, scanFilled: Bool) {
        statusDot.backgroundColor = linkColor
        statusDot.layer.shadowColor = linkColor.cgColor
        statusDot.layer.shadowRadius = 5
        statusDot.layer.shadowOpacity = 0.9
        statusDot.layer.shadowOffset = .zero
        micButton.backgroundColor = streaming ? Self.cyan : Self.panel
        micButton.tintColor = streaming ? Self.ink : Self.cyan
        stateLabel.textColor = streaming ? Self.cyan : UIColor(red: 0.9, green: 0.98, blue: 1, alpha: 1)
        waveformView.setActive(streaming)
        scanButton.backgroundColor = scanFilled ? Self.cyan : .clear
        scanButton.tintColor = scanFilled ? Self.ink : Self.cyan
        if streaming && !pulsing {
            pulsing = true
            pulse()
        } else if !streaming {
            pulsing = false
            pulseRing.layer.removeAllAnimations()
            pulseRing.alpha = 0
            pulseRing.transform = .identity
        }
    }

    private func pulse() {
        guard pulsing else { return }
        pulseRing.transform = .identity
        pulseRing.alpha = 0.55
        UIView.animate(withDuration: 1.6, delay: 0, options: [.curveEaseOut, .allowUserInteraction],
                       animations: {
                           self.pulseRing.transform = CGAffineTransform(scaleX: 1.31, y: 1.31)
                           self.pulseRing.alpha = 0
                       },
                       completion: { [weak self] _ in self?.pulse() })
    }

    // MARK: Streaming

    @objc private func micTapped() {
        if streaming {
            stopStreaming()
            return
        }
        guard linkConnected else {
            alert(internet ? (connectionLabel.text ?? "") : L10n.string("voice_relay_searching"))
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
        guard !streaming, linkConnected else { return }
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
        sendMic(on: true)
        updateStatus()
    }

    private func stopStreaming() {
        guard streaming else { return }
        streaming = false
        sendMic(on: false)
        floorOther = nil
        floorPhones = 1
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
                if internet {
                    net.sendMicFrame(base64: packet.base64EncodedString(), rms: rms)
                } else {
                    relayClient.sendMicFrame(base64: packet.base64EncodedString(), rms: rms)
                }
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
        if linkConnected {
            if !wasConnected, streaming, !internet {
                // Fresh link: re-announce the stream so the host re-registers it.
                micAckLive = false
                relayClient.sendMicState(on: true)
            }
            wasConnected = true
            let host = linkHostName
            if streaming && !micAckLive {
                connectionLabel.text = String(format: L10n.string("remote_mic_host_not_ready"), host)
            } else if streaming, let other = floorOther {
                // Two phones share the mic: the other one is talking, this one waits its turn.
                connectionLabel.text = String(format: L10n.string("remote_mic_floor_other"), other)
            } else if streaming && floorPhones > 1 {
                connectionLabel.text = String(
                    format: L10n.string("remote_mic_live_shared"), host, floorPhones)
            } else if streaming {
                connectionLabel.text = String(format: L10n.string("remote_mic_live_on"), host)
            } else {
                connectionLabel.text = String(
                    format: L10n.string(internet ? "internet_mic_connected" : "remote_mic_host"), host)
            }
        } else if internet {
            wasConnected = false
            floorOther = nil
            floorPhones = 1
            switch net.state {
            case .noCode: connectionLabel.text = L10n.string("internet_mic_no_code")
            case .connecting: connectionLabel.text = L10n.string("internet_mic_connecting")
            case .waitingRobot:
                connectionLabel.text = String(format: L10n.string("internet_mic_waiting"), net.robotName)
            case .offline, .connected:
                connectionLabel.text = String(format: L10n.string("internet_mic_offline"), net.robotName)
            }
        } else {
            wasConnected = false
            floorOther = nil
            floorPhones = 1
            if let chosen = relayClient.chosenHost {
                connectionLabel.text = String(format: L10n.string("remote_mic_connecting"), chosen)
            } else {
                connectionLabel.text = L10n.string("voice_relay_searching")
            }
        }
        stateLabel.text = L10n.string(streaming ? "remote_mic_streaming" : "remote_mic_tap_to_start").uppercased()
        micButton.accessibilityLabel = stateLabel.text
        connectionLabel.text = connectionLabel.text?.uppercased()
        applyStyle(linkColor: linkColor(), scanFilled: !linkConnected)
    }

    /// The link pill's status light: green linked, amber busy, red trouble, grey no code.
    private func linkColor() -> UIColor {
        if linkConnected { return streaming && !micAckLive ? Self.amber : Self.green }
        if internet {
            switch net.state {
            case .noCode: return Self.muted
            case .offline: return Self.red
            default: return Self.amber
            }
        }
        return Self.amber
    }

    /// Pops the shared-robot picker: scan a QR, a robot phone found on the WiFi, or type an IP.
    @objc private func robotPickerTapped() {
        if internet {
            let sheet = UIAlertController(title: L10n.string("tile_internet_mic"), message: nil,
                                          preferredStyle: .actionSheet)
            sheet.addAction(UIAlertAction(title: L10n.string("internet_mic_scan_qr"), style: .default) {
                [weak self] _ in self?.scanTapped()
            })
            sheet.addAction(UIAlertAction(title: L10n.string("internet_mic_forget"), style: .destructive) {
                [weak self] _ in
                self?.stopStreaming()
                self?.net.forget()
            })
            sheet.addAction(UIAlertAction(title: L10n.string("voice_cancel"), style: .cancel))
            if let popover = sheet.popoverPresentationController {
                popover.sourceView = connectionCapsule
                popover.sourceRect = connectionCapsule.bounds
            }
            present(sheet, animated: true)
            return
        }
        let hosts = relayClient.hostNames()
        let sheet = UIAlertController(title: L10n.string("voice_robot_picker_title"),
                                     message: hosts.isEmpty ? L10n.string("remote_mic_picker_none") : nil,
                                     preferredStyle: .actionSheet)
        sheet.addAction(UIAlertAction(title: L10n.string("remote_mic_scan_qr"), style: .default) {
            [weak self] _ in self?.scanTapped()
        })
        sheet.addAction(UIAlertAction(title: L10n.string("remote_mic_picker_ip"), style: .default) {
            [weak self] _ in self?.askForIp()
        })
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

    // MARK: QR / IP pairing

    /// Scans the QR on the robot phone's Voice Control page and connects to that IP.
    @objc private func scanTapped() {
        let scanner = QrScannerController()
        scanner.onCode = { [weak self] code in
            guard let self = self else { return }
            if self.internet {
                guard let qr = RelayQr.parseNet(code) else {
                    self.alert(L10n.string("internet_mic_qr_invalid"))
                    return
                }
                self.net.setCode(qr, raw: code)
                self.updateStatus()
                return
            }
            guard let qr = RelayQr.parse(code) else {
                self.alert(L10n.string("remote_mic_qr_invalid"))
                return
            }
            self.relayClient.connectDirect(ip: qr.ip, port: qr.port, name: qr.name)
            self.updateStatus()
        }
        scanner.modalPresentationStyle = .fullScreen
        present(scanner, animated: true)
    }

    /// Manual fallback: the robot phone shows its IP under its QR.
    private func askForIp() {
        let box = UIAlertController(title: L10n.string("remote_mic_ip_title"),
                                    message: L10n.string("remote_mic_ip_message"),
                                    preferredStyle: .alert)
        box.addTextField { field in
            field.keyboardType = .decimalPad
            field.placeholder = "192.168.1.23"
        }
        box.addAction(UIAlertAction(title: L10n.string("voice_cancel"), style: .cancel))
        box.addAction(UIAlertAction(title: L10n.string("voice_relay_pin_connect"), style: .default) {
            [weak self, weak box] _ in
            let ip = box?.textFields?.first?.text?.trimmingCharacters(in: .whitespaces) ?? ""
            guard ip.range(of: "^\\d{1,3}(\\.\\d{1,3}){3}$", options: .regularExpression) != nil else {
                self?.alert(L10n.string("remote_mic_ip_invalid"))
                return
            }
            self?.relayClient.connectDirect(ip: ip, port: RelayQr.relayPort, name: ip)
            self?.updateStatus()
        })
        present(box, animated: true)
    }

    private func alert(_ message: String) {
        let alertBox = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alertBox.addAction(UIAlertAction(title: L10n.string("okAction"), style: .default))
        present(alertBox, animated: true)
    }
}

// MARK: - QR scanner

/// Full-screen camera that reads one QR code and hands its text back (AVFoundation, no
/// extra libraries). Closes itself after the first code or on Cancel.
final class QrScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {

    var onCode: ((String) -> Void)?

    private let session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer?
    private var finished = false
    private let hint = UILabel()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        var cancelConfig = UIButton.Configuration.gray()
        cancelConfig.title = L10n.string("voice_cancel")
        cancelConfig.cornerStyle = .capsule
        let cancel = UIButton(configuration: cancelConfig)
        cancel.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        cancel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(cancel)

        hint.text = L10n.string("remote_mic_qr_hint")
        hint.textColor = .white
        hint.font = .systemFont(ofSize: 15, weight: .semibold)
        hint.numberOfLines = 0
        hint.textAlignment = .center
        hint.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(hint)

        NSLayoutConstraint.activate([
            cancel.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            cancel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            hint.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            hint.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            hint.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -32),
        ])

        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            setUpCamera()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                DispatchQueue.main.async {
                    if granted { self.setUpCamera() } else { self.showDenied() }
                }
            }
        default:
            showDenied()
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        let running = session
        DispatchQueue.global(qos: .userInitiated).async {
            if running.isRunning { running.stopRunning() }
        }
    }

    private func setUpCamera() {
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
                ?? AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            showDenied()
            return
        }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            showDenied()
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.insertSublayer(layer, at: 0)
        preview = layer
        let running = session
        DispatchQueue.global(qos: .userInitiated).async { running.startRunning() }
    }

    private func showDenied() {
        hint.text = L10n.string("remote_mic_qr_camera_denied")
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput,
                        didOutput metadataObjects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard !finished,
              let code = (metadataObjects.first as? AVMetadataMachineReadableCodeObject)?.stringValue
        else { return }
        finished = true
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        let callback = onCode
        dismiss(animated: true) { callback?(code) }
    }

    @objc private func cancelTapped() {
        finished = true
        dismiss(animated: true)
    }
}

// MARK: - Level bars

/// Nine cyan level bars under the mic orb (robotic mic pages): dim at rest, alive with voice.
final class MicLevelBarsView: UIView {

    private static let shape: [CGFloat] = [0.33, 0.55, 0.83, 0.6, 1, 0.66, 0.83, 0.5, 0.28]
    private var level: CGFloat = 0
    private var active = false
    private var link: CADisplayLink?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
        isAccessibilityElement = false
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func setLevel(_ value: Float) {
        level = CGFloat(max(0, min(1, value * 3.5)))
    }

    func setActive(_ on: Bool) {
        active = on
        if !on { level = 0 }
        link?.invalidate()
        link = nil
        if on {
            let l = CADisplayLink(target: self, selector: #selector(tick))
            l.add(to: .main, forMode: .common)
            link = l
        }
        setNeedsDisplay()
    }

    @objc private func tick() { setNeedsDisplay() }

    override func draw(_ rect: CGRect) {
        let n = Self.shape.count
        let barW: CGFloat = 6
        let gap = (bounds.width - CGFloat(n) * barW) / CGFloat(n - 1)
        let t = CACurrentMediaTime()
        RobotFaceView.accent.withAlphaComponent(active ? 1 : 0.25).setFill()
        for i in 0..<n {
            var k: CGFloat = 0.35
            if active {
                let wobble = 0.75 + 0.25 * CGFloat(sin(t * 11 + Double(i) * 1.3))
                k = max(0.25, min(1, (0.25 + level) * wobble))
            }
            let h = bounds.height * Self.shape[i] * k
            let x = CGFloat(i) * (barW + gap)
            UIBezierPath(roundedRect: CGRect(x: x, y: bounds.height - h, width: barW, height: h),
                         cornerRadius: barW / 2).fill()
        }
    }

    override func removeFromSuperview() {
        link?.invalidate()
        link = nil
        super.removeFromSuperview()
    }
}

// MARK: - Wi‑Fi Mic Pro / Internet Mic Pro

/// The mic pages plus driving tools — the Remote Mic / Internet Mic pages above stay as they are.
///   - STOP: the red bar stops the robot at once. Auto-stop: the robot phone stops by itself when
///     this page goes silent for 1.5 s (Wi‑Fi) / 3 s (internet) while the robot moves.
///   - Heard / answered: what Xiaozhi understood and what it said back.
///   - Camera: tap the window to see the robot phone's camera (5 / 2 pictures a second).
///   - Hold to talk: the microphone is open only while the orb is held.
///   - Drive pad: hold an arrow to drive (short repeated moves), let go to stop.
///   - Link delay and battery chips.
final class ProMicController: UIViewController {

    private let internet: Bool
    private let relayClient = VoiceRelayClient.shared
    private let net = InternetMicLink.shared

    init(internet: Bool) {
        self.internet = internet
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        self.internet = false
        super.init(coder: coder)
    }

    private static let driveMs = 600
    private static let driveRepeat: TimeInterval = 0.25
    private static let pad: [Int: (Float, Float)] = [
        0: (0.6, 0.6), 1: (-0.45, 0.45), 2: (0.45, -0.45), 3: (-0.6, -0.6),
    ]

    // Robotic palette (same as the mic pages).
    private static let ink = UIColor(red: 0.02, green: 0.027, blue: 0.043, alpha: 1)
    private static let panel = UIColor(red: 0.043, green: 0.078, blue: 0.102, alpha: 1)
    private static let cyan = RobotFaceView.accent
    private static let muted = UIColor(red: 0.56, green: 0.66, blue: 0.70, alpha: 1)
    private static let kickerColor = UIColor(red: 0.435, green: 0.659, blue: 0.722, alpha: 1)
    private static let green = UIColor(red: 0.24, green: 0.86, blue: 0.59, alpha: 1)
    private static let amber = UIColor(red: 1.0, green: 0.71, blue: 0.28, alpha: 1)
    private static let red = UIColor(red: 1.0, green: 0.42, blue: 0.42, alpha: 1)
    private static func mono(_ size: CGFloat, _ weight: UIFont.Weight = .regular) -> UIFont {
        .monospacedSystemFont(ofSize: size, weight: weight)
    }

    // MARK: Views

    private let pillLabel = UILabel()
    private let pillDot = UIView()
    private let pill = UIView()
    private let pingChip = UILabel()
    private let batteryChip = UILabel()
    private let camBox = UIView()
    private let camImage = UIImageView()
    private let camHint = UILabel()
    private let camTag = UILabel()
    private let heardLabel = UILabel()
    private let saidLabel = UILabel()
    private var padButtons: [UIButton] = []
    private let orb = UIButton(type: .custom)
    private let levelBars = MicLevelBarsView()
    private let stateLabel = UILabel()
    private let stopButton = UIButton(type: .system)
    private let scanButton = UIButton(type: .system)

    // MARK: State

    private let audioEngine = AVAudioEngine()
    private var micConverter: AVAudioConverter?
    private var opusEncoder: OpusCodecEncoder?
    private var pcmAccumulator: [Int16] = []
    private var tapInstalled = false
    private var talking = false
    private var micAckLive = false
    private var floorOther: String?
    private var wasConnected = false
    private var camOn = false
    private var camAt: TimeInterval = 0
    private var pingId = 0
    private var pingSentAt: TimeInterval = 0
    private var lastPongAt: TimeInterval = 0
    private var rttMs: Int?
    private var heartbeat: Timer?
    private var driveTimer: Timer?
    private var driving: Int?

    private var linkConnected: Bool { internet ? net.isConnected : relayClient.isHostConnected }
    private var linkHostName: String { internet ? net.robotName : relayClient.hostName }

    private func sendRaw(_ line: String) {
        if internet { net.sendRaw(line) } else { relayClient.sendRaw(line) }
    }

    private func sendMic(on: Bool) {
        if internet { net.sendMicState(on: on) } else { relayClient.sendMicState(on: on) }
    }

    // MARK: Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Self.ink
        title = L10n.string(internet ? "tile_internet_mic_pro" : "tile_wifi_mic_pro")
        layout()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        if internet {
            net.onChange = { [weak self] in self?.updateStatus() }
            net.onMicAck = { [weak self] live in self?.micAckLive = live; self?.updateStatus() }
            net.onMicFloor = { [weak self] speaker, you, _ in
                self?.floorOther = you ? nil : speaker
                self?.updateStatus()
            }
            net.onExtra = { [weak self] msg in self?.extra(msg) }
            net.start()
        } else {
            relayClient.onStateChange = { [weak self] in self?.updateStatus() }
            relayClient.onMicAck = { [weak self] live in self?.micAckLive = live; self?.updateStatus() }
            relayClient.onMicFloor = { [weak self] speaker, you, _ in
                self?.floorOther = you ? nil : speaker
                self?.updateStatus()
            }
            relayClient.onExtra = { [weak self] msg in self?.extra(msg) }
            relayClient.onPinRequired = { [weak self] host, wrong in
                guard let self = self else { return }
                VoiceRelayClient.promptForPin(from: self, host: host, wrong: wrong)
            }
            if relayClient.isLocalChoice { relayClient.chooseAuto() }
            relayClient.start()
            relayClient.retryPending()
        }
        wasConnected = false
        heartbeat = Timer.scheduledTimer(withTimeInterval: internet ? 1.0 : 0.5, repeats: true) {
            [weak self] _ in self?.beat()
        }
        updateStatus()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        setTalking(false) // never hold the microphone off screen
        drive(nil)
        heartbeat?.invalidate()
        heartbeat = nil
        if linkConnected { sendRaw("{\"t\":\"pro\",\"on\":false}") }
        if internet {
            net.onExtra = nil
            net.stop()
        } else {
            relayClient.onExtra = nil
            relayClient.onMicAck = nil
            relayClient.onMicFloor = nil
            relayClient.onStateChange = nil
            relayClient.onPinRequired = nil
        }
    }

    // MARK: Layout

    private func label(_ text: String, _ font: UIFont, _ color: UIColor) -> UILabel {
        let l = UILabel()
        l.text = text
        l.font = font
        l.textColor = color
        return l
    }

    private func chip(_ l: UILabel, _ text: String) {
        l.text = text
        l.font = Self.mono(11, .bold)
        l.textColor = Self.muted
        l.textAlignment = .center
        l.backgroundColor = Self.panel
        l.layer.cornerRadius = 17
        l.layer.borderWidth = 1
        l.layer.borderColor = Self.cyan.withAlphaComponent(0.2).cgColor
        l.clipsToBounds = true
        l.translatesAutoresizingMaskIntoConstraints = false
        l.heightAnchor.constraint(equalToConstant: 34).isActive = true
        l.widthAnchor.constraint(greaterThanOrEqualToConstant: 64).isActive = true
        l.setContentCompressionResistancePriority(.required, for: .horizontal)
    }

    private func layout() {
        let kicker = label(internet ? "FRIENDBOT // NET PRO" : "FRIENDBOT // LAN PRO", Self.mono(11), Self.kickerColor)
        let heading = label(title ?? "", .systemFont(ofSize: 28, weight: .semibold), .white)

        // Link pill + delay + battery
        pill.backgroundColor = Self.panel
        pill.layer.cornerRadius = 17
        pill.layer.borderWidth = 1
        pillDot.layer.cornerRadius = 4
        pillDot.translatesAutoresizingMaskIntoConstraints = false
        pillLabel.font = Self.mono(11, .bold)
        pillLabel.lineBreakMode = .byTruncatingTail
        pillLabel.translatesAutoresizingMaskIntoConstraints = false
        pill.addSubview(pillDot)
        pill.addSubview(pillLabel)
        NSLayoutConstraint.activate([
            pillDot.widthAnchor.constraint(equalToConstant: 8),
            pillDot.heightAnchor.constraint(equalToConstant: 8),
            pillDot.leadingAnchor.constraint(equalTo: pill.leadingAnchor, constant: 12),
            pillDot.centerYAnchor.constraint(equalTo: pill.centerYAnchor),
            pillLabel.leadingAnchor.constraint(equalTo: pillDot.trailingAnchor, constant: 8),
            pillLabel.trailingAnchor.constraint(equalTo: pill.trailingAnchor, constant: -10),
            pillLabel.centerYAnchor.constraint(equalTo: pill.centerYAnchor),
            pill.heightAnchor.constraint(equalToConstant: 34),
        ])
        pill.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(optionsTapped)))
        chip(pingChip, "— ms")
        chip(batteryChip, "BAT —")
        let topRow = UIStackView(arrangedSubviews: [pill, pingChip, batteryChip])
        topRow.spacing = 6

        // Camera window
        camBox.backgroundColor = Self.panel
        camBox.layer.cornerRadius = 14
        camBox.layer.borderWidth = 1
        camBox.layer.borderColor = Self.cyan.withAlphaComponent(0.27).cgColor
        camBox.clipsToBounds = true
        camImage.contentMode = .scaleAspectFill
        camImage.clipsToBounds = true
        camImage.isHidden = true
        camHint.text = L10n.string("pro_cam_off")
        camHint.font = Self.mono(12)
        camHint.textColor = Self.kickerColor
        camHint.numberOfLines = 0
        camHint.textAlignment = .center
        camTag.font = Self.mono(10, .bold)
        camTag.backgroundColor = Self.panel.withAlphaComponent(0.85)
        camTag.layer.cornerRadius = 9
        camTag.clipsToBounds = true
        camTag.textAlignment = .center
        for v in [camImage, camHint, camTag] as [UIView] {
            v.translatesAutoresizingMaskIntoConstraints = false
            camBox.addSubview(v)
        }
        NSLayoutConstraint.activate([
            camImage.topAnchor.constraint(equalTo: camBox.topAnchor),
            camImage.bottomAnchor.constraint(equalTo: camBox.bottomAnchor),
            camImage.leadingAnchor.constraint(equalTo: camBox.leadingAnchor),
            camImage.trailingAnchor.constraint(equalTo: camBox.trailingAnchor),
            camHint.centerXAnchor.constraint(equalTo: camBox.centerXAnchor),
            camHint.centerYAnchor.constraint(equalTo: camBox.centerYAnchor),
            camHint.widthAnchor.constraint(lessThanOrEqualTo: camBox.widthAnchor, constant: -24),
            camTag.topAnchor.constraint(equalTo: camBox.topAnchor, constant: 10),
            camTag.leadingAnchor.constraint(equalTo: camBox.leadingAnchor, constant: 10),
            camTag.heightAnchor.constraint(equalToConstant: 18),
            camTag.widthAnchor.constraint(greaterThanOrEqualToConstant: 70),
        ])
        camBox.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(cameraTapped)))
        showCamTag()

        // Heard / answered
        heardLabel.font = Self.mono(12)
        heardLabel.textColor = .white
        heardLabel.numberOfLines = 2
        saidLabel.font = Self.mono(12)
        saidLabel.textColor = Self.cyan
        saidLabel.numberOfLines = 2
        heardLabel.text = L10n.string("pro_heard_empty")
        saidLabel.text = L10n.string("pro_said_empty")
        let words = UIStackView(arrangedSubviews: [heardLabel, saidLabel])
        words.axis = .vertical
        words.spacing = 4
        words.isLayoutMarginsRelativeArrangement = true
        words.layoutMargins = UIEdgeInsets(top: 8, left: 12, bottom: 8, right: 12)
        words.backgroundColor = Self.panel
        words.layer.cornerRadius = 12

        // Drive pad (0 up, 1 left, 2 right, 3 down) + hold-to-talk orb
        let padView = UIView()
        padView.translatesAutoresizingMaskIntoConstraints = false
        let icons = ["arrow.up", "arrow.left", "arrow.right", "arrow.down"]
        for (i, name) in icons.enumerated() {
            let b = UIButton(type: .custom)
            b.tag = i
            b.setImage(UIImage(systemName: name, withConfiguration: UIImage.SymbolConfiguration(pointSize: 20, weight: .bold)), for: .normal)
            b.tintColor = Self.cyan
            b.backgroundColor = Self.panel
            b.layer.cornerRadius = 14
            b.layer.borderWidth = 1.5
            b.layer.borderColor = Self.cyan.withAlphaComponent(0.6).cgColor
            b.translatesAutoresizingMaskIntoConstraints = false
            b.addTarget(self, action: #selector(padDown(_:)), for: .touchDown)
            b.addTarget(self, action: #selector(padUp(_:)), for: [.touchUpInside, .touchUpOutside, .touchCancel])
            padView.addSubview(b)
            padButtons.append(b)
            b.widthAnchor.constraint(equalToConstant: 54).isActive = true
            b.heightAnchor.constraint(equalToConstant: 54).isActive = true
        }
        let padLabel = label("DRIVE", Self.mono(10), Self.kickerColor)
        padLabel.translatesAutoresizingMaskIntoConstraints = false
        padView.addSubview(padLabel)
        let (up, left, right, down) = (padButtons[0], padButtons[1], padButtons[2], padButtons[3])
        NSLayoutConstraint.activate([
            padView.widthAnchor.constraint(equalToConstant: 166),
            padView.heightAnchor.constraint(equalToConstant: 166),
            up.topAnchor.constraint(equalTo: padView.topAnchor),
            up.centerXAnchor.constraint(equalTo: padView.centerXAnchor),
            down.bottomAnchor.constraint(equalTo: padView.bottomAnchor),
            down.centerXAnchor.constraint(equalTo: padView.centerXAnchor),
            left.leadingAnchor.constraint(equalTo: padView.leadingAnchor),
            left.centerYAnchor.constraint(equalTo: padView.centerYAnchor),
            right.trailingAnchor.constraint(equalTo: padView.trailingAnchor),
            right.centerYAnchor.constraint(equalTo: padView.centerYAnchor),
            padLabel.centerXAnchor.constraint(equalTo: padView.centerXAnchor),
            padLabel.centerYAnchor.constraint(equalTo: padView.centerYAnchor),
        ])

        orb.setImage(UIImage(systemName: "mic.fill", withConfiguration: UIImage.SymbolConfiguration(pointSize: 36, weight: .regular)), for: .normal)
        orb.layer.cornerRadius = 56
        orb.layer.borderWidth = 2.5
        orb.layer.borderColor = Self.cyan.cgColor
        orb.translatesAutoresizingMaskIntoConstraints = false
        orb.widthAnchor.constraint(equalToConstant: 112).isActive = true
        orb.heightAnchor.constraint(equalToConstant: 112).isActive = true
        orb.addTarget(self, action: #selector(orbDown), for: .touchDown)
        orb.addTarget(self, action: #selector(orbUp), for: [.touchUpInside, .touchUpOutside, .touchCancel])
        levelBars.translatesAutoresizingMaskIntoConstraints = false
        levelBars.widthAnchor.constraint(equalToConstant: 90).isActive = true
        levelBars.heightAnchor.constraint(equalToConstant: 22).isActive = true
        stateLabel.font = Self.mono(12, .bold)
        stateLabel.textAlignment = .center
        let talkColumn = UIStackView(arrangedSubviews: [orb, levelBars, stateLabel])
        talkColumn.axis = .vertical
        talkColumn.alignment = .center
        talkColumn.spacing = 6
        let controls = UIStackView(arrangedSubviews: [padView, talkColumn])
        controls.alignment = .center
        controls.distribution = .equalSpacing

        // STOP + scan
        stopButton.setTitle(L10n.string("pro_stop"), for: .normal)
        stopButton.titleLabel?.font = Self.mono(18, .bold)
        stopButton.setTitleColor(.white, for: .normal)
        stopButton.backgroundColor = Self.red
        stopButton.layer.cornerRadius = 16
        stopButton.heightAnchor.constraint(equalToConstant: 58).isActive = true
        stopButton.addTarget(self, action: #selector(stopTapped), for: .touchUpInside)
        scanButton.setTitle(L10n.string(internet ? "internet_mic_scan_qr" : "remote_mic_scan_qr").uppercased(), for: .normal)
        scanButton.setImage(UIImage(systemName: "qrcode.viewfinder"), for: .normal)
        scanButton.titleLabel?.font = Self.mono(13, .bold)
        scanButton.layer.cornerRadius = 14
        scanButton.layer.borderWidth = 1.5
        scanButton.layer.borderColor = Self.cyan.cgColor
        scanButton.heightAnchor.constraint(equalToConstant: 46).isActive = true
        scanButton.addTarget(self, action: #selector(scanTapped), for: .touchUpInside)

        let stack = UIStackView(arrangedSubviews: [kicker, heading, topRow, camBox, words, controls, stopButton, scanButton])
        stack.axis = .vertical
        stack.spacing = 10
        stack.setCustomSpacing(2, after: kicker)
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        let g = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: g.topAnchor, constant: 8),
            stack.leadingAnchor.constraint(equalTo: g.leadingAnchor, constant: 18),
            stack.trailingAnchor.constraint(equalTo: g.trailingAnchor, constant: -18),
            stack.bottomAnchor.constraint(equalTo: g.bottomAnchor, constant: -12),
            camBox.heightAnchor.constraint(greaterThanOrEqualToConstant: 120),
        ])
        camBox.setContentHuggingPriority(.defaultLow, for: .vertical) // the camera takes the spare room
    }

    // MARK: Pro protocol

    private func sendPro() {
        sendRaw("{\"t\":\"pro\",\"on\":true,\"watch\":\(internet ? 3000 : 1500),\"cam\":\(camOn),\"fps\":\(internet ? 2 : 5)}")
    }

    /// Fast pings: the robot phone's auto-stop heartbeat, and the link delay.
    private func beat() {
        if linkConnected {
            pingId += 1
            pingSentAt = ProcessInfo.processInfo.systemUptime
            sendRaw("{\"t\":\"ping\",\"id\":\(pingId)}")
        }
        refreshPing()
        if camOn, ProcessInfo.processInfo.systemUptime - camAt > 4 {
            camImage.isHidden = true
            camHint.isHidden = false
            camHint.text = L10n.string("pro_cam_waiting")
        }
    }

    private func extra(_ msg: [String: Any]) {
        let now = ProcessInfo.processInfo.systemUptime
        switch msg["t"] as? String ?? "" {
        case "pong":
            lastPongAt = now
            if (msg["id"] as? NSNumber)?.intValue == pingId {
                rttMs = Int((now - pingSentAt) * 1000)
            }
            refreshPing()
        case "heard":
            heardLabel.text = String(format: L10n.string("pro_heard"), msg["text"] as? String ?? "")
        case "said":
            saidLabel.text = String(format: L10n.string("pro_said"), msg["text"] as? String ?? "")
        case "status":
            showBattery(msg)
        case "cam":
            guard camOn, let b = msg["b"] as? String, let data = Data(base64Encoded: b),
                  let image = UIImage(data: data) else { return }
            camAt = now
            camImage.image = image
            camImage.isHidden = false
            camHint.isHidden = true
        case "safety":
            alert(L10n.string("pro_safety_stopped"))
        default:
            break
        }
    }

    private func showBattery(_ msg: [String: Any]) {
        if let rp = (msg["rp"] as? NSNumber)?.intValue {
            batteryChip.text = String(format: L10n.string("pro_battery_robot"), rp)
            batteryChip.textColor = rp < 20 ? Self.red : rp < 40 ? Self.amber : Self.green
        } else if let bat = (msg["bat"] as? NSNumber)?.intValue {
            let chg = (msg["chg"] as? Bool) ?? false
            batteryChip.text = String(format: L10n.string(chg ? "pro_battery_phone_chg" : "pro_battery_phone"), bat)
            batteryChip.textColor = chg ? Self.green : bat < 20 ? Self.red : bat < 40 ? Self.amber : Self.green
        }
    }

    private func refreshPing() {
        let now = ProcessInfo.processInfo.systemUptime
        if !linkConnected {
            pingChip.text = L10n.string("pro_ping_none")
            pingChip.textColor = Self.muted
        } else if now - lastPongAt > 3 {
            pingChip.text = L10n.string("pro_ping_lost")
            pingChip.textColor = Self.red
        } else if let ms = rttMs {
            pingChip.text = String(format: L10n.string("pro_ping_ms"), ms)
            pingChip.textColor = ms < 150 ? Self.green : ms < 400 ? Self.amber : Self.red
        }
    }

    // MARK: Camera

    @objc private func cameraTapped() {
        camOn.toggle()
        camAt = ProcessInfo.processInfo.systemUptime
        if linkConnected { sendPro() }
        camImage.isHidden = true
        camHint.isHidden = false
        camHint.text = L10n.string(camOn ? "pro_cam_waiting" : "pro_cam_off")
        showCamTag()
    }

    private func showCamTag() {
        camTag.text = camOn ? " " + String(format: L10n.string("pro_cam_tag_on"), internet ? 2 : 5) + " "
                            : " " + L10n.string("pro_cam_tag_off") + " "
        camTag.textColor = camOn ? Self.green : Self.muted
    }

    // MARK: Driving

    @objc private func padDown(_ sender: UIButton) {
        guard linkConnected else {
            alert(pillLabel.text ?? "")
            return
        }
        drive(sender.tag)
    }

    @objc private func padUp(_ sender: UIButton) {
        if driving == sender.tag { drive(nil) }
    }

    private func drive(_ which: Int?) {
        driveTimer?.invalidate()
        driveTimer = nil
        let was = driving
        driving = which
        for b in padButtons {
            let held = b.tag == which
            b.backgroundColor = held ? Self.cyan : Self.panel
            b.tintColor = held ? Self.ink : Self.cyan
        }
        guard let w = which, let speeds = Self.pad[w] else {
            if was != nil { sendRaw("{\"t\":\"stop\"}") } // let go = stop
            return
        }
        let line = "{\"t\":\"move\",\"l\":\(speeds.0),\"r\":\(speeds.1),\"ms\":\(Self.driveMs)}"
        sendRaw(line)
        driveTimer = Timer.scheduledTimer(withTimeInterval: Self.driveRepeat, repeats: true) {
            [weak self] _ in self?.sendRaw(line)
        }
    }

    @objc private func stopTapped() {
        drive(nil)
        guard linkConnected else {
            alert(pillLabel.text ?? "")
            return
        }
        sendRaw("{\"t\":\"stop\"}")
        // Twice, a moment apart: one lost packet on 4G must not leave the robot driving.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { [weak self] in
            self?.sendRaw("{\"t\":\"stop\"}")
        }
        UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
    }

    // MARK: Push-to-talk

    @objc private func orbDown() {
        guard linkConnected else {
            alert(pillLabel.text ?? "")
            return
        }
        AVAudioSession.sharedInstance().requestRecordPermission { [weak self] granted in
            DispatchQueue.main.async {
                guard let self = self else { return }
                guard granted else {
                    self.alert(L10n.string("voice_mic_permission_denied"))
                    return
                }
                // Only if the finger is still on the orb (the permission sheet may have taken it).
                if self.orb.isTracking || self.orb.isTouchInside { self.setTalking(true) }
            }
        }
    }

    @objc private func orbUp() {
        setTalking(false)
    }

    private func setTalking(_ on: Bool) {
        if !on {
            guard talking else { return }
            talking = false
            sendMic(on: false)
            floorOther = nil
            stopCapture()
            levelBars.setLevel(0)
            updateStatus()
            return
        }
        guard !talking, linkConnected else { return }
        do {
            try AVAudioSession.sharedInstance().setCategory(.record, mode: .default)
            try AVAudioSession.sharedInstance().setActive(true, options: .notifyOthersOnDeactivation)
        } catch {
            alert(error.localizedDescription)
            return
        }
        let input = audioEngine.inputNode
        let inputFormat = input.outputFormat(forBus: 0)
        guard let pcmFormat = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16000,
                                            channels: 1, interleaved: false),
              let converter = AVAudioConverter(from: inputFormat, to: pcmFormat) else {
            alert(L10n.string("remote_mic_mic_unavailable"))
            return
        }
        micConverter = converter
        opusEncoder = OpusCodecEncoder(sampleRate: 16000, channels: 1, frameSize: 960)
        pcmAccumulator.removeAll(keepingCapacity: true)
        input.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { [weak self] buffer, _ in
            self?.consume(buffer)
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
        talking = true
        micAckLive = false
        sendMic(on: true)
        updateStatus()
    }

    /// Mic tap -> 16 kHz mono Int16 -> 60 ms opus frames to the robot phone.
    private func consume(_ buffer: AVAudioPCMBuffer) {
        guard talking, let converter = micConverter, let encoder = opusEncoder else { return }
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
        guard let converted = AVAudioPCMBuffer(pcmFormat: converter.outputFormat, frameCapacity: capacity),
              let channel = converted.int16ChannelData?[0] else { return }
        var error: NSError?
        converter.convert(to: converted, error: &error, withInputFrom: inputBlock)
        guard error == nil, converted.frameLength > 0 else { return }
        for i in 0..<Int(converted.frameLength) { pcmAccumulator.append(channel[i]) }
        while pcmAccumulator.count >= 960 {
            let frame = Array(pcmAccumulator.prefix(960))
            pcmAccumulator.removeFirst(960)
            var sum: Double = 0
            for s in frame { sum += Double(s) * Double(s) }
            let rms = Float((sum / 960).squareRoot() / 32768)
            DispatchQueue.main.async { [weak self] in self?.levelBars.setLevel(rms) }
            let pcm = frame.withUnsafeBufferPointer { Data(buffer: $0) }
            if let packet = encoder.encodePCM16(pcm) {
                if internet {
                    net.sendMicFrame(base64: packet.base64EncodedString(), rms: rms)
                } else {
                    relayClient.sendMicFrame(base64: packet.base64EncodedString(), rms: rms)
                }
            }
        }
    }

    private func stopCapture() {
        if tapInstalled {
            audioEngine.inputNode.removeTap(onBus: 0)
            tapInstalled = false
        }
        audioEngine.stop()
        pcmAccumulator.removeAll(keepingCapacity: true)
        micConverter = nil
        opusEncoder = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    // MARK: Status

    private func updateStatus() {
        let connected = linkConnected
        if connected && !wasConnected {
            // Fresh link or reconnect: tell the robot phone what this page wants.
            sendPro()
            lastPongAt = ProcessInfo.processInfo.systemUptime
            if talking {
                micAckLive = false
                sendMic(on: true)
            }
        }
        if !connected { rttMs = nil }
        wasConnected = connected

        let color: UIColor
        if connected {
            pillLabel.text = String(format: L10n.string(internet ? "internet_mic_connected" : "remote_mic_host"), linkHostName)
            color = Self.green
        } else if internet {
            switch net.state {
            case .noCode:
                pillLabel.text = L10n.string("internet_mic_no_code")
                color = Self.muted
            case .connecting:
                pillLabel.text = L10n.string("internet_mic_connecting")
                color = Self.amber
            case .waitingRobot:
                pillLabel.text = String(format: L10n.string("internet_mic_waiting"), net.robotName)
                color = Self.amber
            case .offline, .connected:
                pillLabel.text = String(format: L10n.string("internet_mic_offline"), net.robotName)
                color = Self.red
            }
        } else if let chosen = relayClient.chosenHost {
            pillLabel.text = String(format: L10n.string("remote_mic_connecting"), chosen)
            color = Self.amber
        } else {
            pillLabel.text = L10n.string("voice_relay_searching")
            color = Self.amber
        }
        pillLabel.text = pillLabel.text?.uppercased()
        pillLabel.textColor = color
        pillDot.backgroundColor = color
        pill.layer.borderColor = color.withAlphaComponent(0.45).cgColor

        orb.backgroundColor = talking ? Self.cyan : Self.panel
        orb.tintColor = talking ? Self.ink : Self.cyan
        levelBars.setActive(talking)
        if !talking {
            stateLabel.text = L10n.string("pro_hold_to_talk").uppercased()
        } else if let other = floorOther {
            stateLabel.text = String(format: L10n.string("remote_mic_floor_other"), other)
        } else {
            stateLabel.text = L10n.string(micAckLive ? "pro_talking" : "pro_opening").uppercased()
        }
        stateLabel.textColor = talking ? Self.cyan : .white
        let filled = !connected
        scanButton.backgroundColor = filled ? Self.cyan : .clear
        scanButton.tintColor = filled ? Self.ink : Self.cyan
        scanButton.setTitleColor(filled ? Self.ink : Self.cyan, for: .normal)
        refreshPing()
    }

    // MARK: Choosing the robot

    @objc private func optionsTapped() {
        let sheet = UIAlertController(title: title, message: nil, preferredStyle: .actionSheet)
        if internet {
            sheet.addAction(UIAlertAction(title: L10n.string("internet_mic_scan_qr"), style: .default) {
                [weak self] _ in self?.scanTapped()
            })
            sheet.addAction(UIAlertAction(title: L10n.string("internet_mic_forget"), style: .destructive) {
                [weak self] _ in
                self?.setTalking(false)
                self?.net.forget()
            })
        } else {
            sheet.addAction(UIAlertAction(title: L10n.string("remote_mic_scan_qr"), style: .default) {
                [weak self] _ in self?.scanTapped()
            })
            sheet.addAction(UIAlertAction(title: L10n.string("remote_mic_picker_ip"), style: .default) {
                [weak self] _ in self?.askForIp()
            })
            for host in relayClient.hostNames() {
                sheet.addAction(UIAlertAction(title: String(format: L10n.string("voice_robot_wifi_item"), host),
                                              style: .default) { [weak self] _ in
                    self?.relayClient.chooseHost(host)
                    self?.updateStatus()
                })
            }
        }
        sheet.addAction(UIAlertAction(title: L10n.string("voice_cancel"), style: .cancel))
        if let popover = sheet.popoverPresentationController {
            popover.sourceView = pill
            popover.sourceRect = pill.bounds
        }
        present(sheet, animated: true)
    }

    @objc private func scanTapped() {
        let scanner = QrScannerController()
        scanner.onCode = { [weak self] code in
            guard let self = self else { return }
            if self.internet {
                guard let qr = RelayQr.parseNet(code) else {
                    self.alert(L10n.string("internet_mic_qr_invalid"))
                    return
                }
                self.net.setCode(qr, raw: code)
            } else {
                guard let qr = RelayQr.parse(code) else {
                    self.alert(L10n.string("remote_mic_qr_invalid"))
                    return
                }
                self.relayClient.connectDirect(ip: qr.ip, port: qr.port, name: qr.name)
            }
            self.updateStatus()
        }
        scanner.modalPresentationStyle = .fullScreen
        present(scanner, animated: true)
    }

    private func askForIp() {
        let box = UIAlertController(title: L10n.string("remote_mic_ip_title"),
                                    message: L10n.string("remote_mic_ip_message"), preferredStyle: .alert)
        box.addTextField { field in
            field.keyboardType = .decimalPad
            field.placeholder = "192.168.1.23"
        }
        box.addAction(UIAlertAction(title: L10n.string("voice_cancel"), style: .cancel))
        box.addAction(UIAlertAction(title: L10n.string("voice_relay_pin_connect"), style: .default) {
            [weak self, weak box] _ in
            let ip = box?.textFields?.first?.text?.trimmingCharacters(in: .whitespaces) ?? ""
            guard ip.range(of: "^\\d{1,3}(\\.\\d{1,3}){3}$", options: .regularExpression) != nil else {
                self?.alert(L10n.string("remote_mic_ip_invalid"))
                return
            }
            self?.relayClient.connectDirect(ip: ip, port: RelayQr.relayPort, name: ip)
            self?.updateStatus()
        })
        present(box, animated: true)
    }

    private func alert(_ message: String) {
        guard presentedViewController == nil else { return }
        let box = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        box.addAction(UIAlertAction(title: L10n.string("okAction"), style: .default))
        present(box, animated: true)
    }
}
