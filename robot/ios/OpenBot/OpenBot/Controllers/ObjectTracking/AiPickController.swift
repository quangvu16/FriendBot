//
//  AiPickController.swift
//  OpenBot
//
//  AI Pick (AI tab): the Object Tracking page's template — same camera page, same
//  ObjectTrackingSettings panel (detector model from the Model Management catalog,
//  object, confidence, device, threads) — with the Xiaozhi voice conversation layered
//  on. Talk to the assistant hands-free (the mic uplink streams opus frames like the
//  Voice Control page); its robot tools drive and grip through the shared
//  tool→VoiceCommand map ("self.robot.*"). The robot NEVER follows the object on its
//  own: the detector only draws and asks the Voice AI "what should I do with it?",
//  and every wheel move comes from a voice command (or the gripper buttons). ✊ (and
//  the AI's gripper tool) picks with the gripper gated by a sonar distance: creep
//  forward until the front sonar reads ≤ the "Grip at (cm)" setting, then close the
//  claw — blind fallback after a short creep when the robot never reports a reading,
//  "too far" when the object never comes within the setting. Mirrors AiPickFragment
//  on Android.
//

import UIKit
import AVFoundation

final class AiPickController: CameraController, AVSpeechSynthesizerDelegate {

    // MARK: - The Object Tracking template (ObjectTrackingFragment)

    var objectTrackingSettings: ObjectTrackingSettings?
    var numberOfThreads: Int = 1
    var bottomAnchorConstraint: NSLayoutConstraint!
    var trailingAnchorConstraint: NSLayoutConstraint!
    var detector: Detector?
    var models: [Model] = []
    let gameController = GameController.shared
    let bluetooth = bluetoothDataController.shared
    var currentModel: ModelItem!
    var currentDevice: RuntimeDevice = RuntimeDevice.CPU
    /// The "Object" dropdown's pick: the class the detector draws (it never drives).
    var currentObject: String = "cup"
    public var MINIMUM_CONFIDENCE_TF_OD_API: Float = 0.5
    private let inferenceQueue = DispatchQueue(label: "openbot.aipick.inferencequeue")
    private var isInferenceQueueBusy = false
    private var frames: [UIView] = []
    private var bufferHeight = 0
    private var bufferWidth = 0
    private let edgeOffset: CGFloat = 2.0
    let webSocketMsgHandler = WebSocketMessageHandler();
    let fragmentType = FragmentType.shared
    private lazy var sensorLinkItem = SensorLinkBarItem(owner: self, screen: "AI Pick", camera: self) { [unowned self] out in
        out.append(.range("tracking.confidence", "AI Pick", "Detection confidence (%)", 5, 95, 5,
                          get: { Double((self.MINIMUM_CONFIDENCE_TF_OD_API * 100).rounded()) },
                          set: { NotificationCenter.default.post(name: .updateConfidence, object: Int($0.rounded())) }))
    }

    // MARK: - Voice, gripper, sonar

    private let defaults = UserDefaults.standard
    private var reversed = false
    private var gripCm: Int = 10

    private let synth = AVSpeechSynthesizer()
    private let audio = AVAudioEngine()
    private var speaking = false // the phone's own tts (never listen to it)

    // Plain drive words: timed moves with auto-stop and a resend loop (BLE drops packets).
    private var moveEndsAt: Double = 0
    private var moveLeft: Float = 0
    private var moveRight: Float = 0
    private var moveTimer: Timer?
    /// Queued legs of a choreographed trick (the AI's self.robot.trick), one move timer leg at a time.
    private var moveScript: [DriveSegment] = []

    // MARK: - Voice AI (Xiaozhi): hands-free conversation whose tools drive and grip

    private let aiSession = XiaozhiSession()
    private var aiReady = false

    /// Offline mic fallback: one on-device recognition while Xiaozhi is unreachable.
    private var localVoiceActive = false

    /// Paired-path safety net: what the server heard, and whether a robot tool answered it.
    private var aiUtteranceText = ""
    private var aiToolRan = false
    private var aiAssistantWait: Timer?
    private static let aiAssistantWaitSeconds = 3.5

    // The AI's tts (server opus -> PCM -> the engine's player node).
    private let playerNode = AVAudioPlayerNode()
    private var playerAttached = false
    private var playbackFormat: AVAudioFormat?
    private var opusDecoder: OpusCodecDecoder?
    private var queuedPlayCount = 0
    private var playbackBusy = false
    private var drainWork: DispatchWorkItem?

    // The microphone uplink (AVAudioEngine tap -> 16 kHz mono Int16 -> 60 ms opus frames).
    private var micOn = false
    private var micStreaming = false
    private var micConverter: AVAudioConverter?
    private var opusEncoder: OpusCodecEncoder?
    private var pcmAccumulator: [Int16] = []
    private var tapInstalled = false

    // The sonar-gated pick: creep in, grip at the set distance.
    private var pickActive = false
    private var pickSeenSonar = false
    private var pickStartedAt: Double = 0
    private var pickTimer: Timer?
    private var lastApproach: Double = 0

    // Photo jobs (📸 button and the AI's photo tool): grab one frame, explain it.
    private let photoExplainer = PhotoExplainer()
    private var photoJobActive = false
    private var pendingPhotoQuestion = ""
    private var pendingPhotoDone: ((String?, String?) -> Void)?
    private var grabFrame = false

    // Sighting prompts ("I see a cup — what should I do with it?") are rate-limited.
    private var lastSightingPromptAt: Double = 0
    private var lastSightingClass = ""
    private var lastSightingClassAt: Double = 0
    private static let sightingPromptInterval: Double = 20
    private static let sightingSameClassInterval: Double = 60
    /// The last non-person object the camera saw: "pick it up" / fetch("it") resolves to it.
    private var lastSeenClass = ""

    // Over the camera.
    private let face = RobotFaceView()
    private let bubble = UILabel()
    private let statusLabel = UILabel()
    private let buttonsRow = UIStackView()
    private let gripRow = UIStackView()
    private var micButton: UIButton?
    private var gripValueLabel: UILabel?
    private var lastEmotion: RobotFaceView.Emotion = .neutral

    // Pick tuning (mirrors AiPickFragment on Android).
    private static let defaultGripCm = 5
    /// Under this the object is already touching the sensor: stop, don't crush it.
    private static let tooCloseCm: Float = 2
    private static let gripCmMin = 4
    private static let gripCmMax = 40
    private static let pickSpeed: Float = 0.2 // slow creep toward the object
    private static let pickTickSeconds: Double = 0.15
    private static let pickTimeoutSeconds: Double = 8
    private static let pickBlindSeconds: Double = 2.5
    /// Approach mode during the creep: firmware stops forward at 15 cm, 4 cm in approach.
    private static let approachMs = 3000
    private static let approachResend: Double = 2

    // MARK: - UI

