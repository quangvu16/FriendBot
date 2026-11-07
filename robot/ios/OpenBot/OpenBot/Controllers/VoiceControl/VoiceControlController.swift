//
//  VoiceControlController.swift
//  OpenBot
//
//  Voice Control screen: talk to the robot. Mirrors VoiceControlFragment on Android.
//
//  Voice Control v3: the screen is a true XiaoZhi client. The microphone streams
//  16 kHz mono PCM16 as raw opus binary frames to the persistent Xiaozhi websocket
//  session (XiaozhiSession) and the server runs ASR/LLM; its TTS comes back as raw opus
//  binary frames which are decoded and played here. That opus stream is the ONLY voice
//  output of this screen: no local speech recognition, no local TTS (AVSpeechSynthesizer
//  is never called here), never Apple/Google voice. Typed text-in still works
//  (listen/detect), and when the session is missing the Xiaozhi REST brain (or, offline,
//  the keyword interpreter) turns the words into a VoiceCommand instead. Either way the
//  command is driven out through the normal vehicle link as "c<left>,<right>" messages -
//  exactly what firmware/openbot-esp32s3 understands. Moving commands run for their
//  duration and then auto-stop; while they run the drive values are resent every 250 ms,
//  which also feeds the firmware's heartbeat failsafe. Leaving the screen stops the
//  robot. Commands go through sendData like every other screen, so the STOP latch and
//  object avoidance still apply.
//
//  Redesign (Lily-style): an emotional face hero shows the emotion of the current
//  exchange — the server's llm emotion/emoji first, content heuristics over the
//  request/reply text when the server sends none, state defaults otherwise (Ready 😊 /
//  Listening 🙂 / Thinking 🤔 / Speaking 😄). A live level waveform (mic RMS while the
//  uplink streams, playback level while TTS plays) sits between the face and the bubble
//  transcript (You: right / Xiaozhi: left / "»" action chips centered). Hands-free
//  listening is the default (listen start mode=auto, keep streaming, server VAD ends
//  utterances; the uplink pauses during TTS playback and re-arms after playback drains);
//  the toggle under the state caption switches to push-to-talk (mode=manual, tap to
//  start/stop). Mic tap during Speaking is barge-in (player flush + abort + listen start).
//
//  First run pairs the phone like a real XiaoZhi device (whisplay-xiaozhi style): the screen
//  shows a verification code for xiaozhi.me in place of the controls and connects
//  automatically once the device is bound. Saved credentials (act_ws_token) skip pairing on
//  every later visit.
//

import AVFoundation
import UIKit

final class VoiceControlController: UIViewController {

    private let bluetooth = bluetoothDataController.shared
    private let relayServer = VoiceRelayServer.shared
    private let relayClient = VoiceRelayClient.shared

    private static let driveTickSeconds = 0.25
    /// How long to wait for the first "s<cm>" report after asking for sonar readings.
    private static let sonarSettleSeconds = 0.9
    private static let maxLogLines = 8
    private static let maxConversationEntries = 40
    /// How long the assistant gets to answer a text-in utterance before the built-in brain
    /// takes over.
    private static let assistantWaitSeconds = 3.5
    /// Safety net: Thinking falls back to Listening/Ready when no tts ever starts.
    private static let thinkingTimeoutSeconds = 5.0
    /// Suppress duplicate "You:" bubbles when the server echoes typed text back as stt.
    private static let userEchoWindowSeconds = 2.0
    /// Voice language picker (gear -> "Voice language"); "system" follows the phone locale.
    private static let voiceLangKey = "voice_lang"
    private static let defaultVoiceLang = "vi-VN"
    private static let systemVoiceLang = "system"
    /// Saved listen mode: "handsfree" (default) or "ptt".
    private static let listenModeKey = "voice_listen_mode"
    private static let modeKeyHandsfree = "handsfree"
    private static let modeKeyPtt = "ptt"
    /// Persisted lens choice for the corner preview (back camera by default).
    private static let cameraFrontKey = "voice_camera_front"
    /// Literal endonyms on purpose: the picker shows each language in its own script.
    private static let voiceLanguages: [(tag: String, name: String)] = [
        ("vi-VN", "Tiếng Việt"),
        ("en-US", "English"),
        ("zh-CN", "中文"),
        ("ja-JP", "日本語"),
        ("ko-KR", "한국어"),
        ("th-TH", "ไทย"),
        ("fr-FR", "Français"),
        ("de-DE", "Deutsch"),
        ("es-ES", "Español"),
        ("ru-RU", "Русский"),
        ("pt-BR", "Português"),
        ("hi-IN", "हिन्दी"),
    ]

    // MARK: UI

    private let connectionLabel = UILabel()
    /// The tappable status pill; opens the robot picker (this phone's link vs shared robots).
    private let connectionCapsule = UIView()
    private let settingsButton = UIButton(type: .system)
    private let pairingPanel = UIStackView()
    private let pairCodeLabel = UILabel()
    private let pairStatusLabel = UILabel()
    private let pairRetryButton = UIButton(type: .system)
    private let contentStack = UIStackView()
    private let faceView = VoiceFaceView()
    private let stateIconView = UIImageView()
    private let stateLabel = UILabel()
    private let modeToggle = UISegmentedControl(items: [
        L10n.string("voice_mode_handsfree"), L10n.string("voice_mode_ptt"),
    ])
    private let waveformView = VoiceWaveformView()
    private let conversationScroll = UIScrollView()
    private let conversationStack = UIStackView()
    private let micButton = UIButton(type: .system)
    private let typedField = UITextField()
    private let speedSlider = UISlider()
    private let speedValueLabel = UILabel()
    private let logLabel = UILabel()

    // Corner camera ("the eye"): live preview of the chosen lens + shutter still + lens flip.
    private let cameraCorner = UIView()
    private let cameraPreviewHost = CameraPreviewHost()
    private let cameraStill = UIImageView()
    private let cameraFlipButton = UIButton(type: .system)
    private var cameraFront = false
    private var cameraStillWork: DispatchWorkItem?

    // MARK: Xiaozhi

    private let session = XiaozhiSession()
    private var activator: XiaozhiActivator?

    // MARK: State machine (Ready / Connecting / Pairing / Listening / Thinking / Speaking)

    private enum UiState {
        case ready, connecting, pairing, listening, thinking, speaking
    }

    private var uiState: UiState = .ready
    /// Hands-free (default) vs push-to-talk; toggled under the state caption.
    private var listenHandsFree = true
    /// Think-again guard: THINKING is bounded by the safety timeout below.
    private var thinkingTimer: Timer?

    // MARK: Utterance bookkeeping

    /// Transcript rows as chat bubbles: You: right, Xiaozhi: left, "»" action chips centered.
    private enum RowKind {
        case user, bot, action
    }

    private struct ConvRow {
        let kind: RowKind
        let line: String
    }

    private var conversationRows: [ConvRow] = []
    /// Recent user/assistant turns, fed to the REST brain so follow-ups ("faster") work.
    private var chatTurns: [(role: String, text: String)] = []
    /// Invalidates stale utterance-window callbacks when a newer utterance starts.
    private var utteranceSeq = 0
    /// Did anything come back from the server for this utterance (stt / text / tool)?
    private var utteranceTraffic = false
    /// Did this utterance already move the robot (MCP tools/call)?
    private var utteranceToolCall = false
    /// The 3.5 s text-in window timer.
    private var assistantWait: Timer?
    /// Duplicate-echo guard for the "You:" bubble (typed text echoed back as stt).
    private var lastUserText: String?
    private var lastUserTextAt: TimeInterval = 0

    // MARK: Emotional face

    /// Face emoji of the current exchange; nil = fall back to the state default.
    private var turnEmoji: String?

    // MARK: Audio (opus mic uplink + opus TTS downlink)

    private let audioEngine = AVAudioEngine()
    private let playerNode = AVAudioPlayerNode()
    private var playerAttached = false
    private var tapInstalled = false
    /// Converts mic input to 16 kHz mono Int16 for the opus encoder.
    private var micConverter: AVAudioConverter?
    private var opusEncoder: OpusCodecEncoder?
    private var opusDecoder: OpusCodecDecoder?
    /// The format the player node is connected with (mono Int16 at the server rate).
    private var playbackFormat: AVAudioFormat?
    private var pcmAccumulator: [Int16] = []
    /// Decoded-but-not-yet-rendered TTS buffers; > 0 means the assistant is audible.
    private var queuedPlayCount = 0
    /// True while TTS frames are queued or still playing out (drain = hands-free re-arm).
    private var playbackBusy = false
    /// The drain quiet-window check (the queued audio has played out).
    private var drainWork: DispatchWorkItem?
    /// True while microphone frames are streaming to the server (uplink armed).
    private var micStreaming = false
    /// True while a remote phone's microphone (its Remote Mic page) feeds this page's session.
    private var remoteMicActive = false

    // MARK: Driving

    private var driveTimer: Timer?
    private var driveLeft: Float = 0
    private var driveRight: Float = 0
    private var moveEndsAt: TimeInterval = 0
    /// Remaining legs of a choreographed move (triangle, circle, 360° spin…); see advanceScript().
    private var scriptQueue: [DriveSegment] = []
    /// Last lights state sent to the car, so the Lights tile can toggle.
    private var lightsOn = false
    private var speedScale = VoiceCommand.defaultSpeed
    private var logLines: [String] = []

    // MARK: Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        // Friendly Apple layout: grouped ground, white cards. The big title lives in
        // the content (see layout()), so the pushed screen keeps a clean nav bar.
        view.backgroundColor = .systemGroupedBackground
        // Listen mode is a persisted choice; hands-free is the default.
        listenHandsFree = UserDefaults.standard.string(forKey: Self.listenModeKey) != Self.modeKeyPtt
        layout()
        NotificationCenter.default.addObserver(self, selector: #selector(updateConnection),
                                               name: .bluetoothConnected, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(updateConnection),
                                               name: .bluetoothDisconnected, object: nil)
        wireSession()
        relayClient.onStateChange = { [weak self] in self?.updateConnection() }
        appendLog(L10n.string("voice_log_ready"))
        setUiState(.ready)
        updateConnection()
        startOrPair()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        // A Remote Mic page on another phone feeds this page's session while it is on screen.
        relayServer.setMicSink(self)
        // Another phone's shared robot asks for its PIN before this phone may drive it.
        // (promptForPin retries while this page is mid-transition — do not drop it here.)
        relayClient.onPinRequired = { [weak self] host, wrong in
            guard let self = self else { return }
            VoiceRelayClient.promptForPin(from: self, host: host, wrong: wrong)
        }