    /// Called after the view fragment has loaded.
    override func viewDidLoad() {
        // The model list is the Model Management catalog (documents config.json
        // when present, otherwise the bundled one). Only models whose file exists
        // are considered, so the detector can actually be created.
        var modelItems = Common.loadAllModelItems().filter { Common.isModelItemRunnable($0) }
        if modelItems.isEmpty {
            modelItems = Common.loadAllModelItemsFromBundle()
        }
        if (modelItems.count > 0) {
            currentModel = modelItems.first(where: { $0.type == TYPE.DETECTOR.rawValue }) ?? modelItems[0]
            detector = try! Detector.create(model: Model.fromModelItem(item: currentModel), device: RuntimeDevice.CPU, numThreads: numberOfThreads) as? Detector
        }
        if let threads = preferencesManager.getThreads(){
            numberOfThreads = Int(threads) ?? 4
            detector?.tfliteOptions.threadCount = numberOfThreads
        }
        if let confidence = preferencesManager.getObjectTrackConfidence(){
            MINIMUM_CONFIDENCE_TF_OD_API = Float(confidence as! Int) / 100.0
        }
        if let device = preferencesManager.getDevice(){
            currentDevice = RuntimeDevice(rawValue: device) ?? RuntimeDevice.CPU
            detector = try! Detector.create(model: Model.fromModelItem(item: currentModel), device: currentDevice, numThreads: numberOfThreads) as? Detector
            detector?.tfliteOptions.threadCount = numberOfThreads
        }
        // This page's own model choice (its persisted pick), then the shared one.
        if let lastModel = preferencesManager.getAiPickModel() ?? preferencesManager.getObjectTrackModel(), !lastModel.isEmpty,
           let restored = Common.loadAllModelItems().first(where: { Common.baseName($0.name) == Common.baseName(lastModel) }),
           Common.isModelItemRunnable(restored) {
            // The last choice may be a bundled model just as well as a downloaded one.
            currentModel = restored
            detector = try! Detector.create(model: Model.fromModelItem(item: restored), device: currentDevice, numThreads: numberOfThreads) as? Detector
        }
        if let object = preferencesManager.getObjectTrackingObject(){
            currentObject = object;
            detector?.setSelectedClass(newClass: object);
        }
        // No auto switch / dynamic speed: this page never follows the object — the
        // robot only moves on voice commands.
        objectTrackingSettings = ObjectTrackingSettings(frame: CGRect(x: 0, y: height - 375, width: width, height: 375), detector: detector, model: currentModel, showsAutoControls: false)
        objectTrackingSettings!.backgroundColor = Colors.freeRoamButtonsColor
        objectTrackingSettings!.layer.cornerRadius = 5
        createCameraView()
        view.addSubview(objectTrackingSettings!)
        bottomAnchorConstraint = objectTrackingSettings!.bottomAnchor.constraint(equalTo: view.bottomAnchor, constant: -5)
        trailingAnchorConstraint = objectTrackingSettings!.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: 0)
        NotificationCenter.default.addObserver(self, selector: #selector(openBluetoothSettings), name: .ble, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(switchCamera), name: .switchCamera, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(updateDevice), name: .updateDevice, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(updateThread), name: .updateThread, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(updateConfidence), name: .updateConfidence, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(updateModel), name: .updateModel, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(updateSelectedObject), name: .updateObject, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(updateDataFromControllerApp), name: .updateStringFromControllerApp, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(updateLightsCommandFromControllerApp), name: .updateLightsCommandFromControllerApp, object: nil)
        setupNavigationBarItem()
        gameController.resetControl = false
        fragmentType.currentFragment = "ObjectDetection";
        calculateFrame()
        let msg = JSON.toString(FragmentTypeEvent(status: .init(FRAGMENT_TYPE: self.fragmentType.currentFragment)));
        client.send(message: msg);

        // ---- the voice/gripper layer (the template does not have these)
        setupVoiceOverlay()
        synth.delegate = self
        wireAiSession()
        super.viewDidLoad()
    }