        maybeStartCamera()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        // Never leave the car driving itself off screen.
        stopMoving()
        stopMicSession(notify: session.isReady)
        remoteMicActive = false
        relayServer.setMicSink(nil)
        assistantWait?.invalidate()
        thinkingTimer?.invalidate()
        flushPlayback()
        teardownAudio()
        photoExplainer.teardown()
        cameraStillWork?.cancel()
        // Only a real departure ends the session (alerts present over us as well).
        if isMovingFromParent || isBeingDismissed {
            // Share the robot only while this page is open.
            relayServer.stop()
            session.disconnect()
            activator?.cancel()
            activator = nil
        }
    }

    deinit {
        driveTimer?.invalidate()
        assistantWait?.invalidate()
        thinkingTimer?.invalidate()
        drainWork?.cancel()
    }

    // MARK: Layout

    private func layout() {
        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scroll)
        let stack = UIStackView()
        stack.axis = .vertical
        stack.spacing = 10
        stack.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(stack)
        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            // 64 pt at the top keeps the screen clear of the floating STOP button.
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 64),
            stack.leadingAnchor.constraint(equalTo: scroll.frameLayoutGuide.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(equalTo: scroll.frameLayoutGuide.trailingAnchor, constant: -16),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -24),
        ])

        // Corner camera ("the eye"): live preview of the chosen lens, flip button, shutter still.
        // Pinned to the view (not the scroll) at the top-right, clear of the settings gear.
        cameraCorner.backgroundColor = .black
        cameraCorner.layer.cornerRadius = 14
        cameraCorner.layer.borderWidth = 1
        cameraCorner.layer.borderColor = UIColor.white.withAlphaComponent(0.2).cgColor
        cameraCorner.clipsToBounds = true
        cameraCorner.isHidden = true
        cameraCorner.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(cameraCorner)
        cameraPreviewHost.translatesAutoresizingMaskIntoConstraints = false
        cameraCorner.addSubview(cameraPreviewHost)
        cameraStill.contentMode = .scaleAspectFill
        cameraStill.clipsToBounds = true
        cameraStill.isHidden = true
        cameraStill.translatesAutoresizingMaskIntoConstraints = false
        cameraCorner.addSubview(cameraStill)
        cameraFlipButton.setImage(UIImage(systemName: "arrow.triangle.2.circlepoint.camera"), for: .normal)
        cameraFlipButton.tintColor = .white
        cameraFlipButton.backgroundColor = UIColor.black.withAlphaComponent(0.4)
        cameraFlipButton.layer.cornerRadius = 16
        cameraFlipButton.accessibilityLabel = L10n.string("voice_camera_switch")
        cameraFlipButton.addTarget(self, action: #selector(flipCamera), for: .touchUpInside)
        cameraFlipButton.translatesAutoresizingMaskIntoConstraints = false
        cameraCorner.addSubview(cameraFlipButton)
        cameraCorner.addGestureRecognizer(
            UITapGestureRecognizer(target: self, action: #selector(manualLookAround)))
        NSLayoutConstraint.activate([
            cameraCorner.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 112),
            cameraCorner.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -8),
            cameraCorner.widthAnchor.constraint(equalToConstant: 88),
            cameraCorner.heightAnchor.constraint(equalToConstant: 118),
            cameraPreviewHost.topAnchor.constraint(equalTo: cameraCorner.topAnchor),
            cameraPreviewHost.bottomAnchor.constraint(equalTo: cameraCorner.bottomAnchor),
            cameraPreviewHost.leadingAnchor.constraint(equalTo: cameraCorner.leadingAnchor),
            cameraPreviewHost.trailingAnchor.constraint(equalTo: cameraCorner.trailingAnchor),
            cameraStill.topAnchor.constraint(equalTo: cameraCorner.topAnchor),
            cameraStill.bottomAnchor.constraint(equalTo: cameraCorner.bottomAnchor),
            cameraStill.leadingAnchor.constraint(equalTo: cameraCorner.leadingAnchor),
            cameraStill.trailingAnchor.constraint(equalTo: cameraCorner.trailingAnchor),
            cameraFlipButton.trailingAnchor.constraint(equalTo: cameraCorner.trailingAnchor, constant: -4),
            cameraFlipButton.bottomAnchor.constraint(equalTo: cameraCorner.bottomAnchor, constant: -4),
            cameraFlipButton.widthAnchor.constraint(equalToConstant: 32),
            cameraFlipButton.heightAnchor.constraint(equalToConstant: 32),
        ])

        // 1. Large title + settings gear, then the connection status as a pill capsule.
        let bigTitle = UILabel()
        bigTitle.text = L10n.string("tile_voice_control")
        bigTitle.font = .systemFont(ofSize: 32, weight: .bold)
        bigTitle.textColor = .label
        settingsButton.setImage(UIImage(systemName: "gearshape"), for: .normal)
        settingsButton.accessibilityLabel = L10n.string("voice_xiaozhi_settings")
        settingsButton.addTarget(self, action: #selector(settingsTapped), for: .touchUpInside)
        settingsButton.translatesAutoresizingMaskIntoConstraints = false
        settingsButton.widthAnchor.constraint(equalToConstant: 44).isActive = true
        settingsButton.heightAnchor.constraint(equalToConstant: 44).isActive = true
        let titleRow = UIStackView(arrangedSubviews: [bigTitle, settingsButton])
        titleRow.axis = .horizontal
        titleRow.alignment = .center
        titleRow.spacing = 8
        stack.addArrangedSubview(titleRow)

        let connectionIcon = UIImageView(image: UIImage(systemName: "antenna.radiowaves.left.and.right"))
        connectionIcon.tintColor = .secondaryLabel
        connectionIcon.contentMode = .scaleAspectFit
        connectionIcon.translatesAutoresizingMaskIntoConstraints = false
        connectionIcon.widthAnchor.constraint(equalToConstant: 14).isActive = true
        connectionIcon.heightAnchor.constraint(equalToConstant: 14).isActive = true
        connectionIcon.setContentHuggingPriority(.required, for: .horizontal)
        connectionLabel.font = .systemFont(ofSize: 12, weight: .semibold)
        connectionLabel.textColor = .secondaryLabel
        connectionLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        let capsule = connectionCapsule
        capsule.backgroundColor = .systemGray5
        capsule.layer.cornerRadius = 999
        // A chevron marks the pill as tappable: it opens the robot picker.
        let chevron = UIImageView(image: UIImage(systemName: "chevron.up.chevron.down"))
        chevron.tintColor = .secondaryLabel
        chevron.contentMode = .scaleAspectFit
        chevron.translatesAutoresizingMaskIntoConstraints = false
        chevron.widthAnchor.constraint(equalToConstant: 8).isActive = true
        chevron.heightAnchor.constraint(equalToConstant: 12).isActive = true
        chevron.setContentHuggingPriority(.required, for: .horizontal)
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
        let capsuleRow = UIStackView(arrangedSubviews: [capsule])
        capsuleRow.axis = .horizontal
        stack.addArrangedSubview(capsuleRow)

        // First-time pairing panel: replaces the controls until the device is bound.
        pairingPanel.axis = .vertical
        pairingPanel.spacing = 12
        stack.addArrangedSubview(pairingPanel)

        let pairTitle = UILabel()
        pairTitle.text = L10n.string("voice_pair_title")
        pairTitle.font = .systemFont(ofSize: 22, weight: .bold)
        pairTitle.textAlignment = .center
        pairingPanel.addArrangedSubview(pairTitle)

        pairCodeLabel.font = .monospacedSystemFont(ofSize: 44, weight: .bold)
        pairCodeLabel.textAlignment = .center
        pairCodeLabel.adjustsFontSizeToFitWidth = true
        pairCodeLabel.minimumScaleFactor = 0.5
        pairCodeLabel.accessibilityLabel = L10n.string("voice_pair_title")
        pairingPanel.addArrangedSubview(pairCodeLabel)

        let pairInstructions = UILabel()
        pairInstructions.text = L10n.string("voice_pair_instructions")
        pairInstructions.font = .systemFont(ofSize: 15)
        pairInstructions.textColor = .secondaryLabel
        pairInstructions.numberOfLines = 0
        pairingPanel.addArrangedSubview(pairInstructions)

        pairStatusLabel.font = .systemFont(ofSize: 15, weight: .medium)
        pairStatusLabel.textAlignment = .center
        pairStatusLabel.numberOfLines = 0
        pairingPanel.addArrangedSubview(pairStatusLabel)

        pairRetryButton.setTitle(L10n.string("voice_pair_retry"), for: .normal)
        pairRetryButton.addTarget(self, action: #selector(pairRetryTapped), for: .touchUpInside)
        pairRetryButton.isHidden = true
        pairingPanel.addArrangedSubview(pairRetryButton)

        let pairSkipButton = UIButton(type: .system)
        pairSkipButton.setTitle(L10n.string("voice_pair_skip"), for: .normal)
        pairSkipButton.addTarget(self, action: #selector(pairSkipTapped), for: .touchUpInside)
        pairingPanel.addArrangedSubview(pairSkipButton)

        // Everything below is hidden while pairing.
        contentStack.axis = .vertical
        contentStack.spacing = 10
        stack.addArrangedSubview(contentStack)

        // 2. Face hero card: face + state caption + listen-mode toggle + level waveform
        //    grouped on one white rounded card (the friendly Apple grouping).
        let heroCard = UIView()
        heroCard.backgroundColor = .secondarySystemGroupedBackground
        heroCard.layer.cornerRadius = 22
        heroCard.layer.shadowColor = UIColor.black.cgColor
        heroCard.layer.shadowOpacity = 0.07
        heroCard.layer.shadowRadius = 3
        heroCard.layer.shadowOffset = CGSize(width: 0, height: 1)
        let heroStack = UIStackView()
        heroStack.axis = .vertical
        heroStack.spacing = 10
        heroStack.translatesAutoresizingMaskIntoConstraints = false
        heroCard.addSubview(heroStack)
        NSLayoutConstraint.activate([
            heroStack.topAnchor.constraint(equalTo: heroCard.topAnchor, constant: 18),
            heroStack.bottomAnchor.constraint(equalTo: heroCard.bottomAnchor, constant: -18),
            heroStack.leadingAnchor.constraint(equalTo: heroCard.leadingAnchor, constant: 16),
            heroStack.trailingAnchor.constraint(equalTo: heroCard.trailingAnchor, constant: -16),
        ])

        faceView.translatesAutoresizingMaskIntoConstraints = false
        faceView.heightAnchor.constraint(equalToConstant: 136).isActive = true
        heroStack.addArrangedSubview(faceView)

        stateIconView.contentMode = .scaleAspectFit
        stateIconView.tintColor = .secondaryLabel
        stateIconView.translatesAutoresizingMaskIntoConstraints = false
        stateIconView.widthAnchor.constraint(equalToConstant: 16).isActive = true
        stateIconView.heightAnchor.constraint(equalToConstant: 16).isActive = true
        stateLabel.font = .systemFont(ofSize: 14, weight: .semibold)
        stateLabel.textAlignment = .center
        stateLabel.setContentHuggingPriority(.required, for: .horizontal)
        // Centered icon + caption pair (a full-width row keeps the pair centered).
        let stateRow = UIView()
        let stateInner = UIStackView(arrangedSubviews: [stateIconView, stateLabel])
        stateInner.axis = .horizontal
        stateInner.spacing = 6
        stateInner.alignment = .center
        stateInner.translatesAutoresizingMaskIntoConstraints = false
        stateRow.addSubview(stateInner)
        NSLayoutConstraint.activate([
            stateInner.centerXAnchor.constraint(equalTo: stateRow.centerXAnchor),
            stateInner.topAnchor.constraint(equalTo: stateRow.topAnchor),
            stateInner.bottomAnchor.constraint(equalTo: stateRow.bottomAnchor),
        ])
        heroStack.addArrangedSubview(stateRow)

        modeToggle.selectedSegmentIndex = listenHandsFree ? 0 : 1
        modeToggle.addTarget(self, action: #selector(modeChanged), for: .valueChanged)
        modeToggle.backgroundColor = .systemGray5
        modeToggle.selectedSegmentTintColor = .white
        modeToggle.layer.cornerRadius = 12
        modeToggle.setTitleTextAttributes(
            [.foregroundColor: UIColor.secondaryLabel,
             .font: UIFont.systemFont(ofSize: 13, weight: .semibold)], for: .normal)
        modeToggle.setTitleTextAttributes(
            [.foregroundColor: UIColor.label,
             .font: UIFont.systemFont(ofSize: 13, weight: .semibold)], for: .selected)
        let modeRow = UIView()
        modeToggle.translatesAutoresizingMaskIntoConstraints = false
        modeRow.addSubview(modeToggle)
        NSLayoutConstraint.activate([
            modeToggle.centerXAnchor.constraint(equalTo: modeRow.centerXAnchor),
            modeToggle.topAnchor.constraint(equalTo: modeRow.topAnchor),
            modeToggle.bottomAnchor.constraint(equalTo: modeRow.bottomAnchor),
        ])
        heroStack.addArrangedSubview(modeRow)

        // 3. Lily-style level waveform (mic RMS / playback level), on the hero card.
        waveformView.translatesAutoresizingMaskIntoConstraints = false
        waveformView.heightAnchor.constraint(equalToConstant: 40).isActive = true
        heroStack.addArrangedSubview(waveformView)
        contentStack.addArrangedSubview(heroCard)

        // 4. Conversation transcript: bubbles, newest last ("You:" / "Xiaozhi:" / "»").
        conversationScroll.translatesAutoresizingMaskIntoConstraints = false
        conversationStack.translatesAutoresizingMaskIntoConstraints = false
        conversationStack.axis = .vertical
        conversationStack.spacing = 6
        conversationScroll.addSubview(conversationStack)
        NSLayoutConstraint.activate([
            conversationStack.topAnchor.constraint(equalTo: conversationScroll.contentLayoutGuide.topAnchor, constant: 4),
            conversationStack.leadingAnchor.constraint(equalTo: conversationScroll.contentLayoutGuide.leadingAnchor),
            conversationStack.trailingAnchor.constraint(equalTo: conversationScroll.contentLayoutGuide.trailingAnchor),
            conversationStack.bottomAnchor.constraint(equalTo: conversationScroll.contentLayoutGuide.bottomAnchor, constant: -4),
            conversationStack.widthAnchor.constraint(equalTo: conversationScroll.frameLayoutGuide.widthAnchor),
            conversationScroll.heightAnchor.constraint(equalToConstant: 200),
        ])
        contentStack.addArrangedSubview(conversationScroll)

        // 5. Controls: circular mic orb + message capsule with the send orb inside.
        var micConfig = UIButton.Configuration.filled()
        micConfig.image = UIImage(systemName: "mic.fill")
        micConfig.cornerStyle = .capsule
        micConfig.baseBackgroundColor = VoiceFaceView.accent
        micButton.configuration = micConfig
        micButton.translatesAutoresizingMaskIntoConstraints = false
        micButton.widthAnchor.constraint(equalToConstant: 56).isActive = true
        micButton.heightAnchor.constraint(equalToConstant: 56).isActive = true
        micButton.accessibilityLabel = L10n.string("voice_tap_to_speak")
        micButton.addTarget(self, action: #selector(micTapped), for: .touchUpInside)
        typedField.borderStyle = .none
        typedField.placeholder = L10n.string("voice_type_hint")
        typedField.autocapitalizationType = .sentences
        typedField.returnKeyType = .send
        typedField.delegate = self
        typedField.font = .systemFont(ofSize: 15)
        var sendConfig = UIButton.Configuration.filled()
        sendConfig.image = UIImage(systemName: "paperplane.fill")
        sendConfig.cornerStyle = .capsule
        sendConfig.baseBackgroundColor = VoiceFaceView.accent
        sendConfig.baseForegroundColor = .white
        sendConfig.preferredSymbolConfigurationForImage =
            UIImage.SymbolConfiguration(pointSize: 15, weight: .semibold)
        let sendButton = UIButton(type: .system)
        sendButton.configuration = sendConfig
        sendButton.accessibilityLabel = L10n.string("voice_send")
        sendButton.addTarget(self, action: #selector(sendTapped), for: .touchUpInside)
        // Message capsule: white rounded field with the send orb tucked at its edge.
        let fieldCapsule = UIView()
        fieldCapsule.backgroundColor = .secondarySystemGroupedBackground
        fieldCapsule.layer.cornerRadius = 26
        fieldCapsule.layer.shadowColor = UIColor.black.cgColor
        fieldCapsule.layer.shadowOpacity = 0.06
        fieldCapsule.layer.shadowRadius = 2
        fieldCapsule.layer.shadowOffset = CGSize(width: 0, height: 1)
        typedField.translatesAutoresizingMaskIntoConstraints = false
        sendButton.translatesAutoresizingMaskIntoConstraints = false
        fieldCapsule.addSubview(typedField)
        fieldCapsule.addSubview(sendButton)
        NSLayoutConstraint.activate([
            typedField.leadingAnchor.constraint(equalTo: fieldCapsule.leadingAnchor, constant: 16),
            typedField.trailingAnchor.constraint(equalTo: sendButton.leadingAnchor, constant: -6),
            typedField.topAnchor.constraint(equalTo: fieldCapsule.topAnchor, constant: 13),
            typedField.bottomAnchor.constraint(equalTo: fieldCapsule.bottomAnchor, constant: -13),
            sendButton.trailingAnchor.constraint(equalTo: fieldCapsule.trailingAnchor, constant: -6),
            sendButton.centerYAnchor.constraint(equalTo: fieldCapsule.centerYAnchor),
            sendButton.widthAnchor.constraint(equalToConstant: 40),
            sendButton.heightAnchor.constraint(equalToConstant: 40),
        ])
        let controlsRow = UIStackView(arrangedSubviews: [micButton, fieldCapsule])
        controlsRow.axis = .horizontal
        controlsRow.spacing = 10
        controlsRow.alignment = .center
        contentStack.addArrangedSubview(controlsRow)

        // Quick buttons: Forward / Backward / Turn left / Turn right / Stop (no lights).
        let quickRow = UIStackView(arrangedSubviews: [
            quickButton(symbol: "arrow.up", label: L10n.string("voice_forward"), action: #selector(tapForward)),
            quickButton(symbol: "arrow.down", label: L10n.string("voice_backward"), action: #selector(tapBackward)),
            quickButton(symbol: "arrow.left", label: L10n.string("voice_left"), action: #selector(tapLeft)),
            quickButton(symbol: "arrow.right", label: L10n.string("voice_right"), action: #selector(tapRight)),
            quickButton(symbol: "stop.fill", label: L10n.string("voice_stop"),
                        tint: UIColor(red: 0.843, green: 0, blue: 0.082, alpha: 1),
                        background: UIColor(red: 0.992, green: 0.925, blue: 0.918, alpha: 1),
                        action: #selector(tapStop)),
        ])
        quickRow.axis = .horizontal
        quickRow.spacing = 4
        quickRow.distribution = .fillEqually
        contentStack.addArrangedSubview(quickRow)

        // Trick tiles: lights / sonar / sing / spin 360, then the paths (triangle / circle / …).
        let trickRow = UIStackView(arrangedSubviews: [
            quickButton(symbol: "lightbulb", label: L10n.string("voice_tile_lights"),
                        action: #selector(tapLights)),
            quickButton(symbol: "dot.radiowaves.left.and.right", label: L10n.string("voice_tile_sonar"),
                        action: #selector(tapSonar)),
            quickButton(symbol: "music.note", label: L10n.string("voice_tile_sing"),
                        action: #selector(tapSing)),
            quickButton(symbol: "arrow.triangle.2.circlepath", label: L10n.string("voice_tile_spin"),
                        action: #selector(tapSpin)),
        ])
        trickRow.axis = .horizontal
        trickRow.spacing = 4
        trickRow.distribution = .fillEqually
        contentStack.addArrangedSubview(trickRow)

        let pathRow = UIStackView(arrangedSubviews: [
            quickButton(symbol: "triangle", label: L10n.string("voice_tile_triangle"),
                        action: #selector(tapTriangle)),
            quickButton(symbol: "circle", label: L10n.string("voice_tile_circle"),
                        action: #selector(tapCircle)),
            quickButton(symbol: "arrow.left.arrow.right", label: L10n.string("voice_tile_wiggle"),
                        action: #selector(tapWiggle)),
            quickButton(symbol: "arrow.uturn.left", label: L10n.string("voice_tile_turn"),
                        action: #selector(tapTurn)),
        ])
        pathRow.axis = .horizontal
        pathRow.spacing = 4
        pathRow.distribution = .fillEqually
        contentStack.addArrangedSubview(pathRow)

        // Speed slider, compact single row.
        let speedIcon = UIImageView(image: UIImage(systemName: "gauge"))
        speedIcon.tintColor = .secondaryLabel
        speedIcon.contentMode = .scaleAspectFit
        speedIcon.translatesAutoresizingMaskIntoConstraints = false
        speedIcon.widthAnchor.constraint(equalToConstant: 15).isActive = true
        speedIcon.heightAnchor.constraint(equalToConstant: 15).isActive = true
        speedIcon.setContentHuggingPriority(.required, for: .horizontal)
        let speedCaption = caption(L10n.string("voice_speed"))
        speedValueLabel.font = .systemFont(ofSize: 13, weight: .semibold)
        speedValueLabel.textColor = .secondaryLabel
        speedSlider.minimumValue = 0.25
        speedSlider.maximumValue = 1.0
        speedSlider.value = speedScale
        speedSlider.accessibilityLabel = L10n.string("voice_speed")
        speedSlider.addTarget(self, action: #selector(speedChanged), for: .valueChanged)
        let speedRow = UIStackView(arrangedSubviews: [speedIcon, speedCaption, speedSlider, speedValueLabel])
        speedRow.axis = .horizontal
        speedRow.spacing = 8
        speedRow.alignment = .center
        // Speed lives on its own white card row.
        let speedCard = UIView()
        speedCard.backgroundColor = .secondarySystemGroupedBackground
        speedCard.layer.cornerRadius = 16
        speedCard.layer.shadowColor = UIColor.black.cgColor
        speedCard.layer.shadowOpacity = 0.06
        speedCard.layer.shadowRadius = 2
        speedCard.layer.shadowOffset = CGSize(width: 0, height: 1)
        speedRow.translatesAutoresizingMaskIntoConstraints = false
        speedCard.addSubview(speedRow)
        NSLayoutConstraint.activate([
            speedRow.topAnchor.constraint(equalTo: speedCard.topAnchor, constant: 11),
            speedRow.bottomAnchor.constraint(equalTo: speedCard.bottomAnchor, constant: -11),
            speedRow.leadingAnchor.constraint(equalTo: speedCard.leadingAnchor, constant: 14),
            speedRow.trailingAnchor.constraint(equalTo: speedCard.trailingAnchor, constant: -14),
        ])
        contentStack.addArrangedSubview(speedCard)
        showSpeed()

        // Small log kept at the bottom (the conversation view above is the main content).
        contentStack.addArrangedSubview(caption(L10n.string("voice_log"), symbol: "text.alignleft"))
        logLabel.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        logLabel.textColor = .secondaryLabel
        logLabel.numberOfLines = 0
        contentStack.addArrangedSubview(logLabel)
    }

    /// Small secondary label, optionally preceded by an SF Symbol icon.
    private func caption(_ text: String, symbol: String? = nil) -> UIView {
        let label = UILabel()
        label.text = text
        label.font = .systemFont(ofSize: 13, weight: .semibold)
        label.textColor = .secondaryLabel
        guard let symbol = symbol else { return label }
        let icon = UIImageView(image: UIImage(systemName: symbol))
        icon.tintColor = .secondaryLabel
        icon.contentMode = .scaleAspectFit
        icon.translatesAutoresizingMaskIntoConstraints = false
        icon.widthAnchor.constraint(equalToConstant: 14).isActive = true
        icon.heightAnchor.constraint(equalToConstant: 14).isActive = true
        icon.setContentHuggingPriority(.required, for: .horizontal)
        let row = UIStackView(arrangedSubviews: [icon, label])
        row.axis = .horizontal
        row.spacing = 5
        row.alignment = .center
        return row
    }

    private func quickButton(symbol: String, label: String,
                             tint: UIColor = UIColor(red: 0.23, green: 0.23, blue: 0.24, alpha: 1),
                             background: UIColor = .secondarySystemGroupedBackground,
                             action: Selector) -> UIButton {
        let button = UIButton(type: .system)
        var config = UIButton.Configuration.filled()
        config.title = label
        config.titleAlignment = .center
        config.image = UIImage(systemName: symbol)
        config.imagePlacement = .top
        config.imagePadding = 3
        config.preferredSymbolConfigurationForImage =
            UIImage.SymbolConfiguration(pointSize: 16, weight: .semibold)
        // White rounded tiles (Stop overrides both tint and background). The 16 pt
        // radius comes from clipping the layer — CornerStyle has no custom case.
        config.cornerStyle = .large
        config.baseBackgroundColor = background
        config.baseForegroundColor = tint
        config.titleLineBreakMode = .byTruncatingTail
        config.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in
            var outgoing = incoming
            outgoing.font = .systemFont(ofSize: 11, weight: .semibold)
            return outgoing
        }
        button.configuration = config
        button.layer.cornerRadius = 16
        button.clipsToBounds = true
        button.addTarget(self, action: action, for: .touchUpInside)
        button.accessibilityLabel = label
        return button
    }

    // MARK: Pairing (first-time device binding)

    /// Saved session credentials? Then skip pairing and connect. Otherwise show the pairing
    /// panel in place of the controls until the device is bound (or the user opts out).
    private func startOrPair() {
        if XiaozhiActivator.isBound {
            showPairing(false)
            session.connect()
        } else {
            showPairing(true)
            startPairing()
        }
    }

    private func showPairing(_ pairing: Bool) {
        pairingPanel.isHidden = !pairing
        contentStack.isHidden = pairing
    }

    private func startPairing() {
        pairRetryButton.isHidden = true
        pairStatusLabel.text = L10n.string("voice_xiaozhi_login_waiting")
        setUiState(.pairing)
        let activator = XiaozhiActivator()
        self.activator = activator
        activator.start((onCode: { [weak self] code in
            self?.pairCodeLabel.text = String(format: L10n.string("voice_pair_code"), code)
            self?.pairStatusLabel.text = L10n.string("voice_pair_waiting")
        }, onBound: { [weak self] _, _ in
            self?.pairStatusLabel.text = L10n.string("voice_pair_bound")
            self?.activator = nil
            self?.appendLog(L10n.string("voice_xiaozhi_login_success"))
            self?.pairingDone()
        }, onError: { [weak self] message in
            self?.pairStatusLabel.text = String(format: L10n.string("voice_xiaozhi_login_error"), message)
            self?.pairRetryButton.isHidden = false
            self?.activator = nil
        }))
    }

    /// Binding succeeded (from the panel or the settings dialog): show the controls and let
    /// the session connect with the freshly saved act_ws_* credentials.
    private func pairingDone() {
        showPairing(false)
        setUiState(.ready)
        session.connect()
        updateConnection()
    }

    @objc private func pairRetryTapped() {
        startPairing()
    }

    /// "Use offline mode": REST/keywords only. The gear's sign-in starts pairing again.
    @objc private func pairSkipTapped() {
        activator?.cancel()
        activator = nil
        showPairing(false)
        setUiState(.ready)
        updateConnection()
    }

    // MARK: Xiaozhi session wiring

    private func wireSession() {
        session.onStateChange = { [weak self] state in
            guard let self = self else { return }
            switch state {
            case .ready:
                // The hello announced the playback rate: one TTS decoder per session.
                self.opusDecoder = OpusCodecDecoder(sampleRate: Int32(self.session.playbackSampleRate),
                                                    channels: 1)
                if self.uiState == .connecting {
                    self.setUiState(.ready)
                }
                self.updateConnection()
                if self.listenHandsFree {
                    // Hands-free default: the robot listens as soon as it is connected.
                    self.armListening()
                }
            case .connecting:
                // No socket to stream to; the next connect sends a fresh listen start.
                self.stopMicCapture()
                self.setUiState(.connecting)
                self.updateConnection()
            case .idle:
                self.stopMicCapture()
                self.setUiState(.ready)
                self.updateConnection()
            }
        }
        session.onStt = { [weak self] text in
            guard let self = self else { return }
            self.utteranceTraffic = true
            let now = ProcessInfo.processInfo.systemUptime
            let echo = self.lastUserText == text
                && now - self.lastUserTextAt < Self.userEchoWindowSeconds
            if !echo {
                // Server-side ASR of a spoken utterance: its "You:" bubble too. The typed
                // path already added its bubble, so skip the echo of the text-in.
                self.beginExchange(text)
                self.appendUserTurn(text)
            }
            if !self.listenHandsFree && self.micStreaming {
                self.stopMicSession(notify: true) // push-to-talk: the utterance is over
            }
            // Server VAD (or the tap) ended the utterance; wait for the reply.
            self.setUiState(.thinking)
            self.armThinkingTimeout()
        }
        session.onTtsSentence = { [weak self] text in
            guard let self = self else { return }
            self.utteranceTraffic = true
            self.appendBotTurn(text)
            // v3: no local TTS - the server's binary opus stream is the voice output.
        }
        session.onTtsState = { [weak self] speaking in
            guard let self = self else { return }
            if speaking {
                // The server started answering: pause the mic uplink (and tell it so).
                self.stopMicSession(notify: true)
                self.setUiState(.speaking)
            } else if self.uiState == .speaking {
                // tts stop: the already-queued buffers finish on their own; the drain
                // callback re-arms hands-free listening once they have played out.
                if !self.playbackBusy {
                    self.finishSpeaking()
                }
            }
        }
        session.onBinaryAudio = { [weak self] data, sampleRate in
            guard let self = self else { return }
            // Per protocol, TTS audio is dropped while the mic stream is open.
            guard !self.micStreaming else { return }
            self.enqueuePlayback(data, sampleRate: sampleRate)
        }
        session.onLlmEmotion = { [weak self] emotion, text in
            self?.applyServerEmotion(emotion, text)
        }
        session.toolHandler = { [weak self] name, args in
            self?.handleToolCall(name, args) ?? false
        }
        session.photoHandler = { [weak self] question, done in
            self?.takePhoto(question: question, done: done)
        }
    }

    // MARK: State machine

    private func setUiState(_ state: UiState) {
        uiState = state
        stateLabel.text = Self.stateCaption(state)
        stateIconView.image = UIImage(systemName: Self.stateSymbol(state))
        stateIconView.tintColor = (state == .listening || state == .speaking)
            ? VoiceFaceView.accent : .secondaryLabel
        stateLabel.textColor = (state == .listening || state == .speaking)
            ? VoiceFaceView.accent : .secondaryLabel
        if state != .listening {
            faceView.setRing(active: false)
        }
        if state != .thinking {
            thinkingTimer?.invalidate()
            thinkingTimer = nil
        }
        if state != .speaking {
            faceView.setBobbing(false)
        }
        updateFace()
        if state == .speaking {
            faceView.setBobbing(true)
        }
        switch state {
        case .ready, .connecting, .pairing:
            waveformView.setLevel(0)
        default:
            break
        }
    }

    private static func stateCaption(_ state: UiState) -> String {
        switch state {
        case .connecting: return L10n.string("voice_state_connecting")
        case .pairing: return L10n.string("voice_pair_waiting")
        case .listening: return L10n.string("voice_state_listening")
        case .thinking: return L10n.string("voice_state_thinking")
        case .speaking: return L10n.string("voice_state_speaking")
        default: return L10n.string("voice_state_idle")
        }
    }

    /// SF Symbol beside the state caption (matches the icon quick buttons).
    private static func stateSymbol(_ state: UiState) -> String {
        switch state {
        case .connecting: return "wifi"
        case .pairing: return "link"
        case .listening: return "waveform"
        case .thinking: return "brain.head.profile"
        case .speaking: return "speaker.wave.2.fill"
        default: return "checkmark.circle"
        }
    }

    /// THINKING is bounded: without a tts start it falls back to listening/ready.
    private func armThinkingTimeout() {
        thinkingTimer?.invalidate()
        thinkingTimer = Timer.scheduledTimer(withTimeInterval: Self.thinkingTimeoutSeconds,
                                             repeats: false) { [weak self] _ in
            self?.resolveThinking()
        }
    }

    private func resolveThinking() {
        guard uiState == .thinking else { return }
        thinkingTimer?.invalidate()
        thinkingTimer = nil
        turnEmoji = nil
        if micStreaming || remoteMicActive {
            setUiState(.listening) // the uplink never stopped
        } else if listenHandsFree && session.isReady {
            armListening()
        } else {
            setUiState(.ready)
        }
    }

    /// TTS playback drained: hands-free re-arms, push-to-talk waits for the next tap.
    private func finishSpeaking() {
        guard uiState == .speaking else { return }
        thinkingTimer?.invalidate()
        thinkingTimer = nil
        turnEmoji = nil
        if remoteMicActive || (listenHandsFree && session.isReady) {
            armListening()
        } else {
            setUiState(.ready)
        }
    }

    // MARK: Mic uplink (hands-free / push-to-talk)

    /// Arms the microphone uplink in the selected listen mode and shows Listening. While the
    /// assistant is speaking this barges in (abort + take the floor). Hands-free sends
    /// listen start mode=auto once and keeps streaming (the server VAD ends utterances);
    /// push-to-talk sends mode=manual (tap to start/stop, v3 behavior).
    private func armListening() {
        if remoteMicActive {
            // The remote phone's microphone is the uplink: reopen it in hands-free mode
            // (its frames keep arriving through the relay, whatever the local mode is).
            if session.isReady {
                session.sendListenStart(mode: XiaozhiSession.modeAuto)
                setUiState(.listening)
            }
            return
        }
        guard session.isReady else {
            return // permission callbacks can arrive after the session is gone
        }
        if micStreaming && uiState == .listening {
            return // already armed
        }
        AVAudioSession.sharedInstance().requestRecordPermission { [weak self] granted in
            DispatchQueue.main.async {
                guard let self = self else { return }
                guard granted else {
                    self.alert(L10n.string("voice_mic_permission_denied"))
                    self.setUiState(.ready)
                    return
                }
                self.startListenSession()
            }
        }
    }

    private func startListenSession() {
        guard session.isReady else { return }
        if micStreaming && uiState == .listening {
            return // already armed
        }
        if uiState == .speaking {
            // Barge-in: cut the assistant off at once, then take the floor.
            flushPlayback()
            session.sendAbort()
        }
        thinkingTimer?.invalidate()
        thinkingTimer = nil
        beginCapture()
        setUiState(micStreaming ? .listening : .ready)
    }

    /// Mic tap. While the uplink streams: end the utterance now (push-to-talk stop, or
    /// hands-free "send") and wait for the reply. While the assistant speaks: barge in.
    /// Otherwise: start listening. Without the websocket the tap only logs voice_ws_offline —
    /// the platform speech recognizers are Google voice and are banned on this screen.
    @objc private func micTapped() {
        guard session.isReady else {
            appendLog(L10n.string("voice_ws_offline"))
            return
        }
        if micStreaming {
            stopMicSession(notify: true)
            setUiState(.thinking)
            armThinkingTimeout()
            return
        }
        armListening()
    }

    /// Ends the uplink (mic tap again, server tts start, or screen exit).
    private func stopMicSession(notify: Bool) {
        let wasStreaming = micStreaming
        stopMicCapture()
        if notify && (wasStreaming || remoteMicActive) && session.isReady {
            session.sendListenStop()
        }
    }

    /// The listen mode changed: close the old-mode session and re-arm (unless mid-exchange).
    @objc private func modeChanged() {
        let handsFree = modeToggle.selectedSegmentIndex == 0
        guard handsFree != listenHandsFree else { return }
        listenHandsFree = handsFree
        UserDefaults.standard.set(handsFree ? Self.modeKeyHandsfree : Self.modeKeyPtt,
                                  forKey: Self.listenModeKey)
        guard session.isReady else { return }
        if uiState == .speaking || uiState == .thinking {
            return // the new mode applies from the next arm / finishSpeaking
        }
        if micStreaming {
            stopMicSession(notify: true) // wire-mode switch restarts the listen session
        }
        if listenHandsFree {
            armListening()
        } else {
            setUiState(.ready) // push-to-talk waits for the mic tap
        }
    }

    // MARK: Mic capture (AVAudioEngine tap -> 16 kHz mono Int16 -> 60 ms opus frames)

    /// Opens the mic: AVAudioEngine tap -> 16 kHz mono Int16 -> 60 ms raw opus frames.
    private func beginCapture() {
        guard !remoteMicActive else {
            return // a remote phone's microphone owns the uplink
        }
        guard session.isReady else {
            appendLog(L10n.string("voice_ws_offline"))
            return
        }
        stopMicCapture()
        do {
            // playAndRecord + defaultToSpeaker keeps audio flowing during TTS playback.
            try AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .default,
                                                            options: [.defaultToSpeaker])
            try AVAudioSession.sharedInstance().setActive(true, options: .notifyOthersOnDeactivation)
        } catch {
            appendLog(error.localizedDescription)
            return
        }

        let input = audioEngine.inputNode
        let inputFormat = input.outputFormat(forBus: 0)
        guard
            let pcmFormat = AVAudioFormat(commonFormat: .pcmFormatInt16,
                                          sampleRate: 16000, channels: 1, interleaved: false),
            let converter = AVAudioConverter(from: inputFormat, to: pcmFormat)
        else {
            appendLog("mic format conversion unavailable")
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
            stopMicCapture()
            appendLog(error.localizedDescription)
            return
        }

        // listen start goes out first; the binary frames are queued behind it.
        session.sendListenStart(mode: listenHandsFree ? XiaozhiSession.modeAuto : XiaozhiSession.modeManual)
        micStreaming = true
    }

    /// Mic tap -> 16 kHz mono Int16 -> 960-sample (60 ms) raw opus packets. Each frame's
    /// RMS (sqrt(sum x^2 / N) / 32768) feeds the waveform bars and the face ring.
    private func consumeMicBuffer(_ buffer: AVAudioPCMBuffer) {
        guard micStreaming, let converter = micConverter, let encoder = opusEncoder else { return }
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
            // Per-frame mic level for the waveform + face ring (main thread).
            let rms = Self.frameRms(frame)
            DispatchQueue.main.async { [weak self] in
                self?.onMicLevel(rms)
            }
            let pcm = frame.withUnsafeBufferPointer { Data(buffer: $0) }
            if let packet = encoder.encodePCM16(pcm) {
                session.sendBinary(packet)
            }
        }
    }

    /// Mic RMS sample: drives the level bars and the pulsing ring around the face.
    private func onMicLevel(_ rms: Float) {
        guard micStreaming || remoteMicActive else { return }
        waveformView.setLevel(rms)
        if uiState == .listening {
            faceView.setRing(active: true, pulse: rms * 3.5)
        } else {
            faceView.setRing(active: false)
        }
    }

    /// Tap off, encoder state dropped. (Listen stop is sent by stopMicSession when wanted.)
    private func stopMicCapture() {
        micStreaming = false
        if tapInstalled {
            audioEngine.inputNode.removeTap(onBus: 0)
            tapInstalled = false
        }
        pcmAccumulator.removeAll(keepingCapacity: true)
        micConverter = nil
        opusEncoder = nil
        waveformView.setLevel(0)
        faceView.setRing(active: false)
    }

    // MARK: TTS playback (server opus -> PCM -> AVAudioPlayerNode)

    /// Builds the player graph at the server playback rate once; the engine converts
    /// the rate on connect. The graph is only ever mutated while the engine is stopped,
    /// and the player connects as Float32 (the engine's native mixer format) — both are
    /// required or AVAudioPlayerNode.play() raises an ObjC exception and kills the app.
    private func prepareAudioGraph() -> Bool {
        if !playerAttached {
            if audioEngine.isRunning {
                audioEngine.stop()
            }
            let rate = Double(max(1, session.playbackSampleRate))
            guard let nodeFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32,
                                                 sampleRate: rate, channels: 1, interleaved: false)
            else { return false }
            audioEngine.attach(playerNode)
            audioEngine.connect(playerNode, to: audioEngine.mainMixerNode, format: nodeFormat)
            playbackFormat = nodeFormat
            playerAttached = true
        }
        if !audioEngine.isRunning {
            audioEngine.prepare()
            do {
                try audioEngine.start()
            } catch {
                appendLog(error.localizedDescription)
                return false
            }
        }
        return true
    }

    /// One raw opus packet from the server -> decoded PCM -> queued for playback in
    /// order. This is the screen's only voice output.
    private func enqueuePlayback(_ packet: Data, sampleRate: Int) {
        let decoder: OpusCodecDecoder
        if let existing = opusDecoder {
            decoder = existing
        } else if let created = OpusCodecDecoder(sampleRate: Int32(sampleRate), channels: 1) {
            opusDecoder = created
            decoder = created
        } else {
            return
        }
        guard let pcm = decoder.decodePacket(packet) else { return }
        guard prepareAudioGraph(), let format = playbackFormat else { return }
        let sampleCount = pcm.count / 2
        guard sampleCount > 0,
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(sampleCount)),
              let dst = buffer.floatChannelData?[0]
        else { return }
        // PCM16 -> Float32 (the mixer's native range) and the packet level in one pass.
        var squareSum: Double = 0
        pcm.withUnsafeBytes { raw in
            let src = raw.bindMemory(to: Int16.self)
            guard let base = src.baseAddress else { return }
            for i in 0..<sampleCount {
                let sample = Float(base[i]) / 32768.0
                dst[i] = sample
                squareSum += Double(sample) * Double(sample)
            }
        }
        buffer.frameLength = AVAudioFrameCount(sampleCount)
        // Playback level feedback: a mild × 0.6 bounce on the waveform while Speaking.
        if uiState == .speaking {
            let rms = Float((squareSum / Double(sampleCount)).squareRoot())
            waveformView.setLevel(rms * 0.6)
        }
        playbackBusy = true
        drainWork?.cancel()
        drainWork = nil
        queuedPlayCount += 1
        playerNode.scheduleBuffer(buffer) { [weak self] in
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.queuedPlayCount = max(0, self.queuedPlayCount - 1)
                if self.queuedPlayCount == 0, self.playbackBusy {
                    self.scheduleDrainCheck()
                }
            }
        }
        if !playerNode.isPlaying, !playerNode.openbot_safePlay() {
            // The engine refused to start (AVFAudio raises ObjC exceptions; safePlay
            // caught it): rebuild the graph from scratch and give it one more try.
            rebuildPlayerGraph()
            if prepareAudioGraph() {
                _ = playerNode.openbot_safePlay()
            }
        }
    }

    /// Drops and recreates the player node graph after a refused start.
    private func rebuildPlayerGraph() {
        playerNode.stop()
        if playerAttached {
            audioEngine.detach(playerNode)
            playerAttached = false
        }
        playbackFormat = nil
    }

    /// Everything queued has rendered: the queue must stay empty past the quiet window
    /// (last audio played out) before the exchange is over and hands-free re-arms.
    private func scheduleDrainCheck() {
        drainWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            self.drainWork = nil
            guard self.queuedPlayCount == 0, self.playbackBusy else { return }
            self.playbackBusy = false
            if self.uiState == .speaking {
                self.finishSpeaking()
            }
        }
        drainWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35, execute: work)
    }

    /// Barge-in / screen exit: cut the assistant off mid-sentence. (A plain tts stop
    /// does NOT come here - queued buffers are left to finish naturally.)
    private func flushPlayback() {
        playerNode.stop()   // drops every queued buffer (completions fire as well)
        queuedPlayCount = 0
        // The cancelled segment never drains; no drain callback for it.
        playbackBusy = false
        drainWork?.cancel()
        drainWork = nil
    }

    /// Full audio teardown on view exit: tap off, playback off, engine down.
    private func teardownAudio() {
        flushPlayback()
        if tapInstalled {
            audioEngine.inputNode.removeTap(onBus: 0)
            tapInstalled = false
        }
        if audioEngine.isRunning {
            audioEngine.stop()
        }
        if playerAttached {
            audioEngine.detach(playerNode)
            playerAttached = false
        }
        playbackFormat = nil
        micConverter = nil
        opusEncoder = nil
        opusDecoder = nil
        pcmAccumulator.removeAll()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// RMS of one PCM16 frame, normalized 0..1 (full scale = 32768).
    private static func frameRms(_ samples: [Int16]) -> Float {
        samples.withUnsafeBufferPointer { frameRms($0.baseAddress, $0.count) }
    }

    private static func frameRms(_ samples: UnsafePointer<Int16>?, _ count: Int) -> Float {
        guard let samples = samples, count > 0 else { return 0 }
        var sum: Double = 0
        for i in 0..<count {
            let s = Double(samples[i])
            sum += s * s
        }
        return Float((sum / Double(count)).squareRoot() / 32768.0)
    }

    // MARK: Emotional face (server emotion > content heuristics > state default)

    /// A new exchange begins: content heuristics on the user's text until the server
    /// says otherwise.
    private func beginExchange(_ text: String) {
        turnEmoji = Self.contentEmoji(text)
        updateFace()
    }

    /// The llm frame's emotion is the primary face source and overrides content heuristics.
    private func applyServerEmotion(_ emotion: String, _ text: String) {
        guard let emoji = Self.serverEmoji(emotion, text) else { return }
        turnEmoji = emoji
        updateFace()
    }

    private func updateFace() {
        faceView.show(emoji: turnEmoji ?? Self.stateDefaultEmoji(uiState))
    }

    /// State defaults when nothing else applies: Ready 😊 / Listening 🙂 / Thinking 🤔 / Speaking 😄.
    private static func stateDefaultEmoji(_ state: UiState) -> String {
        switch state {
        case .listening: return "🙂"
        case .thinking: return "🤔"
        case .speaking: return "😄"
        default: return "😊" // Ready (also connecting/pairing)
        }
    }

    /// Server emotion (llm frame): verbatim when the value already is an emoji, otherwise
    /// token -> face. Unknown tokens map to 😊.
    private static func serverEmoji(_ emotion: String, _ text: String) -> String? {
        if isEmojiText(emotion) { return emotion.trimmingCharacters(in: .whitespaces) }
        if isEmojiText(text) { return text.trimmingCharacters(in: .whitespaces) }
        let trimmed = emotion.trimmingCharacters(in: .whitespaces)
        let token = trimmed.isEmpty ? text : emotion
        return tokenEmoji(token.trimmingCharacters(in: .whitespaces).lowercased())
    }

    /// happy → 😄, excited → 🤩, sad → 😢, angry → 😠, surprised → 😮, love → 😍, fear → 😨, …
    private static func tokenEmoji(_ token: String) -> String {
        if token.contains("excited") { return "🤩" }
        if token.contains("happy") { return "😄" }
        if token.contains("sad") { return "😢" }
        if token.contains("angry") { return "😠" }
        if token.contains("surpris") || token.contains("shock") { return "😮" }
        if token.contains("love") { return "😍" }
        if token.contains("confus") || token.contains("think") || token.contains("puzzl") { return "🤔" }
        if token.contains("fear") || token.contains("scared") || token.contains("afraid") { return "😨" }
        return "😊" // neutral + unknown tokens
    }

    /// Content heuristics over the request/reply text when the server sent no emotion:
    /// question → 🤔, explicit drive command → 😄, stop/emergency → 😮, error/offline
    /// response → 😐. Nil when nothing applies (the state default then owns the face).
    private static func contentEmoji(_ text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let lower = trimmed.lowercased()
        if lower.hasSuffix("?")
            || lower.hasPrefix("what")
            || lower.hasPrefix("why")
            || lower.hasPrefix("how")
            || lower.hasPrefix("tại sao")
            || lower.hasPrefix("为什么") {
            return "🤔"
        }
        let action = VoiceInterpreter.interpret(trimmed).action
        switch action {
        case .forward, .backward, .left, .right, .faster, .slower:
            return "😄" // explicit drive command
        case .stop:
            return "😮" // stop / emergency
        default:
            break
        }
        if lower.contains("emergency") || lower.contains("halt") {
            return "😮" // stop / emergency
        }
        if lower.contains("error")
            || lower.contains("offline")
            || lower.contains("unavailable")
            || lower.contains("failed")
            || lower.contains("not connected")
            || lower.contains("did not understand") {
            return "😐" // error / offline response
        }
        return nil
    }

    /// True when the value is a bare emoji (e.g. "😀"), false for tokens like "happy".
    private static func isEmojiText(_ value: String) -> Bool {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        var hasEmoji = false
        for scalar in trimmed.unicodeScalars {
            if CharacterSet.alphanumerics.contains(scalar) { return false }
            if isEmojiCodepoint(scalar.value) { hasEmoji = true }
        }
        return hasEmoji
    }

    private static func isEmojiCodepoint(_ cp: UInt32) -> Bool {
        (cp >= 0x1F300 && cp <= 0x1FAFF) // pictographs, emoticons, symbols
            || (cp >= 0x2600 && cp <= 0x27BF) // misc symbols + dingbats
            || (cp >= 0x2B00 && cp <= 0x2BFF) // stars, arrows
            || cp == 0x2764 // ❤
            || cp == 0xFE0F // variation selector-16
    }

    // MARK: Conversation transcript (bubbles)

    /// One bubble row: You: right-aligned accent bubble, Xiaozhi: left bubble, "»" chip.
    private func appendUserTurn(_ text: String) {
        chatTurns.append((role: "user", text: text))
        trimChat()
        lastUserText = text
        lastUserTextAt = ProcessInfo.processInfo.systemUptime
        appendConversation(.user, "\(L10n.string("voice_log_you")) \(text)")
    }

    private func appendBotTurn(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        if turnEmoji == nil {
            turnEmoji = Self.contentEmoji(text) // reply content, when no emotion yet
            updateFace()
        }
        chatTurns.append((role: "assistant", text: text))
        trimChat()
        appendConversation(.bot, "\(L10n.string("voice_log_bot")) \(trimmed)")
    }

    /// Appends one transcript row (bubble/chip), newest last, and keeps the list scrolled down.
    private func appendConversation(_ kind: RowKind, _ line: String) {
        let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        conversationRows.append(ConvRow(kind: kind, line: trimmed))
        while conversationRows.count > Self.maxConversationEntries {
            conversationRows.removeFirst()
            if let first = conversationStack.arrangedSubviews.first {
                conversationStack.removeArrangedSubview(first)
                first.removeFromSuperview()
            }
        }
        conversationStack.addArrangedSubview(makeBubbleRow(kind, trimmed))
        DispatchQueue.main.async { [weak self] in
            self?.scrollConversationToBottom()
        }
    }

    private func makeBubbleRow(_ kind: RowKind, _ line: String) -> UIView {
        let wrapper = UIView()
        let bubble = UIView()
        bubble.layer.cornerRadius = 20
        bubble.layer.masksToBounds = true
        let label = UILabel()
        label.numberOfLines = 0
        label.text = line
        label.translatesAutoresizingMaskIntoConstraints = false
        bubble.translatesAutoresizingMaskIntoConstraints = false
        wrapper.addSubview(bubble)
        bubble.addSubview(label)
        NSLayoutConstraint.activate([
            label.topAnchor.constraint(equalTo: bubble.topAnchor, constant: 8),
            label.bottomAnchor.constraint(equalTo: bubble.bottomAnchor, constant: -8),
            label.leadingAnchor.constraint(equalTo: bubble.leadingAnchor, constant: 14),
            label.trailingAnchor.constraint(equalTo: bubble.trailingAnchor, constant: -14),
            bubble.topAnchor.constraint(equalTo: wrapper.topAnchor, constant: 3),
            bubble.bottomAnchor.constraint(equalTo: wrapper.bottomAnchor, constant: -3),
        ])
        switch kind {
        case .user:
            label.font = .systemFont(ofSize: 15)
            label.textColor = .white
            bubble.backgroundColor = VoiceFaceView.accent
            NSLayoutConstraint.activate([
                bubble.trailingAnchor.constraint(equalTo: wrapper.trailingAnchor),
                bubble.leadingAnchor.constraint(greaterThanOrEqualTo: wrapper.leadingAnchor, constant: 48),
            ])
        case .bot:
            label.font = .systemFont(ofSize: 15)
            label.textColor = UIColor(red: 0.11, green: 0.11, blue: 0.12, alpha: 1)
            bubble.backgroundColor = .secondarySystemGroupedBackground
            NSLayoutConstraint.activate([
                bubble.leadingAnchor.constraint(equalTo: wrapper.leadingAnchor),
                bubble.trailingAnchor.constraint(lessThanOrEqualTo: wrapper.trailingAnchor, constant: -48),
            ])
        case .action:
            bubble.layer.cornerRadius = 999
            label.font = .systemFont(ofSize: 11, weight: .semibold)
            label.textColor = UIColor(red: 0.28, green: 0.28, blue: 0.29, alpha: 1)
            bubble.backgroundColor = .systemGray5
            NSLayoutConstraint.activate([
                bubble.centerXAnchor.constraint(equalTo: wrapper.centerXAnchor),
                bubble.leadingAnchor.constraint(greaterThanOrEqualTo: wrapper.leadingAnchor, constant: 24),
                bubble.trailingAnchor.constraint(lessThanOrEqualTo: wrapper.trailingAnchor, constant: -24),
            ])
        }
        return wrapper
    }

    private func scrollConversationToBottom() {
        conversationScroll.layoutIfNeeded()
        let bottom = CGRect(x: 0, y: max(0, conversationScroll.contentSize.height - 1), width: 1, height: 1)
        conversationScroll.scrollRectToVisible(bottom, animated: true)
    }

    private func trimChat() {
        if chatTurns.count > 12 {
            chatTurns.removeFirst(chatTurns.count - 12)
        }
    }

    // MARK: Utterances

    /// One typed sentence on its way to a command: the live Xiaozhi session first (text-in,
    /// 3.5 s to answer), then the REST brain, then the offline keyword interpreter.
    /// (Spoken utterances never come through here: the server's ASR drives the list via
    /// the stt messages and the agent answers on its own.)
    private func onTranscript(_ text: String) {
        beginExchange(text)
        appendUserTurn(text)
        setUiState(.thinking)
        armThinkingTimeout()

        if session.isReady {
            // Text-in to the assistant and give it time to answer (stt/tts/tools traffic).
            utteranceTraffic = false
            utteranceToolCall = false
            utteranceSeq += 1
            let seq = utteranceSeq
            session.sendListen(text: text)
            assistantWait?.invalidate()
            assistantWait = Timer.scheduledTimer(withTimeInterval: Self.assistantWaitSeconds,
                                                 repeats: false) { [weak self] _ in
                self?.onUtteranceWindowClosed(seq: seq, text: text)
            }
        } else {
            restInterpret(text)
            resolveThinking()
        }
    }

    /// The 3.5 s assistant window closed: did the assistant handle the utterance at all?
    private func onUtteranceWindowClosed(seq: Int, text: String) {
        guard seq == utteranceSeq else { return }
        if !utteranceTraffic {
            // Nothing came back: use the built-in brain instead.
            appendLog(L10n.string("voice_assistant_silent"))
            restInterpret(text)
            resolveThinking()
            return
        }
        if !utteranceToolCall {
            // The assistant answered conversationally, but the user clearly commanded the
            // robot: run the command as a safety fallback. (When a tool did run, its "»"
            // row already shows the action.)
            let local = VoiceInterpreter.interpret(text)
            if local.action != .unknown {
                execute(command: local, say: "")
            }
            resolveThinking()
        }
        // When a tool did run, the assistant's tts reply is on its way: THINKING rides
        // into SPEAKING (the 5 s thinking timeout is the safety net).
    }

    /// The saved voice language ("system" follows the phone's locale).
    private var voiceLanguageTag: String {
        let saved = UserDefaults.standard.string(forKey: Self.voiceLangKey) ?? Self.defaultVoiceLang
        return saved == Self.systemVoiceLang ? Locale.current.identifier : saved
    }

    /// REST brain (XiaozhiClient) with the earlier turns as context; keywords on failure.
    private func restInterpret(_ text: String) {
        let config = XiaozhiClient.loadConfig()
        if config.isConfigured {
            XiaozhiClient(config: config).interpret(text: text,
                                                   languageTag: voiceLanguageTag,
                                                   history: Array(chatTurns.dropLast().suffix(6))) { [weak self] result in
                guard let self = self else { return }
                switch result {
                case .success(let reply):
                    self.execute(command: reply.command, say: reply.say)
                case .failure(let error):
                    self.appendLog(String(format: L10n.string("voice_xiaozhi_fallback"),
                                          error.localizedDescription))
                    self.execute(command: VoiceInterpreter.interpret(text), say: "")
                }
            }
        } else {
            execute(command: VoiceInterpreter.interpret(text), say: "")
        }
    }

    // MARK: Driving

    private func execute(command: VoiceCommand, say: String, spoken: Bool = true) {
        if command.action != .chat {
            let summary = describe(command)
            appendLog(command.rawText.isEmpty ? summary : command.rawText + " -> " + summary)
            if !spoken || !say.isEmpty {
                // What the robot did also goes in the conversation list, next to the reply.
                appendConversation(.action, "» \(summary)")
            }
        }
        if command.action == .sing {
            // The tune of "sing and turn around" goes in like a spoken reply (Android parity).
            appendBotTurn(L10n.string("voice_song"))
        }
        if !say.isEmpty {
            appendBotTurn(say)
        } else if spoken && command.action != .chat {
            // The spoken confirmation doubles as the visible list row.
            appendBotTurn(describe(command))
        }

        // v3: no local TTS on this screen - the rows above are all the feedback the
        // phone gives; spoken replies come only from the server's opus stream.
        switch command.action {
        case .faster:
            speedScale = min(1, speedScale + 0.15)
            speedSlider.value = speedScale
            showSpeed()
        case .slower:
            speedScale = max(0.25, speedScale - 0.15)
            speedSlider.value = speedScale
            showSpeed()
        case .chat:
            // Pure conversation: hold still, never drive.
            stopMoving()
        case .unknown:
            stopMoving()
        case .stop:
            stopMoving()
        case .lightsOn:
            guard isBluetoothConnected else {
                alert(L10n.string("voice_not_connected"))
                return
            }
            bluetooth.sendData(payload: "l255,255\n")  // stock OpenBot boards
            sendIndicator(left: Self.ledOn, right: Self.ledOn)  // FriendBot firmware LEDs
            lightsOn = true
        case .lightsOff:
            guard isBluetoothConnected else {
                alert(L10n.string("voice_not_connected"))
                return
            }
            bluetooth.sendData(payload: "l0,0\n")
            sendIndicator(left: Self.ledOff, right: Self.ledOff)
            lightsOn = false
        case .sonar:
            guard isBluetoothConnected else {
                alert(L10n.string("voice_not_connected"))
                return
            }
            // Ask for fast reports, give the first one time to arrive, then read and report it.
            bluetooth.sendData(payload: "s200\n")
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.sonarSettleSeconds) { [weak self] in
                self?.sonarReport()
            }
        case .indicatorLeft:
            guard isBluetoothConnected else {
                alert(L10n.string("voice_not_connected"))
                return
            }
            sendIndicator(left: Self.ledBlink, right: Self.ledOff)
        case .indicatorRight:
            guard isBluetoothConnected else {
                alert(L10n.string("voice_not_connected"))
                return
            }
            sendIndicator(left: Self.ledOff, right: Self.ledBlink)
        case .indicatorHazard:
            guard isBluetoothConnected else {
                alert(L10n.string("voice_not_connected"))
                return
            }
            sendIndicator(left: Self.ledBlink, right: Self.ledBlink)
        case .indicatorOff:
            guard isBluetoothConnected else {
                alert(L10n.string("voice_not_connected"))
                return
            }
            // Signals off: hand the LEDs back to the firmware's automatic driving signals.
            sendIndicator(left: Self.ledAuto, right: Self.ledAuto)
        case .sing, .triangle, .circle, .spin360, .turnAround, .wiggle:
            startScript(command)
        default:
            startMoving(command)
        }
    }

    private func startMoving(_ command: VoiceCommand) {
        guard let wheels = command.toWheels() else { return }
        // The slider rescales the command: at 75 % (the default) the command's own speed is used.
        let scale = speedScale / VoiceCommand.defaultSpeed
        driveLeft = clampUnit(wheels.left * scale)
        driveRight = clampUnit(wheels.right * scale)
        moveEndsAt = ProcessInfo.processInfo.systemUptime + Double(command.durationMs) / 1000
        VoiceRelayServer.shared.noteLocalMove(endsAt: moveEndsAt)
        if relayClient.isHostConnected {
            // The picked target is a robot another phone shares over WiFi (auto mode only
            // connects when this phone has no link of its own; choosing local disconnects it).
            relayClient.sendMove(l: driveLeft, r: driveRight, ms: command.durationMs)
        } else if isBluetoothConnected {
            sendControl(left: driveLeft, right: driveRight)
        } else {
            moveEndsAt = 0
            alert(L10n.string("voice_not_connected"))
            return
        }
        driveTimer?.invalidate()
        driveTimer = Timer.scheduledTimer(withTimeInterval: Self.driveTickSeconds, repeats: true) { [weak self] _ in
            self?.driveTick()
        }
    }

    private func driveTick() {
        if VoiceRelayServer.shared.isStopLatched {
            stopMoving(global: false)
            appendLog(L10n.string("voice_status_stopped"))
            return
        }
        guard ProcessInfo.processInfo.systemUptime < moveEndsAt else {
            if !scriptQueue.isEmpty {
                // One leg of a choreographed move just ended: run the next one.
                advanceScript()
                return
            }
            stopMoving(global: false)
            appendLog(L10n.string("voice_status_stopped"))
            return
        }
        if isBluetoothConnected {
            sendControl(left: driveLeft, right: driveRight)
        }
    }

    /// Starts a choreographed move (triangle, circle, 360° spin…): its legs queue up as segments.
    private func startScript(_ command: VoiceCommand) {
        let segs = command.toScript()
        guard !segs.isEmpty else { return }
        scriptQueue = segs
        advanceScript()
    }

    /// Drives the next leg of the current script. Each leg is a plain timed move — the same
    /// driveTick resend and moveEndsAt auto-stop as a voice move; when a leg expires driveTick
    /// calls back here until the queue runs out and the robot stops.
    private func advanceScript() {
        guard !scriptQueue.isEmpty else {
            stopMoving()
            return
        }
        let seg = scriptQueue.removeFirst()
        let scale = speedScale / VoiceCommand.defaultSpeed
        driveLeft = clampUnit(seg.left * scale)
        driveRight = clampUnit(seg.right * scale)
        moveEndsAt = ProcessInfo.processInfo.systemUptime + Double(seg.ms) / 1000
        VoiceRelayServer.shared.noteLocalMove(endsAt: moveEndsAt)
        if relayClient.isHostConnected {
            relayClient.sendMove(l: driveLeft, r: driveRight, ms: seg.ms)
        } else if isBluetoothConnected {
            sendControl(left: driveLeft, right: driveRight)
        } else {
            stopMoving()
            alert(L10n.string("voice_not_connected"))
            return
        }
        driveTimer?.invalidate()
        driveTimer = Timer.scheduledTimer(withTimeInterval: Self.driveTickSeconds, repeats: true) { [weak self] _ in
            self?.driveTick()
        }
    }

    /// Reads the sonar distance once the first report has had time to arrive, then stops the reports.
    private func sonarReport() {
        let cm = bluetooth.sonarDistanceCm
        bluetooth.sendData(payload: "s0\n")
        appendBotTurn(cm >= 0
            ? String(format: L10n.string("voice_sonar_distance"), cm)
            : L10n.string("voice_sonar_unknown"))
    }

    private func stopMoving() {
        stopMoving(global: true)
    }

    /// A real STOP (`global`) also halts the relay: every phone's move stops and latches
    /// until the next command. A move simply timing out stops only itself.
    private func stopMoving(global: Bool) {
        driveTimer?.invalidate()
        driveTimer = nil
        scriptQueue.removeAll()
        moveEndsAt = 0
        if global {
            VoiceRelayServer.shared.noteLocalStop()
            if relayClient.isHostConnected {
                relayClient.sendStop()
            }
        }
        if isBluetoothConnected {
            sendControl(left: 0, right: 0)
        }
    }

    /// Same path as the other driving screens: Bluetooth, so the STOP latch and avoidance apply.
    private func sendControl(left: Float, right: Float) {
        let l = Int((left * 255).rounded())
        let r = Int((right * 255).rounded())
        bluetooth.sendData(payload: "c\(l),\(r)\n")
    }

    /// FriendBot firmware LED modes for "i<left>,<right>" (see openbot-esp32-mtv.ino).
    private static let ledOff = 0
    private static let ledOn = 1
    private static let ledBlink = 2
    /// The firmware shows the driving state itself (turning side blinks, both blink when stopped).
    private static let ledAuto = 3

    /// Left/right LEDs, firmware "i<left>,<right>", each one of ledOff / ledOn / ledBlink /
    /// ledAuto. Not a motion command, so the drive gate and auto-stop do not apply.
    private func sendIndicator(left: Int, right: Int) {
        bluetooth.sendData(payload: "i\(left),\(right)\n")
    }

    private func clampUnit(_ v: Float) -> Float {
        max(-1, min(1, v))
    }

    private func describe(_ command: VoiceCommand) -> String {
        switch command.action {
        case .forward, .backward, .left, .right:
            let name: String
            switch command.action {
            case .forward: name = L10n.string("voice_forward")
            case .backward: name = L10n.string("voice_backward")
            case .left: name = L10n.string("voice_left")
            default: name = L10n.string("voice_right")
            }
            return String(format: L10n.string("voice_action_move"), name, command.durationMs)
        case .stop: return L10n.string("voice_stop")
        case .faster: return L10n.string("voice_faster")
        case .slower: return L10n.string("voice_slower")
        case .lightsOn: return L10n.string("voice_lights_on")
        case .lightsOff: return L10n.string("voice_lights_off")
        case .sonar: return L10n.string("voice_sonar")
        case .sing: return L10n.string("voice_sing")
        case .triangle: return L10n.string("voice_triangle")
        case .circle: return L10n.string("voice_circle")
        case .spin360: return L10n.string("voice_spin360")
        case .turnAround: return L10n.string("voice_turn_around")
        case .wiggle: return L10n.string("voice_wiggle")
        case .indicatorLeft: return L10n.string("voice_indicator_left")
        case .indicatorRight: return L10n.string("voice_indicator_right")
        case .indicatorHazard: return L10n.string("voice_indicator_hazard")
        case .indicatorOff: return L10n.string("voice_indicator_off")
        case .chat: return ""
        case .unknown: return L10n.string("voice_no_match")
        }
    }

    // MARK: MCP tools/call

    /// Camera manager for the corner preview + capture/vision pipeline (KS5028 S3CAM style).
    private let photoExplainer = PhotoExplainer()

    /// `self.camera.take_photo`: the "»" row marks the look-around; the answer is spoken by the
    /// assistant's own tts like any other reply (no local TTS on this screen).
    private func takePhoto(question: String, done: @escaping (String?, String?) -> Void) {
        utteranceTraffic = true
        utteranceToolCall = true
        appendConversation(.action, "» \(L10n.string("tool_take_photo"))")
        runPhotoTool(question: question) { photoJson, error in
            done(photoJson, photoJson == nil ? error : "")
        }
    }

    // MARK: Corner camera

    private func maybeStartCamera() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            startCameraPreview()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    if granted { self?.startCameraPreview() }
                }
            }
        default:
            break // denied: the corner stays hidden; the tool re-asks on demand
        }
    }

    private func startCameraPreview() {
        cameraFront = UserDefaults.standard.bool(forKey: Self.cameraFrontKey)
        photoExplainer.start(in: cameraPreviewHost, useFront: cameraFront)
        cameraCorner.isHidden = false
    }

    @objc private func flipCamera() {
        cameraFront.toggle()
        UserDefaults.standard.set(cameraFront, forKey: Self.cameraFrontKey)
        photoExplainer.switchCamera(in: cameraPreviewHost)
    }

    /// Tap on the corner: take a photo and show the Xiaozhi vision model's answer at once.
    @objc private func manualLookAround() {
        appendConversation(.action, "» \(L10n.string("tool_take_photo"))")
        runPhotoTool(question: "Describe what the camera sees in detail.") { [weak self] photoJson, error in
            guard let self = self else { return }
            if let photoJson = photoJson {
                self.appendBotTurn(Self.extractPhotoAnswer(photoJson))
            } else {
                self.appendBotTurn(error)
            }
        }
    }

    /// The whole photo job: capture → corner still flash → explain → answer.
    private func runPhotoTool(question: String, finished: @escaping (String?, String) -> Void) {
        photoExplainer.captureAndExplain(
            question: question,
            explainUrl: session.visionExplainUrl,
            explainToken: session.visionExplainToken,
            onCaptured: { [weak self] data in self?.showCameraStill(data) },
            explained: { photoJson, error in finished(photoJson, error) })
    }

    /// Freeze-frame the shutter in the corner for a moment so the capture is visible.
    private func showCameraStill(_ data: Data?) {
        guard let data = data, let image = UIImage(data: data) else { return }
        cameraStill.image = image
        cameraStill.isHidden = false
        cameraStillWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.cameraStill.isHidden = true }
        cameraStillWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.6, execute: work)
    }

    private static func extractPhotoAnswer(_ photoJson: String) -> String {
        guard let data = photoJson.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let answer = object["answer"] as? String else {
            return photoJson
        }
        return answer
    }

    /// The assistant's tools/call lands here: build a VoiceCommand and run the exact same
    /// execute path as the quick buttons (bluetoothDataController.sendData), so the STOP
    /// latch, drive gate and timed auto-stop all apply.
    private func handleToolCall(_ name: String, _ args: [String: Any]) -> Bool {
        guard ["self.robot.move", "self.robot.stop", "self.robot.led",
               "self.robot.indicator", "self.robot.sonar", "self.robot.trick"].contains(name) else {
            return false
        }
        utteranceTraffic = true
        utteranceToolCall = true
        let command: VoiceCommand
        switch name {
        case "self.robot.move":
            let action: VoiceAction
            switch args["direction"] as? String ?? "" {
            case "forward": action = .forward
            case "backward": action = .backward
            case "left": action = .left
            case "right": action = .right
            default: action = .stop
            }
            let speed = (args["speed"] as? NSNumber)?.floatValue ?? VoiceCommand.defaultSpeed
            let duration = (args["duration_ms"] as? NSNumber)?.intValue ?? VoiceCommand.defaultDurationMs
            command = VoiceCommand(action: action, speed: speed, durationMs: duration,
                                   rawText: name, source: "xiaozhi")
        case "self.robot.stop":
            command = VoiceCommand(action: .stop, rawText: name, source: "xiaozhi")
        case "self.robot.led":
            // Front/back lights: {"state": "on" | "off"} (Android parity).
            let action: VoiceAction
            switch (args["state"] as? String ?? "").lowercased() {
            case "on": action = .lightsOn
            case "off": action = .lightsOff
            default: return false  // unknown state: reject rather than guess
            }
            command = VoiceCommand(action: action, rawText: name, source: "xiaozhi")
        case "self.robot.indicator":
            // Turn signals: {"state": "left" | "right" | "both" | "off"}.
            let action: VoiceAction
            switch args["state"] as? String ?? "" {
            case "left": action = .indicatorLeft
            case "right": action = .indicatorRight
            case "both": action = .indicatorHazard
            case "off": action = .indicatorOff
            default: return false  // unknown state: reject rather than guess
            }
            command = VoiceCommand(action: action, rawText: name, source: "xiaozhi")
        case "self.robot.sonar":
            command = VoiceCommand(action: .sonar, rawText: name, source: "xiaozhi")
        case "self.robot.trick":
            let action: VoiceAction
            switch args["name"] as? String ?? "" {
            case "triangle": action = .triangle
            case "circle": action = .circle
            case "spin": action = .spin360
            case "turn_around": action = .turnAround
            case "wiggle": action = .wiggle
            case "sing": action = .sing
            default: return false  // unknown trick: reject, never spin the robot by default
            }
            command = VoiceCommand(action: action, rawText: name, source: "xiaozhi")
        default:
            command = VoiceCommand(action: .stop, rawText: name, source: "xiaozhi")
        }
        // Silent execution: the assistant's own tts reply covers the answer. The action
        // still goes in the conversation list ("»" row); the phone never speaks it.
        execute(command: command, say: "", spoken: false)
        return true
    }

    // MARK: Quick buttons and typed input

    @objc private func tapForward() { execute(command: VoiceCommand(action: .forward, rawText: "tap", source: "tap"), say: "") }
    @objc private func tapBackward() { execute(command: VoiceCommand(action: .backward, rawText: "tap", source: "tap"), say: "") }
    @objc private func tapLeft() { execute(command: VoiceCommand(action: .left, rawText: "tap", source: "tap"), say: "") }
    @objc private func tapRight() { execute(command: VoiceCommand(action: .right, rawText: "tap", source: "tap"), say: "") }
    @objc private func tapStop() { execute(command: VoiceCommand(action: .stop, rawText: "tap", source: "tap"), say: "") }
    @objc private func tapLights() {
        execute(command: VoiceCommand(action: lightsOn ? .lightsOff : .lightsOn,
                                      rawText: "tap", source: "tap"), say: "")
    }
    @objc private func tapSonar() { execute(command: VoiceCommand(action: .sonar, rawText: "tap", source: "tap"), say: "") }
    @objc private func tapSing() { execute(command: VoiceCommand(action: .sing, rawText: "tap", source: "tap"), say: "") }
    @objc private func tapTriangle() { execute(command: VoiceCommand(action: .triangle, rawText: "tap", source: "tap"), say: "") }
    @objc private func tapCircle() { execute(command: VoiceCommand(action: .circle, rawText: "tap", source: "tap"), say: "") }
    @objc private func tapSpin() { execute(command: VoiceCommand(action: .spin360, rawText: "tap", source: "tap"), say: "") }
    @objc private func tapWiggle() { execute(command: VoiceCommand(action: .wiggle, rawText: "tap", source: "tap"), say: "") }
    @objc private func tapTurn() { execute(command: VoiceCommand(action: .turnAround, rawText: "tap", source: "tap"), say: "") }

    @objc private func speedChanged() {
        speedScale = speedSlider.value
        showSpeed()
    }

    private func showSpeed() {
        speedValueLabel.text = String(format: L10n.string("voice_speed_value"), Int((speedScale * 100).rounded()))
    }

    @objc private func sendTapped() {
        let text = (typedField.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        typedField.text = ""
        typedField.resignFirstResponder()
        onTranscript(text)
    }

    // MARK: Xiaozhi settings

    @objc private func settingsTapped() {
        let config = XiaozhiClient.loadConfig()
        let alertBox = UIAlertController(title: L10n.string("voice_xiaozhi_settings"),
                                         message: L10n.string("voice_xiaozhi_settings_help"),
                                         preferredStyle: .alert)
        alertBox.addTextField { field in
            field.text = config.endpoint
            field.placeholder = L10n.string("voice_xiaozhi_endpoint_hint")
        }
        alertBox.addTextField { field in
            field.text = config.token
            field.placeholder = L10n.string("voice_xiaozhi_token_hint")
            field.isSecureTextEntry = true
        }
        alertBox.addTextField { field in
            field.text = config.model
            field.placeholder = L10n.string("voice_xiaozhi_model_hint")
        }
        // Device login (whisplay-xiaozhi style): the app registers as a Xiaozhi device, shows a
        // verification code, and receives the credentials back once the code is entered at
        // xiaozhi.me. Also the way back into pairing after "Use offline mode".
        alertBox.addAction(UIAlertAction(title: L10n.string("voice_xiaozhi_sign_in"), style: .default) { [weak self] _ in
            self?.startXiaozhiLogin()
        })
        alertBox.addAction(UIAlertAction(title: L10n.string("voice_cancel"), style: .cancel))
        alertBox.addAction(UIAlertAction(title: L10n.string("voice_save"), style: .default) { [weak self] _ in
            let fields = alertBox.textFields ?? []
            let saved = XiaozhiConfig(endpoint: fields.count > 0 ? fields[0].text ?? "" : config.endpoint,
                                      token: fields.count > 1 ? fields[1].text ?? "" : config.token,
                                      model: fields.count > 2 ? fields[2].text ?? "" : config.model)
            XiaozhiClient.save(saved)
            self?.appendLog(L10n.string("voice_xiaozhi_saved"))
        })
        // Voice language: single-choice list of endonyms + System default.
        alertBox.addAction(UIAlertAction(title: L10n.string("voice_language_title"), style: .default) { [weak self] _ in
            self?.showLanguagePicker()
        })
        present(alertBox, animated: true)
    }

    /// Single-choice voice language list (gear -> "Voice language"): endonyms plus
    /// "System default". The choice is saved in the voice_lang preference and feeds
    /// the REST brain's languageTag.
    private func showLanguagePicker() {
        let current = UserDefaults.standard.string(forKey: Self.voiceLangKey) ?? Self.defaultVoiceLang
        let picker = UIAlertController(title: L10n.string("voice_language_title"),
                                       message: nil,
                                       preferredStyle: .alert)
        var options = Self.voiceLanguages
        options.append((tag: Self.systemVoiceLang, name: L10n.string("voice_language_system")))
        for option in options {
            // A checkmark marks the current choice; the names stay literal endonyms.
            let title = option.tag == current ? "✓ \(option.name)" : option.name
            picker.addAction(UIAlertAction(title: title, style: .default) { [weak self] _ in
                UserDefaults.standard.set(option.tag, forKey: Self.voiceLangKey)
                self?.appendLog(L10n.string("voice_language_title") + ": " + option.name)
            })
        }
        picker.addAction(UIAlertAction(title: L10n.string("voice_cancel"), style: .cancel))
        present(picker, animated: true)
    }

    /// Runs the xiaozhi.me device-binding flow: show the verification code, poll until the
    /// user has entered it on the website, then save the issued credentials.
    private func startXiaozhiLogin() {
        let loginAlert = UIAlertController(title: L10n.string("voice_xiaozhi_login_title"),
                                           message: L10n.string("voice_xiaozhi_login_waiting"),
                                           preferredStyle: .alert)
        loginAlert.addAction(UIAlertAction(title: L10n.string("voice_cancel"), style: .cancel) { [weak self] _ in
            self?.activator?.cancel()
            self?.activator = nil
            self?.appendLog(L10n.string("voice_xiaozhi_login_cancelled"))
        })
        present(loginAlert, animated: true)

        let activator = XiaozhiActivator()
        self.activator = activator
        activator.start((onCode: { code in
            loginAlert.message = String(format: L10n.string("voice_xiaozhi_login_code"), code)
        }, onBound: { [weak self] _, wsToken in
            loginAlert.dismiss(animated: true)
            var config = XiaozhiClient.loadConfig()
            config.token = wsToken
            XiaozhiClient.save(config)
            self?.appendLog(L10n.string("voice_xiaozhi_login_success"))
            self?.activator = nil
            // The reply also carried the websocket credentials (saved by the activator):
            // connect the live session and leave pairing.
            self?.pairingDone()
        }, onError: { [weak self] message in
            loginAlert.message = String(format: L10n.string("voice_xiaozhi_login_error"), message)
            self?.activator = nil
        }))
    }

    // MARK: UI helpers

    @objc private func updateConnection() {
        if isBluetoothConnected {
            // This phone owns the robot link: share it over WiFi for the other phones.
            // Automatic mode defers to it; an explicit host pick survives.
            relayServer.start()
            relayClient.onLocalLinkUp()
        }
        // Keep browsing even while sharing: the capsule's picker lists the robots other
        // phones share so the user can choose to drive through one of them.
        relayClient.start()
        let base: String
        if relayClient.isHostConnected {
            // The picked target is a robot another phone shares over WiFi.
            base = String(format: L10n.string("voice_relay_connected"), relayClient.hostName)
        } else if isBluetoothConnected {
            base = relayServer.isRunning
                ? String(format: L10n.string("voice_sharing_wifi"), "BLE")
                : String(format: L10n.string("voice_connected"), "BLE")
        } else if relayClient.isSearching {
            base = L10n.string("voice_relay_searching")
        } else {
            base = L10n.string("voice_not_connected")
        }
        // While this page serves, always show the PIN other phones must type — the relay
        // serves even before the robot link is up, and remotes need the PIN to join.
        let sharing = relayServer.isRunning && !relayClient.isHostConnected
            ? " · " + String(format: L10n.string("voice_sharing_pin"), relayServer.pin)
            : ""
        let ws = session.isReady ? L10n.string("voice_ws_ready") : L10n.string("voice_ws_offline")
        connectionLabel.text = base + sharing + " · " + ws
    }

    /// Capsule tap: choose which robot the voice commands drive — this phone's own
    /// Bluetooth link, or a robot another phone shares over WiFi (by phone name). The pick
    /// is remembered for the next launch.
    @objc private func robotPickerTapped() {
        let hosts = relayClient.hostNames()
        guard isBluetoothConnected || !hosts.isEmpty else {
            alert(L10n.string("voice_robot_none_found"))
            return
        }
        let sheet = UIAlertController(title: L10n.string("voice_robot_picker_title"),
                                     message: nil, preferredStyle: .actionSheet)
        if isBluetoothConnected {
            sheet.addAction(UIAlertAction(
                title: String(format: L10n.string("voice_robot_this_phone"), "BLE"),
                style: .default) { [weak self] _ in
                    self?.relayClient.chooseLocal()
                    self?.updateConnection()
                })
        }
        for host in hosts {
            sheet.addAction(UIAlertAction(
                title: String(format: L10n.string("voice_robot_wifi_item"), host),
                style: .default) { [weak self] _ in
                    self?.relayClient.chooseHost(host)
                    self?.updateConnection()
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

    private func appendLog(_ line: String) {
        logLines.insert(line, at: 0)
        if logLines.count > Self.maxLogLines {
            logLines = Array(logLines.prefix(Self.maxLogLines))
        }
        logLabel.text = logLines.joined(separator: "\n")
    }
}

extension VoiceControlController: UITextFieldDelegate {
    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        sendTapped()
        return true
    }
}

// MARK: Remote microphone (Remote Mic page on another phone)

extension VoiceControlController: RelayMicSink {

    func onRemoteMic(active: Bool, phoneName: String) {
        if active {
            onRemoteMicStarted()
        } else {
            onRemoteMicStopped()
        }
    }

    func onRemoteMicFrame(opus: Data, rms: Float) {
        guard remoteMicActive, session.isReady else { return }
        guard uiState != .speaking else { return } // echo guard: the host is the one talking
        session.sendBinary(opus)
        onMicLevel(rms)
    }

    private func onRemoteMicStarted() {
        remoteMicActive = true
        guard session.isReady else { return }
        if uiState == .speaking {
            // Barge-in: cut the assistant off at once, then take the floor.
            flushPlayback()
            session.sendAbort()
        }
        thinkingTimer?.invalidate()
        thinkingTimer = nil
        stopMicCapture() // the host's own mic stands down
        session.sendListenStart(mode: XiaozhiSession.modeAuto) // hands-free, whatever the mode
        setUiState(.listening)
    }

    private func onRemoteMicStopped() {
        guard remoteMicActive else { return }
        remoteMicActive = false
        if session.isReady {
            session.sendListenStop()
        }
        setUiState(.thinking)
        armThinkingTimeout()
    }
}