    /// Face (top right), what was said (a bubble), status line, the voice buttons and the
    /// grip-distance stepper, floating over the camera above the settings panel.
    private func setupVoiceOverlay() {
        reversed = defaults.bool(forKey: "aipick_flip")
        let savedGrip = defaults.integer(forKey: "aipick_grip_cm")
        gripCm = savedGrip > 0 ? max(Self.gripCmMin, min(Self.gripCmMax, savedGrip)) : Self.defaultGripCm

        face.translatesAutoresizingMaskIntoConstraints = false
        face.backgroundColor = UIColor(red: 0.02, green: 0.027, blue: 0.043, alpha: 0.85)
        face.layer.cornerRadius = 18
        face.clipsToBounds = true
        view.addSubview(face)

        bubble.translatesAutoresizingMaskIntoConstraints = false
        bubble.textColor = .white
        bubble.font = .systemFont(ofSize: 15, weight: .medium)
        bubble.numberOfLines = 3
        bubble.backgroundColor = UIColor.black.withAlphaComponent(0.65)
        bubble.layer.cornerRadius = 10
        bubble.clipsToBounds = true
        bubble.isHidden = true
        view.addSubview(bubble)

        NSLayoutConstraint.activate([
            face.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            face.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -8),
            face.widthAnchor.constraint(equalToConstant: 180),
            face.heightAnchor.constraint(equalToConstant: 140),
            bubble.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 48),
            bubble.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),
            bubble.trailingAnchor.constraint(lessThanOrEqualTo: face.leadingAnchor, constant: -8),
        ])
        face.setFace(.neutral, talking: false)

        statusLabel.font = .systemFont(ofSize: 13, weight: .medium)
        statusLabel.textColor = .white
        statusLabel.backgroundColor = UIColor.black.withAlphaComponent(0.55)
        statusLabel.layer.cornerRadius = 8
        statusLabel.clipsToBounds = true
        statusLabel.textAlignment = .center
        statusLabel.text = "  " + L10n.string("aipick_ready") + "  "
        view.addSubview(statusLabel)

        buttonsRow.axis = .horizontal
        buttonsRow.spacing = 6
        buttonsRow.distribution = .fillEqually
        view.addSubview(buttonsRow)
        micButton = addVoiceButton("🎤 OFF") { [weak self] in self?.toggleListening() }
        addVoiceButton("📸") { [weak self] in self?.takePictureAndDescribe() }
        addVoiceButton("✊") { [weak self] in self?.sonarPick() }
        addVoiceButton("✋") { [weak self] in self?.openGripper() }
        addVoiceButton("⇄") { [weak self] in self?.toggleReverse() }

        // The sonar distance the gripper grabs at: "Grip at (cm)" −/value/+.
        gripRow.axis = .horizontal
        gripRow.spacing = 6
        gripRow.distribution = .fill
        view.addSubview(gripRow)

        let gripLabel = UILabel()
        gripLabel.text = "  " + L10n.string("grab_distance") + "  "
        gripLabel.font = .systemFont(ofSize: 13, weight: .medium)
        gripLabel.textColor = .white
        gripLabel.backgroundColor = UIColor.black.withAlphaComponent(0.55)
        gripLabel.layer.cornerRadius = 8
        gripLabel.clipsToBounds = true
        gripLabel.textAlignment = .center
        gripRow.addArrangedSubview(gripLabel)

        let minus = makeVoiceButton("−") { [weak self] in self?.bumpGrip(-2) }
        minus.widthAnchor.constraint(equalToConstant: 44).isActive = true
        gripRow.addArrangedSubview(minus)

        let value = UILabel()
        value.text = "\(gripCm)"
        value.font = .systemFont(ofSize: 16, weight: .semibold)
        value.textColor = UIColor(red: 0.13, green: 0.55, blue: 1.0, alpha: 1)
        value.backgroundColor = UIColor.white.withAlphaComponent(0.92)
        value.layer.cornerRadius = 8
        value.clipsToBounds = true
        value.textAlignment = .center
        value.widthAnchor.constraint(equalToConstant: 52).isActive = true
        gripValueLabel = value
        gripRow.addArrangedSubview(value)

        let plus = makeVoiceButton("+") { [weak self] in self?.bumpGrip(2) }
        plus.widthAnchor.constraint(equalToConstant: 44).isActive = true
        gripRow.addArrangedSubview(plus)
    }

    private func makeVoiceButton(_ title: String, _ action: @escaping () -> Void) -> UIButton {
        let b = UIButton(type: .system)
        b.setTitle(title, for: .normal)
        b.setTitleColor(UIColor(red: 0.13, green: 0.55, blue: 1.0, alpha: 1), for: .normal)
        b.titleLabel?.font = .systemFont(ofSize: 16, weight: .semibold)
        b.backgroundColor = UIColor.white.withAlphaComponent(0.92)
        b.layer.cornerRadius = 8
        b.addAction(UIAction { _ in action() }, for: .touchUpInside)
        return b
    }

    private func addVoiceButton(_ title: String, _ action: @escaping () -> Void) -> UIButton {
        let b = makeVoiceButton(title, action)
        buttonsRow.addArrangedSubview(b)
        return b
    }

    /// Called when the view controller's view's size is changed by its parent (i.e. for the root view controller when its window rotates or is resized).
    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)
        calculateFrame()
    }

    func calculateFrame() {
        if currentOrientation == .portrait || currentOrientation == .portraitUpsideDown {
            objectTrackingSettings?.frame.origin.x = 0
            objectTrackingSettings?.frame.origin.y = height - 375
            // Voice controls float over the camera, just above the settings panel.
            gripRow.frame = CGRect(x: 8, y: height - 375 - 114, width: width - 16, height: 28)
            buttonsRow.frame = CGRect(x: 8, y: height - 375 - 52, width: width - 16, height: 44)
            statusLabel.frame = CGRect(x: 12, y: height - 375 - 82, width: width - 24, height: 24)
        } else {
            objectTrackingSettings?.frame.origin.x = height - 375
            objectTrackingSettings?.frame.origin.y = 30
            gripRow.frame = CGRect(x: 8, y: 92, width: height - 375 - 16, height: 28)
            buttonsRow.frame = CGRect(x: 8, y: 40, width: height - 375 - 16, height: 44)
            statusLabel.frame = CGRect(x: 12, y: 8, width: height - 375 - 16, height: 24)
        }
    }

    @objc func openBluetoothSettings() {
        let nextViewController = (storyboard?.instantiateViewController(withIdentifier: Strings.bluetoothScreen))
        navigationController?.pushViewController(nextViewController!, animated: true)
    }

    /// Initialization routine
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        sensorLinkItem.appeared()
        aiSession.connect() // the Voice AI: hands-free talk and the robot tools
    }

    /// Called after the view was dismissed, covered or otherwise hidden.
    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        sensorLinkItem.disappeared()
        micOn = false
        localVoiceActive = false
        LocalStt.stop()
        aiAssistantWait?.invalidate()
        aiAssistantWait = nil
        aiUtteranceText = ""
        stopMicSession(notify: true)
        aiSession.disconnect()
        teardownAiAudio()
        synth.stopSpeaking(at: .immediate)
        stopManualMove()
        if isBluetoothConnected {
            bluetooth.sendData(payload: "a0\n")
            bluetooth.sendData(payload: "s0\n")
        }

        let msg = JSON.toString(self.fragmentType.closeFragment())
        client.send(message: msg)

    }

    @objc func switchCamera() {
        switchCameraView()
    }

    /// function to update the detector model when change in device[CPU, GPU, NNAPI] is triggered.
    @objc func updateDevice(_ notification: Notification) throws {
        currentDevice = RuntimeDevice(rawValue: notification.object as! String) ?? RuntimeDevice.CPU
        detector = try! Detector.create(model: Model.fromModelItem(item: currentModel), device: currentDevice, numThreads: numberOfThreads) as? Detector
        currentDevice.rawValue == RuntimeDevice.GPU.rawValue ? NotificationCenter.default.post(name: .updateThreadLabel, object: "N/A") : NotificationCenter.default.post(name: .updateThreadLabel, object: String(numberOfThreads))
        detector?.tfliteOptions.threadCount = numberOfThreads
        preferencesManager.setDevice(value: notification.object as! String);
    }

    /// function to update the number of threads.
    @objc func updateThread(_ notification: Notification) {
        let threadCount = notification.object as! String
        numberOfThreads = Int(threadCount) ?? 4
        detector?.tfliteOptions.threadCount = numberOfThreads
        preferencesManager.setThreads(value: notification.object as! String);
    }

    /// function to update the confidence of the model
    @objc func updateConfidence(_ notification: Notification) {
        let confidence = notification.object as! Int
        MINIMUM_CONFIDENCE_TF_OD_API = Float(confidence) / 100.0
        preferencesManager.setObjectTrackConfidence(value: confidence);
    }

    /// Voice STOP and "one driver at a time": halt whatever drives the wheels (moves,
    /// trick scripts, the pick's creep) and quiet the sensors. This page never follows
    /// the object on its own — only voice commands (and the gripper buttons) move it.
    func stopAllMotion() {
        stopManualMove()
        if isBluetoothConnected {
            bluetooth.sendData(payload: "a0\n")
            bluetooth.sendData(payload: "s0\n")
        }
    }

    /// function to update the model
    @objc func updateModel(_ notification: Notification) throws {
        let selectedModelName = notification.object as! String
        // Only switch to a model from the Model Management list whose file exists;
        // an unknown name must not fabricate an empty item and crash the detector.
        guard let item = Common.loadAllModelItems().first(where: { Common.baseName($0.name) == Common.baseName(selectedModelName) }),
              Common.isModelItemRunnable(item) else {
            return
        }
        currentModel = item
        do {
            detector = try Detector.create(model: Model.fromModelItem(item: item), device: currentDevice, numThreads: numberOfThreads) as? Detector
        } catch {
            // Keep the previous detector if the new model fails to load.
            return
        }
        preferencesManager.setAiPickModel(value: selectedModelName) // this page's own choice
        NotificationCenter.default.post(name: .updateObjectList, object: detector?.getLabels())
        NotificationCenter.default.post(name: .updateObject, object: currentObject)
    }

    /// function to update the selected object.
    @objc func updateSelectedObject(_ notification: Notification) throws {
        currentObject = notification.object as! String
        detector?.setSelectedClass(newClass: notification.object as! String)
    }

    func setupNavigationBarItem() {
        if UIImage(named: "back") != nil {
            let backNavigationIcon = (UIImage(named: "back")?.withRenderingMode(.alwaysOriginal))!
            let newBackButton = UIBarButtonItem(image: backNavigationIcon, title: L10n.string("tile_ai_pick"), target: self, action: #selector(AiPickController.back(sender:)), titleColor: Colors.navigationColor ?? .white)
            navigationItem.leftBarButtonItem = newBackButton
        }
        // Sensor link (SensorServer WebSocket): share this phone's sensors or use another phone's.
        navigationItem.rightBarButtonItem = sensorLinkItem
    }

    @objc func back(sender: UIBarButtonItem) {
        _ = navigationController?.popViewController(animated: true)
    }

    /// function to create the frame on the screen for detection on based of image device(front/back) or orientation (portrait/landscape).
    func addFrame(item: Detector.Recognition, color: UIColor) -> UIView {
        let frame = UIView()
        var convertedRect: CGRect = item.getLocation()
        if currentOrientation == .portrait && (captureSession.inputs[0] as! AVCaptureDeviceInput).device.position == .front {
            let detection = item.getLocation()
            let scaleX = width / CGFloat(bufferWidth)
            let scaleY = height / CGFloat(bufferHeight)
            let transform = CGAffineTransform.identity.scaledBy(x: scaleX, y: scaleY)
            let revertTransform = transform.concatenating(__CGAffineTransformMake(-1.0, 0.0, 0.0, 1.0, CGFloat(width), 0.0));
            convertedRect = detection.applying(revertTransform)
        } else if currentOrientation != .portrait && (captureSession.inputs[0] as! AVCaptureDeviceInput).device.position == .front {
            let detection = item.getLocation()
            let scaleX = height / CGFloat(bufferWidth)
            let scaleY = width / CGFloat(bufferHeight)
            let transform = CGAffineTransform.identity.scaledBy(x: scaleX, y: scaleY)
            let revertTransform = transform.concatenating(__CGAffineTransformMake(-1.0, 0.0, 0.0, 1.0, CGFloat(height), 0.0));
            convertedRect = detection.applying(revertTransform)
        } else if currentOrientation == .portrait && (captureSession.inputs[0] as! AVCaptureDeviceInput).device.position == .back {
            let detection = item.getLocation()
            let scaleX = width / CGFloat(bufferWidth)
            let scaleY = height / CGFloat(bufferHeight)
            let transform = CGAffineTransform.identity.scaledBy(x: scaleX, y: scaleY)
            convertedRect = detection.applying(transform)
        } else {
            let detection = item.getLocation()
            let scaleX = height / CGFloat(bufferWidth)
            let scaleY = width / CGFloat(bufferHeight)
            let transform = CGAffineTransform.identity.scaledBy(x: scaleX, y: scaleY)
            convertedRect = detection.applying(transform)
        }
        if convertedRect.origin.x < 0 {
            convertedRect.origin.x = edgeOffset
        }

        if convertedRect.origin.y < 0 {
            convertedRect.origin.y = edgeOffset
        }

        if convertedRect.maxY > UIScreen.main.bounds.maxY {
            convertedRect.size.height = UIScreen.main.bounds.maxY - convertedRect.origin.y - edgeOffset
        }

        if convertedRect.maxX > UIScreen.main.bounds.maxX {
            convertedRect.size.width = UIScreen.main.bounds.maxX - convertedRect.origin.x - edgeOffset
        }
        frame.frame = convertedRect
        frame.layer.borderColor = color.cgColor
        frame.layer.borderWidth = 3.0
        let nameString = UITextView()
        nameString.textColor = UIColor.white
        nameString.font = nameString.font?.withSize(12)
        nameString.backgroundColor = color.withAlphaComponent(0.5)
        nameString.text = item.getTitle() + " " + String(format: "%.2f", item.getConfidence() * 100) + "%"
        nameString.translatesAutoresizingMaskIntoConstraints = true
        nameString.sizeToFit()
        frame.addSubview(nameString)
        return frame
    }

    // MARK: - Camera

    /// callback function when any image is clicked on ai pick fragment.
    override func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        CameraHub.shared.offer(sampleBuffer) // browser camera view (Sensor link)

        // extract the image buffer from the sample buffer
        let pixelBuffer: CVPixelBuffer? = CMSampleBufferGetImageBuffer(sampleBuffer)

        bufferWidth = CVPixelBufferGetWidth(pixelBuffer!)
        bufferHeight = CVPixelBufferGetHeight(pixelBuffer!)

        guard let imagePixelBuffer = pixelBuffer else {
            debugPrint("unable to get image from sample buffer")
            return
        }
        if webRTCClient != nil {
            inferenceQueue.async {
                webRTCClient.captureCurrentFrame(sampleBuffer: sampleBuffer)
                self.isInferenceQueueBusy = false
            }
        }
        guard !isInferenceQueueBusy else {
            return
        }

        inferenceQueue.async {
            if self.grabFrame {
                // One frame is enough for the photo tool / the 📸 button.
                self.grabFrame = false
                let jpeg = self.jpegOf(imagePixelBuffer)
                DispatchQueue.main.async { self.runPhotoJob(jpeg) }
            }
            // The detector always runs: the page draws the target and asks the Voice AI
            // when it sees something. It never drives — voice commands and the gripper
            // buttons own the wheels.
            self.isInferenceQueueBusy = true
            let startTime = Date().millisecondsSince1970
            // All classes, not just the target: the sighting prompt needs whatever
            // else shows up ("what should I do with the cup?").
            let res = self.detector?.recognizeImage(pixelBuffer: imagePixelBuffer, detectionType: "all") ?? []
            let endTime = Date().millisecondsSince1970

            var boxes: [(Detector.Recognition, UIColor)] = []
            if let first = res.filter({ $0.getTitle() == self.currentObject && $0.getConfidence() >= self.MINIMUM_CONFIDENCE_TF_OD_API })
                .max(by: { $0.getConfidence() < $1.getConfidence() }) {
                // Draw only — this page never follows the object.
                boxes.append((first, .green))
            }
            // The sighting that may prompt "what should I do with it?".
            let bestSeen = res.filter { $0.getTitle() != "person" && $0.getConfidence() >= self.MINIMUM_CONFIDENCE_TF_OD_API }
                .max { $0.getConfidence() < $1.getConfidence() }

            DispatchQueue.main.async {

                if (self.frames.count > 0) {
                    for frame in self.frames {
                        frame.removeFromSuperview()
                    }
                }
                self.frames.removeAll()
                for (item, color) in boxes {
                    let frame = self.addFrame(item: item, color: color)
                    self.frames.append(frame)
                    self.cameraView.addSubview(frame)
                }

                if (endTime - startTime) != 0 {
                    NotificationCenter.default.post(name: .updateObjectTrackingFps, object: 1000 / (endTime - startTime))
                }
                self.maybeAskAboutSighting(bestSeen)
            }
            self.isInferenceQueueBusy = false
        }
    }

    /// function to update the data from the controller.
    @objc func updateDataFromControllerApp(_ notification: Notification) {
        if gameController.selectedControlMode == ControlMode.GAMEPAD {
            return
        }
        if notification.object != nil {
            let command = notification.object as! String
            let rightSpeed = command.slice(from: "r:", to: ", ")
            let leftSpeed = command.slice(from: "l:", to: "}}")
            gameController.sendControlFromPhoneController(control: Control(left: Float(Double(leftSpeed ?? "0.0") ?? 0.0), right: Float(Double(rightSpeed ?? "0.0") ?? 0.0)))
        }
    }

    /// update screen command data coming from application
    @objc func updateLightsCommandFromControllerApp(_ notification: Notification) {
        if gameController.selectedControlMode == ControlMode.GAMEPAD {
            return
        }
        if notification.object != nil {
            let command = notification.object as! String
            let controllerCommand = command
                .replacingOccurrences(of: "{", with: "")
                .replacingOccurrences(of: "}", with: "")
                .replacingOccurrences(of: "command:", with: "")
                .trimmingCharacters(in: .whitespacesAndNewlines)

            switch controllerCommand {
            case "SPEED_DOWN":
                self.webSocketMsgHandler.speedDown();
                break;
            case "SPEED_UP":
                self.webSocketMsgHandler.speedUp();
                break;
            case "DRIVE_MODE":
                self.webSocketMsgHandler.driveMode()
                break;
            case "NETWORK":
                break // no auto-follow on this page: nothing to toggle;
            default:
                break
            }
        }
    }

    // MARK: - Voice buttons

    private func toggleReverse() {
        reversed.toggle()
        defaults.set(reversed, forKey: "aipick_flip")
        setStatus(L10n.string(reversed ? "grab_flip_on" : "grab_flip_off"))
    }

    private func bumpGrip(_ delta: Int) {
        setGripCm(gripCm + delta)
    }

    private func setGripCm(_ value: Int) {
        gripCm = max(Self.gripCmMin, min(Self.gripCmMax, value))
        defaults.set(gripCm, forKey: "aipick_grip_cm")
        gripValueLabel?.text = "\(gripCm)"
    }

    /// ✋ (and the AI's release): stop whatever owns the wheels, then open the claw.
    private func openGripper() {
        stopAllMotion() // halts a pick that is creeping in (and its sonar stream)
        guard isBluetoothConnected else { return say(L10n.string("voice_not_connected")) }
        bluetooth.sendData(payload: "g0\n")
        say(L10n.string("aipick_released"))
    }

    // MARK: - The sonar-gated pick

    /**
     ✊ and the AI's gripper grab: creep forward until the front sonar reads ≤ the
     "Grip at (cm)" setting, then close the claw. Already in reach → grip at once. The
     sonar never produced a reading → grab blind after a short creep; it produced
     readings but the object never came within the setting → stop without gripping and
     say so.
     */
    private func sonarPick() {
        guard isBluetoothConnected else { return say(L10n.string("voice_not_connected")) }
        if pickActive { return }
        stopAllMotion() // one driver at a time (clears any old move/script first)
        let cm = bluetooth.sonarDistanceCm
        if cm >= 0, cm < Self.tooCloseCm {
            say(L10n.string("vgrab_failed")) // already touching it: back off, don't crush
            return
        }
        if cm >= 0, cm <= Float(gripCm) {
            closeGrip(cm)
            return
        }
        bluetooth.sendData(payload: "s100\n") // live distance while creeping in
        lastApproach = ProcessInfo.processInfo.systemUptime
        bluetooth.sendData(payload: "a\(Self.approachMs)\n") // firmware: reach 4 cm instead of stopping at 15 cm
        pickActive = true
        pickSeenSonar = cm >= 0
        pickStartedAt = ProcessInfo.processInfo.systemUptime
        setStatus(String(format: L10n.string("aipick_picking"), gripCm))
        sendWheels(Self.pickSpeed, Self.pickSpeed)
        pickTimer?.invalidate()
        pickTimer = Timer.scheduledTimer(withTimeInterval: Self.pickTickSeconds, repeats: true) { [weak self] _ in
            self?.pickTick()
        }
    }

    private func pickTick() {
        guard pickActive else { return }
        let cm = bluetooth.sonarDistanceCm
        if cm >= 0 { pickSeenSonar = true }
        if cm >= 0, cm < Self.tooCloseCm {
            stopPickMotion()
            say(L10n.string("vgrab_failed")) // too close: stop safely, don't crush it
            return
        }
        if cm >= 0, cm <= Float(gripCm) {
            closeGrip(cm)
            return
        }
        let elapsed = ProcessInfo.processInfo.systemUptime - pickStartedAt
        if pickSeenSonar ? elapsed >= Self.pickTimeoutSeconds : elapsed >= Self.pickBlindSeconds {
            if pickSeenSonar {
                stopPickMotion()
                say(String(format: L10n.string("aipick_too_far"), gripCm))
            } else {
                closeGrip(-1) // no sonar on this robot: grab after the short blind creep
            }
            return
        }
        let now = ProcessInfo.processInfo.systemUptime
        if now - lastApproach > Self.approachResend {
            lastApproach = now
            bluetooth.sendData(payload: "a\(Self.approachMs)\n")
        }
        sendWheels(Self.pickSpeed, Self.pickSpeed) // resend (BLE drops packets)
    }

    /// The object is at the grip distance (or the blind creep ran out): stop and close the
    /// claw, then believe the claw's own report — resend once if it is still open, and say
    /// "I can't grab it." rather than claiming a grab that did not happen.
    private func closeGrip(_ cm: Float) {
        stopPickMotion()
        bluetooth.sendData(payload: "g1\n")
        func reportGrabbed() {
            say(cm >= 0 ? String(format: L10n.string("aipick_grabbed"), Double(cm))
                        : L10n.string("aipick_grabbed_blind"))
        }
        // The firmware answers g2 after its 700 ms move: ask once the claw has had time.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            guard let self = self else { return }
            self.bluetooth.sendData(payload: "g2\n")
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) { [weak self] in
                guard let self = self else { return }
                let state = self.bluetooth.gripperState
                if state == 0 {
                    // Still open: one retry, then accept the answer either way.
                    self.bluetooth.sendData(payload: "g1\n")
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
                        guard let self = self else { return }
                        self.bluetooth.sendData(payload: "g2\n")
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) { [weak self] in
                            guard let self = self else { return }
                            if self.bluetooth.gripperState == 0 {
                                self.say(L10n.string("vgrab_failed"))
                            } else {
                                reportGrabbed()
                            }
                        }
                    }
                } else {
                    // 1 = closed; -1 = this firmware never reports (old behaviour: trust it).
                    reportGrabbed()
                }
            }
        }
    }

    /// Stops the creep without gripping (object never came within the setting).
    private func stopPickMotion() {
        pickActive = false
        pickTimer?.invalidate()
        pickTimer = nil
        stopManualMove()
        if isBluetoothConnected {
            bluetooth.sendData(payload: "a0\n") // back to the firmware's 15 cm safety stop
            bluetooth.sendData(payload: "s0\n") // the pick's stream ends with the creep
        }
    }

    // MARK: - Mic uplink (hands-free Xiaozhi conversation)

    private func toggleListening() {
        if !micOn {
            AVAudioSession.sharedInstance().requestRecordPermission { [weak self] granted in
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    guard granted else {
                        self.setStatus(L10n.string("voice_mic_permission_denied"))
                        return
                    }
                    self.setMicOn(true)
                }
            }
        } else {
            setMicOn(false)
        }
    }

    private func setMicOn(_ on: Bool) {
        micOn = on
        micButton?.setTitle(on ? "🎤 ON" : "🎤 OFF", for: .normal)
        if !on {
            localVoiceActive = false
            LocalStt.stop()
            stopMicSession(notify: true)
            setFace(.neutral, talking: false)
        } else if !aiReady {
            startOfflineVoice() // not paired / still connecting: hear the driver on-device
        } else {
            armListening()
        }
    }

    /// Offline mic: one on-device recognition into the same tool commands the assistant
    /// would send (VoiceInterpreter keywords), so "go forward", "turn left" and "grip"
    /// still drive the robot without Xiaozhi.
    private func startOfflineVoice() {
        guard !localVoiceActive else { return }
        localVoiceActive = true
        micButton?.setTitle("🎤 ON", for: .normal)
        setStatus(L10n.string("voice_listening"))
        LocalStt.listen(locale: Locale(identifier: vietnamese ? "vi-VN" : "en-US"),
                        onText: { [weak self] text in
                            guard let self = self else { return }
                            self.localVoiceActive = false
                            self.setMicOn(false)
                            self.showBubble("🗣 " + text)
                            let command = VoiceInterpreter.interpret(text)
                            guard command.action != .unknown else {
                                self.setStatus(L10n.string("voice_no_match"))
                                return
                            }
                            self.executeAiCommand(command)
                        },
                        onError: { [weak self] message in
                            guard let self = self else { return }
                            self.localVoiceActive = false
                            self.setMicOn(false)
                            self.setStatus(message)
                        })
    }

    /// Starts (or re-arms) the hands-free uplink: {@code listen start mode=auto} once, then
    /// keep streaming opus frames until the assistant starts its tts. Barge-in while it speaks.
    private func armListening() {
        if localVoiceActive {
            return // an offline utterance owns the mic until it finishes
        }
        guard aiReady else {
            setStatus(L10n.string("aipick_not_paired")) // still connecting: re-armed on connect
            return
        }
        if micStreaming { return }
        if playbackBusy || queuedPlayCount > 0 {
            // Barge-in: cut the assistant off at once, then take the floor.
            flushPlayback()
            aiSession.sendAbort()
        }
        beginCapture()
        if micStreaming {
            setFace(.listening, talking: false)
        }
    }

    // MARK: Mic capture (AVAudioEngine tap -> 16 kHz mono Int16 -> 60 ms opus frames)

    /// Opens the mic: AVAudioEngine tap -> 16 kHz mono Int16 -> 60 ms raw opus frames.
    private func beginCapture() {
        guard aiReady else {
            setStatus(L10n.string("aipick_not_paired"))
            return
        }
        stopMicCapture()
        do {
            // playAndRecord + defaultToSpeaker keeps audio flowing during TTS playback.
            try AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .default,
                                                            options: [.defaultToSpeaker])
            try AVAudioSession.sharedInstance().setActive(true, options: .notifyOthersOnDeactivation)
        } catch {
            return
        }

        let input = audio.inputNode
        let inputFormat = input.outputFormat(forBus: 0)
        guard
            let pcmFormat = AVAudioFormat(commonFormat: .pcmFormatInt16,
                                          sampleRate: 16000, channels: 1, interleaved: false),
            let converter = AVAudioConverter(from: inputFormat, to: pcmFormat)
        else {
            return
        }
        micConverter = converter
        opusEncoder = OpusCodecEncoder(sampleRate: 16000, channels: 1, frameSize: 960)
        pcmAccumulator.removeAll(keepingCapacity: true)

        input.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { [weak self] buffer, _ in
            self?.consumeMicBuffer(buffer)
        }
        tapInstalled = true
        audio.prepare()
        do {
            try audio.start()
        } catch {
            stopMicCapture()
            return
        }

        // listen start goes out first; the binary frames are queued behind it.
        aiSession.sendListenStart(mode: XiaozhiSession.modeAuto)
        micStreaming = true
    }

    /// Mic tap -> 16 kHz mono Int16 -> 960-sample (60 ms) raw opus packets -> the server.
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
            let pcm = frame.withUnsafeBufferPointer { Data(buffer: $0) }
            if let packet = encoder.encodePCM16(pcm) {
                aiSession.sendBinary(packet)
            }
        }
    }

    /// Tap off, encoder state dropped. (Listen stop is sent by stopMicSession when wanted.)
    private func stopMicCapture() {
        micStreaming = false
        if tapInstalled {
            audio.inputNode.removeTap(onBus: 0)
            tapInstalled = false
        }
        pcmAccumulator.removeAll(keepingCapacity: true)
        micConverter = nil
        opusEncoder = nil
    }

    /// Ends the uplink (mic off, assistant tts start, or screen exit).
    private func stopMicSession(notify: Bool) {
        let wasStreaming = micStreaming
        stopMicCapture()
        if notify && wasStreaming && aiReady {
            aiSession.sendListenStop()
        }
    }

    /// The exchange is over and nobody is speaking: take the floor again (hands-free).
    private func maybeRearm() {
        guard micOn, !speaking else { return }
        guard !playbackBusy, queuedPlayCount == 0 else { return }
        armListening()
    }

    // MARK: - Words and moves

    /// Timed drive from the AI's move tool ("go forward 5 seconds"), auto-stop and resend.
    private func startManualMove(_ cmd: VoiceCommand) {
        guard let wheels = cmd.toWheels() else { return }
        guard isBluetoothConnected else { return say(L10n.string("voice_not_connected")) }
        stopAllMotion() // this move owns the wheels
        startTimedMove(left: wheels.left, right: wheels.right, ms: cmd.durationMs)
    }

    /// Starts a choreographed move (triangle, circle, 360° spin…): its legs queue up as
    /// segments and the move timer runs each one after the other (Voice Control page parity).
    private func playScript(_ command: VoiceCommand) {
        let segs = command.toScript()
        guard !segs.isEmpty else { return }
        guard isBluetoothConnected else { return setStatus(L10n.string("voice_not_connected")) }
        stopAllMotion() // this move owns the wheels (clears any old script)
        moveScript = segs
        advanceScript()
    }

    /// Drives the next leg of the current script; when the queue runs out the robot stops.
    private func advanceScript() {
        guard !moveScript.isEmpty else {
            stopManualMove()
            return
        }
        guard isBluetoothConnected else {
            moveScript.removeAll()
            return setStatus(L10n.string("voice_not_connected"))
        }
        let seg = moveScript.removeFirst()
        startTimedMove(left: seg.left, right: seg.right, ms: seg.ms)
    }

    /// One timed wheel write with the 250 ms resend; expiry advances the script or stops.
    private func startTimedMove(left: Float, right: Float, ms: Int) {
        moveLeft = left
        moveRight = right
        moveEndsAt = ProcessInfo.processInfo.systemUptime + Double(ms) / 1000
        sendWheels(moveLeft, moveRight)
        moveTimer?.invalidate()
        moveTimer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            if ProcessInfo.processInfo.systemUptime >= self.moveEndsAt {
                if !self.moveScript.isEmpty { self.advanceScript() }
                else { self.stopManualMove() }
            } else {
                self.sendWheels(self.moveLeft, self.moveRight)
            }
        }
    }

    /// Stops whatever drives the wheels: manual move, trick script or the pick's creep.
    private func stopManualMove() {
        pickActive = false
        pickTimer?.invalidate()
        pickTimer = nil
        moveScript.removeAll()
        moveTimer?.invalidate()
        moveTimer = nil
        moveEndsAt = 0
        moveLeft = 0
        moveRight = 0
        sendWheels(0, 0)
    }

    private func cycleSpeed(_ up: Bool) {
        let order: [SpeedMode] = [.SLOW, .NORMAL, .FAST]
        var i = order.firstIndex(of: gameController.selectedSpeedMode) ?? 1
        i = max(0, min(order.count - 1, i + (up ? 1 : -1)))
        gameController.selectedSpeedMode = order[i]
        setStatus("\(Strings.speed): \([Strings.slow, Strings.medium, Strings.fast][i])")
    }

    private func reportSonar() {
        guard isBluetoothConnected else { return say(L10n.string("voice_not_connected")) }
        if pickActive { return say(sonarPhrase()) } // the pick's stream is already live
        bluetooth.sendData(payload: "s200\n")     // one report
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) { [weak self] in
            guard let self = self else { return }
            self.say(self.sonarPhrase())
            if !self.pickActive, isBluetoothConnected { self.bluetooth.sendData(payload: "s0\n") }
        }
    }

    private func sonarPhrase() -> String {
        let cm = bluetooth.sonarDistanceCm
        return cm >= 0 ? String(format: L10n.string("voice_sonar_distance"), Double(cm)) : L10n.string("voice_sonar_unknown")
    }

    /// The brain's wheel convention; "⇄" switches (shared with the plain drive words). The
    /// motor channels are cross-wired, so the template's raw sendControl gets a negate-and-swap.
    private func sendWheels(_ left: Float, _ right: Float) {
        DispatchQueue.main.async {
            let scale = GameController.shared.selectedSpeedMode.rawValue
            if self.reversed {
                self.bluetooth.sendData(payload: "c\(Int((left * scale).rounded())),\(Int((right * scale).rounded()))\n")
            } else {
                self.bluetooth.sendData(payload: "c\(Int((-right * scale).rounded())),\(Int((-left * scale).rounded()))\n")
            }
        }
    }

    // MARK: - Speaking

    private func say(_ text: String) {
        showBubble("🤖 " + text)
        let u = AVSpeechUtterance(string: text)
        u.voice = AVSpeechSynthesisVoice(language: "en-US")
        speaking = true
        if micStreaming { stopMicSession(notify: true) } // never listen to the phone's own voice
        flushPlayback() // barge-in: cut the AI off, this phrase wins
        synth.stopSpeaking(at: .immediate)
        synth.speak(u)
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        speaking = false
        face.setFace(lastEmotion, talking: false) // the mouth stops with the voice
        maybeRearm()
    }

    private func showBubble(_ text: String) {
        bubble.text = "  " + text + "  "
        bubble.isHidden = false
    }

    private func setStatus(_ text: String) {
        statusLabel.text = "  " + text + "  "
    }

    private func setFace(_ e: RobotFaceView.Emotion, talking: Bool) {
        lastEmotion = e
        face.setFace(e, talking: talking)
    }

    private static func emotionFromEmoji(_ emotion: String) -> RobotFaceView.Emotion {
        switch emotion {
        case "😀", "😄", "😁", "🙂":
            return .happy
        case "😢", "😭":
            return .sad
        case "😠", "😡":
            return .angry
        case "😮", "😲", "😯":
            return .surprised
        case "😕", "🙃", "🤪":
            return .confused
        case "😴":
            return .sleepy
        default:
            return .neutral
        }
    }

    // MARK: - Voice AI (Xiaozhi)

    /// The Xiaozhi session: hands-free tts speech and the robot tools (drive/grip/…).
    private func wireAiSession() {
        aiSession.onStateChange = { [weak self] state in
            guard let self = self else { return }
            self.aiReady = (state == .ready)
            if state == .ready, self.micOn {
                self.armListening() // the mic was switched on while still connecting
            }
        }
        aiSession.onStt = { [weak self] text in
            guard let self = self else { return }
            self.showBubble("🗣 " + text) // what the assistant heard
            // Safety net: if no robot tool answers these words in time, but they are a clear
            // move/turn/grip command the assistant only chatted about, run them here.
            self.aiUtteranceText = text
            self.aiToolRan = false
            self.aiAssistantWait?.invalidate()
            self.aiAssistantWait = Timer.scheduledTimer(
                withTimeInterval: Self.aiAssistantWaitSeconds, repeats: false
            ) { [weak self] _ in
                self?.onAiUtteranceWindowClosed()
            }
        }
        aiSession.onTtsSentence = { [weak self] text in
            self?.showBubble("🤖 " + text) // the opus frames of its tts are the actual voice
        }
        aiSession.onTtsState = { [weak self] speaking in
            guard let self = self else { return }
            if speaking {
                // Never listen to the robot's own voice.
                self.stopMicSession(notify: true)
                self.setFace(self.lastEmotion, talking: true)
            } else {
                self.finishAiSpeaking()
            }
        }
        aiSession.onBinaryAudio = { [weak self] data, sampleRate in
            guard let self = self else { return }
            // Per protocol, TTS audio is dropped while the mic stream is open.
            guard !self.micStreaming else { return }
            self.enqueuePlayback(data, sampleRate: sampleRate)
        }
        aiSession.onLlmEmotion = { [weak self] emotion, _ in
            guard let self = self else { return }
            self.setFace(Self.emotionFromEmoji(emotion), talking: false)
        }
        aiSession.toolHandler = { [weak self] name, args in
            self?.handleAiTool(name, args) ?? false
        }
        aiSession.photoHandler = { [weak self] question, done in
            guard let self = self else {
                done(nil, "screen closed")
                return
            }
            self.aiToolRan = true // the assistant answered with a tool, not chat
            self.requestPhoto(question, done: done)
        }
        aiSession.fetchHandler = { [weak self] object, done in
            guard let self = self else {
                done(nil, "screen closed")
                return
            }
            self.aiToolRan = true
            self.startAiFetch(object, done: done)
        }
    }

    /// The assistant heard an utterance but no robot tool answered it within the window: when
    /// the words were a plain move/turn/grip command the assistant only chatted about, run them
    /// here. Voice Control's utterance-window safety net, on the server-ASR path this page uses.
    private func onAiUtteranceWindowClosed() {
        guard !aiToolRan, !aiUtteranceText.isEmpty else { return }
        let text = aiUtteranceText
        aiUtteranceText = ""
        let command = VoiceInterpreter.interpret(text)
        guard command.action != .unknown else { return }
        executeAiCommand(command)
    }

    /// The assistant's tools/call lands here: the same tool→VoiceCommand map as the Voice
    /// Control page (VoiceCommand.from(tool:)), then this page's execute path — so the AI's
    /// commands behave identically on both pages.
    private func handleAiTool(_ name: String, _ args: [String: Any]) -> Bool {
        guard let command = VoiceCommand.from(tool: name, args: args) else { return false }
        aiToolRan = true
        executeAiCommand(command)
        return true
    }

    /// Runs one AI command with the Voice Control page's semantics, on this page's machinery:
    /// lights, indicators, sonar, gripper and the choreographed tricks, not just drive moves.
    /// Silent like the Voice Control page — the assistant's own tts reply is the narration.
    /// The gripper's grab is this page's sonar-gated pick.
    private func executeAiCommand(_ command: VoiceCommand) {
        switch command.action {
        case .faster, .slower:
            cycleSpeed(command.action == .faster)
        case .chat, .unknown, .stop:
            stopAllMotion() // halts moves, tricks and the pick's creep
        case .grip:
            sonarPick()
        case .release:
            openGripper()
        case .lightsOn:
            guard isBluetoothConnected else { return setStatus(L10n.string("voice_not_connected")) }
            bluetooth.sendData(payload: "l255,255\n") // stock OpenBot boards
            sendIndicator(left: Self.ledOn, right: Self.ledOn) // FriendBot firmware LEDs
        case .lightsOff:
            guard isBluetoothConnected else { return setStatus(L10n.string("voice_not_connected")) }
            bluetooth.sendData(payload: "l0,0\n")
            sendIndicator(left: Self.ledOff, right: Self.ledOff)
        case .sonar:
            reportSonar()
        case .indicatorLeft:
            guard isBluetoothConnected else { return setStatus(L10n.string("voice_not_connected")) }
            sendIndicator(left: Self.ledBlink, right: Self.ledOff)
        case .indicatorRight:
            guard isBluetoothConnected else { return setStatus(L10n.string("voice_not_connected")) }
            sendIndicator(left: Self.ledOff, right: Self.ledBlink)
        case .indicatorHazard:
            guard isBluetoothConnected else { return setStatus(L10n.string("voice_not_connected")) }
            sendIndicator(left: Self.ledBlink, right: Self.ledBlink)
        case .indicatorOff:
            guard isBluetoothConnected else { return setStatus(L10n.string("voice_not_connected")) }
            // Signals off: hand the LEDs back to the firmware's automatic driving signals.
            sendIndicator(left: Self.ledAuto, right: Self.ledAuto)
        case .sing, .triangle, .circle, .spin360, .turnAround, .wiggle:
            playScript(command)
        default:
            startManualMove(command)
        }
    }

    /// FriendBot firmware LED modes for "i<left>,<right>" (same as the Voice Control page).
    private static let ledOff = 0
    private static let ledOn = 1
    private static let ledBlink = 2
    /// The firmware shows the driving state itself (turning side blinks, both blink when stopped).
    private static let ledAuto = 3

    /// Left/right LEDs, firmware "i<left>,<right>". Not a motion command.
    private func sendIndicator(left: Int, right: Int) {
        bluetooth.sendData(payload: "i\(left),\(right)\n")
    }

    /// The AI's fetch tool: "pick up the …" — the sonar-gated pick (creep in, grip at the
    /// set distance). The assistant speaks the ack. "it" resolves to the seen object.
    private func startAiFetch(_ object: String, done: @escaping (String?, String?) -> Void) {
        guard viewIfLoaded?.window != nil else {
            done(nil, "screen closed")
            return
        }
        guard isBluetoothConnected else {
            done(nil, "Robot not connected")
            return
        }
        let target = Self.resolveFetchObject(object, seen: lastSeenClass, fallback: currentObject)
        guard !target.isEmpty else {
            done(nil, "No object named")
            return
        }
        sonarPick()
        done("Reaching for the " + target + " with the gripper.", nil)
    }

    /// "pick it up" / fetch("it"): a pronoun or an empty name resolves to the last object
    /// the camera saw — the "it" memory the sighting prompt seeds. Otherwise the name stands.
    private static func resolveFetchObject(_ object: String, seen: String, fallback: String) -> String {
        let name = object.trimmingCharacters(in: .whitespaces)
        if name.isEmpty || ["it", "this", "that", "them", "this one", "that one"].contains(name.lowercased()) {
            return seen.isEmpty ? fallback : seen
        }
        return name
    }

    // MARK: - Photos and sightings (📸 button, the AI's photo tool, "what should I do with it?")

    /// Makes the Voice AI speak by injecting text as user speech; false → the caller says it.
    private func speakViaVoiceAi(_ text: String) -> Bool {
        guard aiReady else { return false }
        aiSession.sendListen(text: vietnamese ? text + " (Hãy trả lời bằng tiếng Việt.)"
                                             : text + " (Answer in English.)")
        return true
    }

    /// 📸: describe the next camera frame (the Voice AI speaks the answer and shows it too).
    private func takePictureAndDescribe() {
        guard !photoJobActive else { return }
        photoJobActive = true
        pendingPhotoQuestion = "Describe what the camera sees in detail."
        pendingPhotoDone = nil
        grabFrame = true
    }

    /// The AI's photo tool: same grab, but the answer goes back as the tool result it speaks.
    private func requestPhoto(_ question: String, done: @escaping (String?, String?) -> Void) {
        guard viewIfLoaded?.window != nil else {
            done(nil, "screen closed")
            return
        }
        guard !photoJobActive else {
            done(nil, "camera busy")
            return
        }
        photoJobActive = true
        pendingPhotoQuestion = question.isEmpty ? "Describe what the camera sees in detail." : question
        pendingPhotoDone = done
        grabFrame = true
    }

    /// One grabbed frame on its way to the vision explain service (main thread).
    private func runPhotoJob(_ jpeg: Data?) {
        let question = pendingPhotoQuestion
        let done = pendingPhotoDone
        pendingPhotoQuestion = ""
        pendingPhotoDone = nil
        photoJobActive = false
        guard let jpeg = jpeg else {
            if let done = done {
                done(nil, "Failed to capture photo")
            } else {
                say("Failed to capture photo")
            }
            return
        }
        photoExplainer.explainJpeg(
            jpeg,
            question: question,
            explainUrl: aiSession.visionExplainUrl,
            explainToken: aiSession.visionExplainToken,
            onCaptured: { _ in },
            explained: { [weak self] photoJson, error in
                guard let self = self else { return }
                if let done = done {
                    // The tool result is spoken by the AI's own tts; just note it here.
                    if let photoJson = photoJson {
                        self.showBubble("📸 " + Self.extractPhotoAnswer(photoJson))
                    }
                    done(photoJson, error)
                    return
                }
                guard let photoJson = photoJson else {
                    self.say(error)
                    return
                }
                let answer = Self.extractPhotoAnswer(photoJson)
                self.showBubble("📸 " + answer)
                if !self.speakViaVoiceAi("Tell the user briefly what the robot camera sees right now: " + answer) {
                    self.say(answer) // not paired: the phone's own tts
                }
            })
    }

    /**
     Always-on watcher: when the camera sees an object and nothing else is going on, the
     Voice AI asks the user what to do with it ("What should I do with the cup?"). The spoken
     reply comes back through the same hands-free channel, so "pick it up" starts the fetch
     right away — the seen object is the "it" it resolves to. Rate-limited so it never nags:
     at most one question per 20 s, and the same object only once a minute.
     */
    private func maybeAskAboutSighting(_ seen: Detector.Recognition?) {
        guard let cls = seen?.getTitle(), !cls.isEmpty, cls != "person" else { return }
        guard aiReady, micOn, !speaking else { return }
        guard !playbackBusy, queuedPlayCount == 0 else { return } // mid-exchange: not idle
        guard !pickActive, moveEndsAt == 0, moveScript.isEmpty else { return }
        lastSeenClass = cls // "pick it up" resolves to this object
        let now = ProcessInfo.processInfo.systemUptime
        if now - lastSightingPromptAt < Self.sightingPromptInterval { return }
        if cls == lastSightingClass, now - lastSightingClassAt < Self.sightingSameClassInterval { return }
        lastSightingPromptAt = now
        lastSightingClass = cls
        lastSightingClassAt = now
        aiSession.sendListen(text: "The robot camera sees a " + cls
            + ". In one short question, ask the user what to do with it."
            + (vietnamese ? " (Hãy hỏi bằng tiếng Việt.)" : " (Ask in English.)"))
    }

    /// The prompt language mirrors the Voice Grab page: Vietnamese when the device is set to it.
    private var vietnamese: Bool { Locale.current.languageCode == "vi" }

    private static let jpegContext = CIContext()

    /// The camera frame as JPEG bytes for the vision explain upload.
    private func jpegOf(_ pixelBuffer: CVPixelBuffer) -> Data? {
        let image = CIImage(cvPixelBuffer: pixelBuffer)
        guard let cg = Self.jpegContext.createCGImage(image, from: image.extent) else { return nil }
        return UIImage(cgImage: cg).jpegData(compressionQuality: 0.85)
    }

    /// The vision explain service's answer inside its JSON photo result.
    private static func extractPhotoAnswer(_ photoJson: String) -> String {
        guard let data = photoJson.data(using: .utf8),
              let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let answer = obj["answer"] as? String
        else {
            return photoJson
        }
        return answer
    }

    /// The AI stopped speaking: re-arm the listener once its audio has drained.
    private func finishAiSpeaking() {
        if playbackBusy { return } // the drain callback finishes the turn
        face.setFace(lastEmotion, talking: false) // the mouth stops with the voice
        maybeRearm()
    }

    // MARK: - AI tts playback (server opus -> PCM -> the engine's player node)

    /// Builds the player graph at the server playback rate once; the engine converts the
    /// rate on connect. The player connects as Float32 (the engine's native mixer format) —
    /// required or AVAudioPlayerNode.play() raises an ObjC exception and kills the app.
    private func prepareAudioGraph() -> Bool {
        if !playerAttached {
            if audio.isRunning {
                audio.stop()
            }
            let rate = Double(max(1, aiSession.playbackSampleRate))
            guard let nodeFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32,
                                                 sampleRate: rate, channels: 1, interleaved: false)
            else { return false }
            audio.attach(playerNode)
            audio.connect(playerNode, to: audio.mainMixerNode, format: nodeFormat)
            playbackFormat = nodeFormat
            playerAttached = true
        }
        if !audio.isRunning {
            audio.prepare()
            do {
                try audio.start()
            } catch {
                return false
            }
        }
        return true
    }

    /// One raw opus packet from the AI -> decoded PCM -> queued for playback in order.
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
        // PCM16 -> Float32 (the mixer's native range).
        pcm.withUnsafeBytes { raw in
            let src = raw.bindMemory(to: Int16.self)
            guard let base = src.baseAddress else { return }
            for i in 0..<sampleCount {
                dst[i] = Float(base[i]) / 32768.0
            }
        }
        buffer.frameLength = AVAudioFrameCount(sampleCount)
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
            playerNode.stop()
            if playerAttached {
                audio.detach(playerNode)
                playerAttached = false
            }
            playbackFormat = nil
            if prepareAudioGraph() {
                _ = playerNode.openbot_safePlay()
            }
        }
    }

    /// Everything queued has rendered: the queue must stay empty past the quiet window
    /// (last audio played out) before the AI's turn is over.
    private func scheduleDrainCheck() {
        drainWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            self.drainWork = nil
            guard self.queuedPlayCount == 0, self.playbackBusy else { return }
            self.playbackBusy = false
            self.finishAiSpeaking()
        }
        drainWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35, execute: work)
    }

    /// Barge-in / screen exit: cut the AI off mid-sentence. (A plain tts stop does NOT
    /// come here — queued buffers are left to finish naturally.)
    private func flushPlayback() {
        playerNode.stop() // drops every queued buffer (completions fire as well)
        queuedPlayCount = 0
        playbackBusy = false // the cancelled segment never drains
        drainWork?.cancel()
        drainWork = nil
    }

    /// Full audio teardown on view exit: playback off, engine down.
    private func teardownAiAudio() {
        flushPlayback()
        if playerAttached {
            if audio.isRunning {
                audio.stop()
            }
            audio.detach(playerNode)
            playerAttached = false
        }
        playbackFormat = nil
        opusDecoder = nil
    }
}
