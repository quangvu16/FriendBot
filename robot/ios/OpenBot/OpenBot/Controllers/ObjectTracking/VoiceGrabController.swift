//
//  VoiceGrabController.swift
//  OpenBot
//
//  Voice Grab (AI tab): the Object Tracking page's template — same camera page, same
//  ObjectTrackingSettings panel (model, object, confidence, device, threads, auto switch,
//  dynamic speed) — with talking layered on. The page behaves exactly like Object Tracking
//  while no voice mission runs (updateTarget() + raw sendControl), and a voice command hands
//  the wheels to VoiceGrabBrain (tested in a simulation): "pick up the cup", "bring me the
//  bottle", "put it down", "follow me" (English or Vietnamese), plus the Voice Control page's
//  plain drive words ("go forward", "how far", "grip"). The Object dropdown is the target the
//  words act on when they name none. Mirrors VoiceGrabFragment on Android.
//

import UIKit
import AVFoundation
import CoreImage
import Speech

final class VoiceGrabController: CameraController, AVSpeechSynthesizerDelegate {

    // MARK: - The Object Tracking template (ObjectTrackingFragment)

    var objectTrackingSettings: ObjectTrackingSettings?
    var numberOfThreads: Int = 1
    var bottomAnchorConstraint: NSLayoutConstraint!
    var trailingAnchorConstraint: NSLayoutConstraint!
    var detector: Detector?
    var models: [Model] = []
    var autoMode: Bool = false
    let gameController = GameController.shared
    var vehicleControl: Control = Control()
    let bluetooth = bluetoothDataController.shared
    var currentModel: ModelItem!
    var currentDevice: RuntimeDevice = RuntimeDevice.CPU
    /// The "Object" dropdown's pick: the target the voice commands act on when they name none.
    var currentObject: String = "cup"
    public var MINIMUM_CONFIDENCE_TF_OD_API: Float = 0.5
    private let inferenceQueue = DispatchQueue(label: "openbot.voicegrab.inferencequeue")
    private var isInferenceQueueBusy = false
    private var result: Control?
    private var frames: [UIView] = []
    private var bufferHeight = 0
    private var bufferWidth = 0
    private let edgeOffset: CGFloat = 2.0
    private(set) var useDynamicSpeed: Bool = false
    let webSocketMsgHandler = WebSocketMessageHandler();
    let fragmentType = FragmentType.shared
    private lazy var sensorLinkItem = SensorLinkBarItem(owner: self, screen: "Voice Grab", camera: self) { [unowned self] out in
        out.append(.range("tracking.confidence", "Voice Grab", "Detection confidence (%)", 5, 95, 5,
                          get: { Double((self.MINIMUM_CONFIDENCE_TF_OD_API * 100).rounded()) },
                          set: { NotificationCenter.default.post(name: .updateConfidence, object: Int($0.rounded())) }))
        out.append(.bool("tracking.dynamicSpeed", "Voice Grab", "Slow down when the object is close",
                         get: { self.useDynamicSpeed },
                         set: { self.setDynamicSpeed(isEnabled: $0) }))
    }

    // MARK: - Voice, gripper, sonar

    private let brain = VoiceGrabBrain()
    private let brainLock = NSLock()
    /// The grab sequence wants the object confirmed before the brain acts on it: three
    /// frames in a row above 60% confidence (the spec's "confirm before you move").
    private static let confirmFrames = 3
    private static let confirmConfidence: Float = 0.6
    /// Consecutive confirmed frames of the wanted object; a new command starts it over.
    private var objConfirmStreak = 0
    /// The distinct titles the last frame saw — the answer to "What do you see?".
    private var lastSeenTitles: [String] = []
    private let defaults = UserDefaults.standard
    private var lastApproach: Double = 0

    // The sonar-gated grab ("grip", ✊ and the AI's gripper grab): creep toward the object
    // and close the claw once the front sonar reads within 5 cm of it (the "5 cm rule").
    private var gripPickActive = false
    private var gripPickSeenSonar = false
    private var gripPickStartedAt: Double = 0
    private var gripPickTimer: Timer?
    private static let gripPickSpeed: Float = 0.2 // slow creep toward the object
    private static let gripPickTick: Double = 0.15
    private static let gripPickTimeout: Double = 8
    private static let gripPickBlind: Double = 2.5
    private static let gripAtCm: Float = 5
    private static let gripTooCloseCm: Float = 2
    private var vietnamese = false
    private var reversed = false

    private let synth = AVSpeechSynthesizer()
    private let audio = AVAudioEngine()
    private var speechRecognizer: SFSpeechRecognizer?
    private var speechRequest: SFSpeechAudioBufferRecognitionRequest?
    private var speechTask: SFSpeechRecognitionTask?
    private var listening = false
    private var speaking = false

    // Plain drive words: timed moves with auto-stop and a resend loop (BLE drops packets).
    private var moveEndsAt: Double = 0
    private var moveLeft: Float = 0
    private var moveRight: Float = 0
    private var moveTimer: Timer?
    /// Queued legs of a choreographed trick (the AI's self.robot.trick), one move timer leg at a time.
    private var moveScript: [DriveSegment] = []

    // MARK: - Voice AI (Xiaozhi): photo descriptions and "what should I do with it?" prompts

    private let aiSession = XiaozhiSession()
    private let photoExplainer = PhotoExplainer()
    private var aiReady = false

    // One photo job at a time: grab a frame, then explain it (📸 button or the AI's photo tool).
    private var photoJobActive = false
    private var pendingPhotoQuestion = ""
    private var pendingPhotoDone: ((String?, String?) -> Void)?
    private var grabFrame = false

    // The AI's tts (server opus -> PCM -> the engine's player node).
    private let playerNode = AVAudioPlayerNode()
    private var playerAttached = false
    private var playbackFormat: AVAudioFormat?
    private var opusDecoder: OpusCodecDecoder?
    private var queuedPlayCount = 0
    private var playbackBusy = false
    private var drainWork: DispatchWorkItem?

    // Sighting prompts ("I see a cup — what should I do?") are rate-limited.
    private var lastSightingPromptAt: Double = 0
    private var lastSightingClass = ""
    private var lastSightingClassAt: Double = 0
    private static let sightingPromptInterval: Double = 20
    private static let sightingSameClassInterval: Double = 60

    // Over the camera.
    private let face = RobotFaceView()
    private let bubble = UILabel()
    private let statusLabel = UILabel()
    private let buttonsRow = UIStackView()
    private let gripperStateLabel = UILabel()
    private var micButton: UIButton?
    private var langButton: UIButton?
    private var lastEmotion: RobotFaceView.Emotion = .neutral

    /// Claw words the fetch parser does not know ("grab"/"hold" are already its pick words).
    private static let gripWords = ["grip", "grip it", "close the claw", "close claw", "kep"]

    private static func now() -> Double { Date().timeIntervalSince1970 * 1000 }

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
        if let lastModel = preferencesManager.getObjectTrackModel(), !lastModel.isEmpty,
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
        objectTrackingSettings = ObjectTrackingSettings(frame: CGRect(x: 0, y: height - 375, width: width, height: 375), detector: detector, model: currentModel)
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
        NotificationCenter.default.addObserver(self, selector: #selector(toggleAutoMode), name: .autoModeObjectTracking, object: nil)
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
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { [weak self] in self?.toggleListening() }
    }

    /// Face (top right), what was said (a bubble), status line and the voice/gripper buttons,
    /// floating over the camera above the settings panel.
    private func setupVoiceOverlay() {
        vietnamese = defaults.object(forKey: "vgrab_vi") as? Bool ?? (Locale.current.languageCode == "vi")
        reversed = defaults.bool(forKey: "grab_reverse")

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
        statusLabel.text = "  " + L10n.string("vgrab_ready") + "  "
        view.addSubview(statusLabel)

        buttonsRow.axis = .horizontal
        buttonsRow.spacing = 6
        buttonsRow.distribution = .fillEqually
        view.addSubview(buttonsRow)
        micButton = addVoiceButton("🎤 OFF") { [weak self] in self?.toggleListening() }
        langButton = addVoiceButton(vietnamese ? "VI" : "EN") { [weak self] in self?.toggleLanguage() }
        addVoiceButton("📸") { [weak self] in self?.takePictureAndDescribe() }
        addVoiceButton("✊") { [weak self] in self?.creepAndGrip(speak: false) }
        addVoiceButton("✋") { [weak self] in self?.manualGrip(false) }
        addVoiceButton("⇄") { [weak self] in self?.toggleReverse() }

        // Live claw state: the digital firmware answers g0/g1 after every move.
        gripperStateLabel.font = .systemFont(ofSize: 12, weight: .medium)
        gripperStateLabel.textColor = .white
        gripperStateLabel.backgroundColor = UIColor.black.withAlphaComponent(0.55)
        gripperStateLabel.layer.cornerRadius = 8
        gripperStateLabel.clipsToBounds = true
        gripperStateLabel.textAlignment = .center
        view.addSubview(gripperStateLabel)
        NotificationCenter.default.addObserver(self, selector: #selector(refreshGripperState),
                                               name: .updateLabel, object: nil)
        refreshGripperState()
    }

    /// The chip above the status line: what the firmware last reported (g0/g1).
    @objc private func refreshGripperState() {
        let text: String
        switch bluetooth.gripperState {
        case 0:
            text = Strings.hwStateOpen
        case 1:
            text = Strings.hwStateClosed
        default:
            text = Strings.hwStateUnknown
        }
        gripperStateLabel.text = "  " + text + "  "
    }

    private func addVoiceButton(_ title: String, _ action: @escaping () -> Void) -> UIButton {
        let b = UIButton(type: .system)
        b.setTitle(title, for: .normal)
        b.setTitleColor(UIColor(red: 0.13, green: 0.55, blue: 1.0, alpha: 1), for: .normal)
        b.titleLabel?.font = .systemFont(ofSize: 16, weight: .semibold)
        b.backgroundColor = UIColor.white.withAlphaComponent(0.92)
        b.layer.cornerRadius = 8
        b.addAction(UIAction { _ in action() }, for: .touchUpInside)
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
            buttonsRow.frame = CGRect(x: 8, y: height - 375 - 52, width: width - 16, height: 44)
            statusLabel.frame = CGRect(x: 12, y: height - 375 - 82, width: width - 24, height: 24)
            gripperStateLabel.frame = CGRect(x: 12, y: height - 375 - 110, width: width - 24, height: 24)
        } else {
            objectTrackingSettings?.frame.origin.x = height - 375
            objectTrackingSettings?.frame.origin.y = 30
            buttonsRow.frame = CGRect(x: 8, y: 40, width: height - 375 - 16, height: 44)
            statusLabel.frame = CGRect(x: 12, y: 8, width: height - 375 - 16, height: 24)
            gripperStateLabel.frame = CGRect(x: 12, y: 90, width: height - 375 - 16, height: 24)
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
        aiSession.connect() // the Voice AI: photo talk and "what should I do?" questions
        switch objectTrackingSettings?.autoModeButton.isOn {
        case true:
            setAutoMode(true)
        default:
            autoMode = false
        }
        // The claw state: ask the firmware whenever the page comes up on a live link.
        if isBluetoothConnected {
            bluetooth.sendData(payload: "g2\n")
        }
    }

    /// Called after the view was dismissed, covered or otherwise hidden.
    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        sensorLinkItem.disappeared()
        aiSession.disconnect()
        teardownAiAudio()
        stopListening()
        synth.stopSpeaking(at: .immediate)
        stopManualMove()
        if isBluetoothConnected {
            bluetooth.sendData(payload: "a0\n")
            bluetooth.sendData(payload: "s0\n")
        }
        if autoMode {
            autoMode = false
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

    /// function to toggle the auto mode (the panel's switch posts this)
    @objc func toggleAutoMode() {
        setAutoMode(objectTrackingSettings?.autoModeButton.isOn ?? false)
    }

    /// The template's auto switch, plus this page's sonar stream and voice-brain reset.
    func setAutoMode(_ enabled: Bool) {
        stopManualMove() // one driver at a time
        autoMode = enabled
        if isBluetoothConnected {
            bluetooth.sendData(payload: enabled ? "s100\n" : "a0\ns0\n")
        }
        if !enabled {
            brainLock.lock()
            brain.command(VoiceGrabParser.parse("stop"), now: Self.now())
            brainLock.unlock()
        }
    }

    /// function to send the controls to the device.
    func sendControl(control: Control) {
        if (control.getRight() != vehicleControl.getRight() || control.getLeft() != vehicleControl.getLeft()) {
            let left = control.getLeft() * gameController.selectedSpeedMode.rawValue
            let right = control.getRight() * gameController.selectedSpeedMode.rawValue
            vehicleControl = control
            bluetooth.sendData(payload: "c" + String(left) + "," + String(right) + "\n")
            NotificationCenter.default.post(name: .updateSpeedLabel, object: String(Int(left)) + "," + String(Int(right)))
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
            let newBackButton = UIBarButtonItem(image: backNavigationIcon, title: L10n.string("tile_voice_grab"), target: self, action: #selector(VoiceGrabController.back(sender:)), titleColor: Colors.navigationColor ?? .white)
            navigationItem.leftBarButtonItem = newBackButton
        }
        // Sensor link (SensorServer WebSocket): share this phone's sensors or use another phone's.
        navigationItem.rightBarButtonItem = sensorLinkItem
    }

    @objc func back(sender: UIBarButtonItem) {
        _ = navigationController?.popViewController(animated: true)
    }

    func updateTarget(_ detection: CGRect) -> Control {

        // Left and right wheels control values
        var left: Float = 0.0
        var right: Float = 0.0

        let frameWidth = UIScreen.main.bounds.size.width
        let frameHeight = UIScreen.main.bounds.size.height

        let dx: CGFloat = CGFloat(detector!.getImageSizeX()) / frameWidth
        let dy: CGFloat = CGFloat(detector!.getImageSizeY()) / frameHeight
        let trackedPos = detection.applying(CGAffineTransform(scaleX: dx, y: dy))

        // Calculate track box area for distance estimate
        let boxArea: Float = Float(trackedPos.height * trackedPos.width)

        // Make sure object center is in frame
        var centerX: Float = Float(trackedPos.midX)
        centerX = max(0, min(centerX, Float(frameWidth)))

        // Scale relative position along x-axis between -1 and 1
        let x_pos_norm: Float = 1.0 - 2.0 * centerX / Float(frameWidth)

        // Generate vehicle controls
        if (x_pos_norm < 0.0) {
            left = 1.0
            right = 1.0 + x_pos_norm
        } else {
            left = 1 - x_pos_norm
            right = 1.0
        }

        // Adjust speed depending on size of detected object bounding box
        if (objectTrackingSettings!.dynamicSpeedCheckbox.isChecked) {  //  Set use of dynamic speed on or off (used in updateTarget())
            var scaleFactor: Float = 1.0 - boxArea / Float(frameWidth * frameHeight)
            scaleFactor = scaleFactor > 0.75 ? 1.0 : scaleFactor // tracked object far, full speed
            // apply scale factor if tracked object is not too near, otherwise stop
            if (scaleFactor > 0.25) {
                left *= scaleFactor
                right *= scaleFactor
            } else {
                left = 0.0
                right = 0.0
            }
        }

        return Control(left: left, right: right)
    }

    /// Set use of dynamic speed on or off (used in updateTarget())
    ///
    /// - Parameter isEnabled
    func setDynamicSpeed(isEnabled: Bool) {
        self.useDynamicSpeed = isEnabled
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

    /// callback function when any image is clicked on voice grab fragment.
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
            // The detector runs even with the auto switch off: the page always tracks,
            // always draws, and always asks the Voice AI when it sees something — only
            // the driving still needs the auto switch.
            self.isInferenceQueueBusy = true
            let startTime = Date().millisecondsSince1970
            // All classes, not just the target: the voice brain also needs the person
            // ("bring me the me", "follow me").
            let res = self.detector?.recognizeImage(pixelBuffer: imagePixelBuffer, detectionType: "all") ?? []
            let endTime = Date().millisecondsSince1970

            let w = CGFloat(CVPixelBufferGetWidth(imagePixelBuffer)), h = CGFloat(CVPixelBufferGetHeight(imagePixelBuffer))
            self.brainLock.lock()
            // The words' object and destination — never the Object dropdown.
            let wanted = self.brain.object
            let wantsPerson = self.brain.wantsPerson
            let wantsDest = self.brain.wantsDest
            let wantedDest = self.brain.dest
            let mission = self.brain.mode != .idle
            // "What do you see?": the distinct titles this frame saw (either mode).
            var titles: [String] = []
            for r in res where r.getConfidence() >= self.MINIMUM_CONFIDENCE_TF_OD_API {
                let t = r.getTitle()
                if !titles.contains(t) { titles.append(t) }
            }
            self.lastSeenTitles = titles
            var controlResult = Control(left: 0, right: 0)
            var boxes: [(Detector.Recognition, UIColor)] = []
            var bestSeen: Detector.Recognition?
            if mission {
                func best(_ title: String) -> Detector.Recognition? {
                    guard !title.isEmpty else { return nil }
                    return res.filter { $0.getTitle() == title && $0.getConfidence() >= self.MINIMUM_CONFIDENCE_TF_OD_API }
                        .max { $0.getConfidence() < $1.getConfidence() }
                }
                // The object must be confirmed before the brain acts on it: three frames in a
                // row above 60% confidence, and — when the words named a colour ("the red
                // block") — the pixels inside the box must actually look like it. A miss in
                // either check resets the streak, so one flicker frame never confirms.
                var cand: Detector.Recognition?
                if !wanted.isEmpty {
                    let hit = res.filter { $0.getTitle() == wanted && $0.getConfidence() >= Self.confirmConfidence }
                        .max { $0.getConfidence() < $1.getConfidence() }
                    if let r = hit, self.colorMatches(self.brain.color, r, imagePixelBuffer, w, h) {
                        cand = r
                    }
                }
                if cand != nil { self.objConfirmStreak += 1 } else { self.objConfirmStreak = 0 }
                let confirmed = self.objConfirmStreak >= Self.confirmFrames
                let o = confirmed ? self.measure(cand, w, h) : nil
                let p = wantsPerson ? self.measure(best("person"), w, h) : nil
                let d = wantsDest ? self.measure(best(wantedDest), w, h) : nil
                if let r = cand, confirmed { boxes.append((r, .green)) }
                if wantsPerson, let r = best("person") { boxes.append((r, .cyan)) }
                if wantsDest, let r = best(wantedDest) { boxes.append((r, .orange)) }
                let out = self.brain.step(now: Self.now(), objSeen: o != nil, objOffset: o?.0 ?? 0, objHeight: o?.1 ?? 0,
                                          personSeen: p != nil, personOffset: p?.0 ?? 0, personHeight: p?.1 ?? 0,
                                          destSeen: d != nil, destOffset: d?.0 ?? 0, destHeight: d?.1 ?? 0,
                                          sonarCm: self.bluetooth.sonarDistanceCm,
                                          gripperState: self.bluetooth.gripperState)
                self.brainLock.unlock()
                self.apply(out, fromCamera: true)
            } else {
                self.brainLock.unlock()
                // The sighting that may prompt "what should I do with it?".
                bestSeen = res.filter { $0.getTitle() != "person" && $0.getConfidence() >= self.MINIMUM_CONFIDENCE_TF_OD_API }
                    .max { $0.getConfidence() < $1.getConfidence() }
                // No voice mission: plain Object Tracking behaviour (the template).
                if let first = res.filter({ $0.getTitle() == self.currentObject && $0.getConfidence() >= self.MINIMUM_CONFIDENCE_TF_OD_API })
                    .max(by: { $0.getConfidence() < $1.getConfidence() }) {
                    controlResult = self.updateTarget(first.getLocation())
                    boxes.append((first, .green))
                }
            }

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
                if !mission {
                    self.sendControl(control: self.autoMode ? controlResult : Control())
                    self.maybeAskAboutSighting(bestSeen)
                }
            }
            self.isInferenceQueueBusy = false
        }
    }

    /**
     Offset (-1 left … +1 right) and height (0…1) of a detection; the phone's buffers are
     portrait already (videoOrientation = .portrait), so the box maps straight over.
     */
    private func measure(_ r: Detector.Recognition?, _ w: CGFloat, _ h: CGFloat) -> (Float, Float)? {
        guard let r = r, w > 0, h > 0 else { return nil }
        let box = r.getLocation()
        // The front camera's raw buffer is mirrored vs. the robot's frame (Android pre-flips
        // its bitmap instead): negate the offset so +1 is always the robot's right.
        let front = (captureSession.inputs[0] as? AVCaptureDeviceInput)?.device.position == .front
        let sign: Float = front ? -1 : 1
        return (sign * Float((box.midX / w) * 2 - 1), Float(box.height / h))
    }

    /// True when the detection's pixels look like the colour the words asked for ("the red
    /// block"); nil colour → always true. Samples a 5×5 grid inside the middle half of the
    /// box — the object, not its surroundings — and wants most coloured pixels to agree.
    private func colorMatches(_ color: String?, _ r: Detector.Recognition, _ buffer: CVPixelBuffer, _ w: CGFloat, _ h: CGFloat) -> Bool {
        guard let want = color, !want.isEmpty else { return true }
        let box = r.getLocation().intersection(CGRect(x: 0, y: 0, width: w, height: h))
        guard !box.isNull, box.width >= 4, box.height >= 4 else { return false }
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return false }
        let bpr = CVPixelBufferGetBytesPerRow(buffer)
        let fmt = CVPixelBufferGetPixelFormatType(buffer)
        // The camera hands out BGRA; tolerate RGBA too. Anything else: don't filter.
        guard fmt == kCVPixelFormatType_32BGRA || fmt == kCVPixelFormatType_32RGBA else { return true }
        let redFirst = (fmt == kCVPixelFormatType_32RGBA)
        let inner = box.insetBy(dx: box.width * 0.25, dy: box.height * 0.25)
        var matches = 0, coloured = 0, samples = 0
        for iy in 0..<5 {
            for ix in 0..<5 {
                let px = Int(inner.minX + inner.width * (CGFloat(ix) + 0.5) / 5)
                let py = Int(inner.minY + inner.height * (CGFloat(iy) + 0.5) / 5)
                guard px >= 0, py >= 0, px < Int(w), py < Int(h) else { continue }
                let ptr = base.advanced(by: py * bpr + px * 4)
                let b0 = ptr.load(fromByteOffset: 0, as: UInt8.self)
                let b1 = ptr.load(fromByteOffset: 1, as: UInt8.self)
                let b2 = ptr.load(fromByteOffset: 2, as: UInt8.self)
                let rr = CGFloat(redFirst ? b0 : b2), gg = CGFloat(b1), bb = CGFloat(redFirst ? b2 : b0)
                samples += 1
                let (hue, sat) = Self.hueSat(rr, gg, bb)
                if sat >= 0.3 {
                    coloured += 1
                    if Self.hue(want, matches: hue) { matches += 1 }
                }
            }
        }
        // Mostly grey pixels = no colour to judge by: treat as a mismatch.
        guard samples > 0, coloured * 2 >= samples else { return false }
        return matches * 2 >= coloured
    }

    /// RGB (0…255) → hue in degrees and saturation 0…1.
    private static func hueSat(_ r: CGFloat, _ g: CGFloat, _ b: CGFloat) -> (CGFloat, CGFloat) {
        let rn = r / 255, gn = g / 255, bn = b / 255
        let mx = max(rn, gn, bn), mn = min(rn, gn, bn)
        let d = mx - mn
        guard d > 0 else { return (0, 0) }
        var hue: CGFloat
        if mx == rn { hue = ((gn - bn) / d).truncatingRemainder(dividingBy: 6) }
        else if mx == gn { hue = (bn - rn) / d + 2 }
        else { hue = (rn - gn) / d + 4 }
        hue *= 60
        if hue < 0 { hue += 360 }
        return (hue, mx > 0 ? d / mx : 0)
    }

    /// The hue band for a canonical colour name (red wraps around 0°).
    private static func hue(_ want: String, matches h: CGFloat) -> Bool {
        switch want {
        case "red": return h < 15 || h >= 345
        case "blue": return h >= 190 && h <= 250
        case "green": return h >= 80 && h <= 160
        case "yellow": return h >= 40 && h <= 70
        default: return true
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
                if let autoModeButton = self.objectTrackingSettings?.autoModeButton {
                    autoModeButton.setOn(!autoModeButton.isOn, animated: true)
                    self.objectTrackingSettings?.switchButton(autoModeButton)
                }
                break;
            default:
                break;
            }
        }
    }

    // MARK: - Voice buttons

    private func toggleLanguage() {
        vietnamese.toggle()
        defaults.set(vietnamese, forKey: "vgrab_vi")
        langButton?.setTitle(vietnamese ? "VI" : "EN", for: .normal)
        if listening { stopListening(); startListening() }
    }

    private func toggleReverse() {
        reversed.toggle()
        defaults.set(reversed, forKey: "grab_reverse")
        setStatus(L10n.string(reversed ? "grab_flip_on" : "grab_flip_off"))
    }

    private func manualGrip(_ close: Bool) {
        guard isBluetoothConnected else { return setStatus(L10n.string("voice_not_connected")) }
        bluetooth.sendData(payload: close ? "g1\n" : "g0\n")
        brainLock.lock()
        brain.setHolding(close)
        brainLock.unlock()
    }

    /// ✊, the claw words and the AI's gripper grab: creep toward the object and close the claw
    /// once the front sonar reads within 5 cm of it (the "5 cm rule", AiPick parity). Already
    /// in reach → grip at once; no sonar at all → grab after a short blind creep; it produced
    /// readings but the object never came within 5 cm → stop without gripping.
    private func creepAndGrip(speak: Bool) {
        guard isBluetoothConnected else {
            if speak { say(L10n.string("voice_not_connected")) } else { setStatus(L10n.string("voice_not_connected")) }
            return
        }
        if gripPickActive { return }
        setAutoMode(false) // one driver at a time (clears any mission or manual move first)
        let cm = bluetooth.sonarDistanceCm
        if cm >= 0, cm < Self.gripTooCloseCm {
            return gripPickFail(speak) // already touching it: back off, don't crush
        }
        if cm >= 0, cm <= Self.gripAtCm {
            manualGrip(true)
            if speak { say(L10n.string("vgrab_gripped")) }
            return
        }
        bluetooth.sendData(payload: "s100\n") // live distance while creeping in
        lastApproach = Self.now()
        bluetooth.sendData(payload: "a3000\n") // firmware: reach 4 cm instead of stopping at 15 cm
        gripPickActive = true
        gripPickSeenSonar = cm >= 0
        gripPickStartedAt = ProcessInfo.processInfo.systemUptime
        setStatus(String(format: L10n.string("aipick_picking"), Int(Self.gripAtCm)))
        sendWheels(Self.gripPickSpeed, Self.gripPickSpeed)
        gripPickTimer?.invalidate()
        gripPickTimer = Timer.scheduledTimer(withTimeInterval: Self.gripPickTick, repeats: true) { [weak self] _ in
            self?.gripPickTick(speak)
        }
    }

    private func gripPickTick(_ speak: Bool) {
        guard gripPickActive else { return }
        let cm = bluetooth.sonarDistanceCm
        if cm >= 0 { gripPickSeenSonar = true }
        if cm >= 0, cm < Self.gripTooCloseCm {
            stopGripPick()
            return gripPickFail(speak) // too close: stop safely, don't crush it
        }
        if cm >= 0, cm <= Self.gripAtCm {
            stopGripPick()
            manualGrip(true)
            if speak { say(L10n.string("vgrab_gripped")) }
            return
        }
        let seenSonar = gripPickSeenSonar
        let elapsed = ProcessInfo.processInfo.systemUptime - gripPickStartedAt
        if seenSonar ? elapsed >= Self.gripPickTimeout : elapsed >= Self.gripPickBlind {
            stopGripPick()
            if seenSonar {
                setStatus(String(format: L10n.string("aipick_too_far"), Int(Self.gripAtCm)))
            } else {
                manualGrip(true) // no sonar on this robot: grab after the short blind creep
                if speak { say(L10n.string("vgrab_gripped")) }
            }
            return
        }
        let now = Self.now()
        if now - lastApproach > 2000 {
            lastApproach = now
            bluetooth.sendData(payload: "a3000\n")
        }
        sendWheels(Self.gripPickSpeed, Self.gripPickSpeed) // resend (BLE drops packets)
    }

    private func gripPickFail(_ speak: Bool) {
        if speak { say(L10n.string("vgrab_failed")) } else { setStatus(L10n.string("vgrab_failed")) }
    }

    /// Stops the creep without gripping (and ends its sonar stream).
    private func stopGripPick() {
        gripPickActive = false
        gripPickTimer?.invalidate()
        gripPickTimer = nil
        stopManualMove()
        if isBluetoothConnected, !autoMode {
            bluetooth.sendData(payload: "s0\n") // the grab's stream ends with the creep
        }
    }

    // MARK: - Listening

    private func toggleListening() {
        if listening { stopListening(); return }
        SFSpeechRecognizer.requestAuthorization { [weak self] status in
            DispatchQueue.main.async {
                guard let self = self else { return }
                guard status == .authorized else { return self.setStatus(L10n.string("vgrab_no_speech")) }
                AVAudioSession.sharedInstance().requestRecordPermission { ok in
                    DispatchQueue.main.async {
                        if ok { self.listening = true; self.startListening() }
                        else { self.setStatus(L10n.string("voice_mic_permission_denied")) }
                        self.updateMicButton()
                    }
                }
            }
        }
    }

    private func updateMicButton() {
        micButton?.setTitle(listening ? "🎤 ON" : "🎤 OFF", for: .normal)
        setFace(listening ? .listening : .neutral, talking: false)
    }

    private func startListening() {
        guard listening, !speaking, speechTask == nil else { return }
        speechRecognizer = SFSpeechRecognizer(locale: Locale(identifier: vietnamese ? "vi-VN" : "en-US"))
        guard let recognizer = speechRecognizer, recognizer.isAvailable else { return setStatus(L10n.string("vgrab_no_speech")) }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .measurement, options: [.defaultToSpeaker, .duckOthers])
            try session.setActive(true, options: .notifyOthersOnDeactivation)
        } catch { return }
        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = false
        speechRequest = req
        let input = audio.inputNode
        let format = input.outputFormat(forBus: 0)
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak req] buffer, _ in req?.append(buffer) }
        audio.prepare()
        do { try audio.start() } catch { return }
        speechTask = recognizer.recognitionTask(with: req) { [weak self] result, error in
            guard let self = self else { return }
            if let result = result, result.isFinal {
                let guesses = [result.bestTranscription.formattedString] + result.transcriptions.map { $0.formattedString }
                DispatchQueue.main.async { self.onHeard(guesses) }
            }
            if error != nil || (result?.isFinal ?? false) {
                DispatchQueue.main.async {
                    self.endRecognition()
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { self.startListening() }
                }
            }
        }
        // One sentence at a time: end the request after a short pause so results arrive.
        DispatchQueue.main.asyncAfter(deadline: .now() + 6) { [weak self, weak req] in
            if let req = req, self?.speechRequest === req { req.endAudio() }
        }
    }

    private func endRecognition() {
        audio.stop()
        audio.inputNode.removeTap(onBus: 0)
        speechRequest?.endAudio()
        speechTask?.cancel()
        speechTask = nil
        speechRequest = nil
    }

    private func stopListening() {
        listening = false
        endRecognition()
        updateMicButton()
    }

    // MARK: - Words

    /// What the recognizer heard (best guesses first): the first one that means something wins.
    private func onHeard(_ guesses: [String]) {
        var text = guesses.first ?? ""
        var request = VoiceGrabParser.parse(text)
        for g in guesses {
            let r = VoiceGrabParser.parse(g)
            if r.intent != .unknown { request = r; text = g; break }
        }
        guard !text.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        showBubble("🗣 " + text)
        if request.intent == .unknown {
            // Claw words and the Voice Control page's plain drive words are not in the
            // fetch parser's vocabulary; handle them before giving up.
            let folded = VoiceGrabParser.plain(text)
            if Self.gripWords.contains(where: { VoiceInterpreter.containsWord(folded, $0) }) {
                creepAndGrip(speak: true) // move to the object and grip it at 5 cm
                return
            }
            let cmd = VoiceInterpreter.interpret(text)
            switch cmd.action {
            case .forward, .backward, .left, .right:
                startManualMove(cmd)
                return
            case .sonar:
                reportSonar()
                return
            case .faster, .slower:
                cycleSpeed(cmd.action == .faster)
                return
            case .grip:
                creepAndGrip(speak: true) // move to the object and grip it at 5 cm
                return
            case .release:
                manualGrip(false)
                say(L10n.string("vgrab_released"))
                return
            default:
                break // let the brain answer "Sorry, I didn't get that"
            }
        }
        // "What do you see?" and "did you pick it up?" are answered from the last frame
        // and the claw's own report — they never start a mission.
        if request.intent == .see { return answerSee() }
        if request.intent == .check { return answerCheck() }
        // The object to recognize comes from the words (or the brain's memory of them): the
        // Object dropdown never feeds the mission — it stays the template's tracker target.
        let drives = request.intent == .pick || request.intent == .bring || request.intent == .find
            || request.intent == .nextTo || request.intent == .putDown || request.intent == .follow
            || request.intent == .approach
        if drives && !isBluetoothConnected {
            return say(L10n.string("voice_not_connected"))
        }
        brainLock.lock()
        objConfirmStreak = 0 // a new command starts the three-frame count from scratch
        brain.command(request, now: Self.now())
        let active = brain.mode != .idle
        brainLock.unlock()
        if drives && active && !autoMode {
            setAutoMode(true) // the camera loop runs the brain
        }
        var reply: VoiceGrabBrain.Out?
        if !drives || !active || !autoMode {
            brainLock.lock()
            // "hello" / "thank you" / "stop" / unknown, or a fetch command that started no
            // mission: answer straight away (the camera loop never steps an idle brain).
            reply = brain.step(now: Self.now(), objSeen: false, objOffset: 0, objHeight: 0,
                               personSeen: false, personOffset: 0, personHeight: 0,
                               destSeen: false, destOffset: 0, destHeight: 0, sonarCm: -1)
            brainLock.unlock()
        }
        if let reply = reply { apply(reply, fromCamera: false) }
    }

    /// Timed drive from the plain words ("go forward 5 seconds"), auto-stop and resend.
    private func startManualMove(_ cmd: VoiceCommand) {
        guard let wheels = cmd.toWheels() else { return }
        guard isBluetoothConnected else { return say(L10n.string("voice_not_connected")) }
        setAutoMode(false) // the brain stops; this move owns the wheels
        startTimedMove(left: wheels.left, right: wheels.right, ms: cmd.durationMs)
    }

    /// Starts a choreographed move (triangle, circle, 360° spin…): its legs queue up as
    /// segments and the move timer runs each one after the other (Voice Control page parity).
    private func playScript(_ command: VoiceCommand) {
        let segs = command.toScript()
        guard !segs.isEmpty else { return }
        guard isBluetoothConnected else { return setStatus(L10n.string("voice_not_connected")) }
        setAutoMode(false) // the brain stops; this move owns the wheels (clears any old script)
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

    private func stopManualMove() {
        gripPickActive = false // any motion stop also halts the grab's creep
        gripPickTimer?.invalidate()
        gripPickTimer = nil
        if isBluetoothConnected { bluetooth.sendData(payload: "a0\n") } // back to the 15 cm safety stop
        moveScript.removeAll()
        moveTimer?.invalidate()
        moveTimer = nil
        moveEndsAt = 0
        moveLeft = 0
        moveRight = 0
        sendWheels(0, 0)
        DispatchQueue.main.async {
            self.sendControl(control: Control()) // resync the de-duplication state after the raw writes
        }
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
        if autoMode { return say(sonarPhrase()) } // already streaming
        bluetooth.sendData(payload: "s200\n")     // one report
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) { [weak self] in
            guard let self = self else { return }
            self.say(self.sonarPhrase())
            if !self.autoMode, isBluetoothConnected { self.bluetooth.sendData(payload: "s0\n") }
        }
    }

    private func sonarPhrase() -> String {
        let cm = bluetooth.sonarDistanceCm
        return cm >= 0 ? String(format: L10n.string("voice_sonar_distance"), cm) : L10n.string("voice_sonar_unknown")
    }

    /// "What do you see?": the distinct things the last frame saw.
    private func answerSee() {
        brainLock.lock()
        let titles = lastSeenTitles
        brainLock.unlock()
        guard !titles.isEmpty else { return say(L10n.string("vgrab_see_none")) }
        let names = titles.prefix(3).map { article($0) }
        let list = names.count == 1 ? names[0]
            : names.dropLast().joined(separator: ", ") + " and " + names[names.count - 1]
        say(String(format: L10n.string("vgrab_see"), list))
    }

    /// "a bottle" / "an apple" for the sighting list.
    private func article(_ name: String) -> String {
        let first = name.lowercased().first.map { String($0) } ?? ""
        return ("aeiou".contains(first) ? "an " : "a ") + name
    }

    /// "Did you pick it up?": the claw's own report (g2 → g0/g1), never a guess.
    private func answerCheck() {
        func report(_ state: Int) {
            // Unknown counts as "no": never claim success the claw did not confirm.
            say(L10n.string(state == 1 ? "vgrab_check_yes" : "vgrab_check_no"))
        }
        let state = bluetooth.gripperState
        if state >= 0 { return report(state) }
        guard isBluetoothConnected else { return say(L10n.string("voice_not_connected")) }
        bluetooth.sendData(payload: "g2\n") // one report of the claw's own state
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) { [weak self] in
            guard let self = self else { return }
            report(self.bluetooth.gripperState)
        }
    }

    // MARK: - Speaking

    private func say(_ text: String) {
        showBubble("🤖 " + text)
        let u = AVSpeechUtterance(string: text)
        u.voice = AVSpeechSynthesisVoice(language: vietnamese ? "vi-VN" : "en-US")
        speaking = true
        endRecognition() // never listen to itself
        flushPlayback() // barge-in: cut the AI off, this phrase wins
        synth.stopSpeaking(at: .immediate)
        synth.speak(u)
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        speaking = false
        face.setFace(lastEmotion, talking: false) // the mouth stops with the voice
        if listening { DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in self?.startListening() } }
    }

    private func showBubble(_ text: String) {
        bubble.text = "  " + text + "  "
        bubble.isHidden = false
    }

    private func setStatus(_ text: String) {
        statusLabel.text = "  " + text + "  "
    }

    private func setFace(_ f: VoiceGrabBrain.Face?, talking: Bool) {
        guard let f = f else { return }
        let e: RobotFaceView.Emotion
        switch f {
        case .neutral: e = .neutral
        case .listening: e = .listening
        case .thinking: e = .thinking
        case .happy: e = .happy
        case .surprised: e = .surprised
        case .sad: e = .sad
        case .confused: e = .confused
        }
        lastEmotion = e
        face.setFace(e, talking: talking)
    }

    // MARK: - The brain's decisions

    /// Carries out one decision: wheels, claw, approach mode, voice, face, status.
    private func apply(_ out: VoiceGrabBrain.Out, fromCamera: Bool) {
        if fromCamera {
            let now = Self.now()
            if out.approach && now - lastApproach > 2000, isBluetoothConnected {
                lastApproach = now
                bluetooth.sendData(payload: "a3000\n") // firmware: reach 4 cm instead of stopping at 15 cm
            }
            drive(out.grip ? 0 : out.left, out.grip ? 0 : out.right)
        }
        if isBluetoothConnected {
            if out.grip { bluetooth.sendData(payload: "g1\n") }
            if out.release { bluetooth.sendData(payload: "g0\n") }
        }
        setStatus(out.status)
        if out.say != nil || out.face != nil {
            let line = out.say, obj = out.sayObject, f = out.face
            DispatchQueue.main.async {
                self.setFace(f, talking: line != nil)
                if let line = line { self.say(self.phrase(line, obj)) }
            }
        }
    }

    /// The brain's wheel convention; "⇄" switches (shared with the plain drive words). The
    /// motor channels are cross-wired, so the template's raw sendControl gets a negate-and-swap.
    private func drive(_ left: Float, _ right: Float) {
        if reversed {
            DispatchQueue.main.async {
                let scale = GameController.shared.selectedSpeedMode.rawValue
                self.bluetooth.sendData(payload: "c\(Int((left * scale).rounded())),\(Int((right * scale).rounded()))\n")
            }
        } else {
            DispatchQueue.main.async {
                self.sendControl(control: Control(left: -right, right: -left))
            }
        }
    }

    /// Raw wheel write for the timed manual moves (they resend; sendControl de-duplicates).
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

    // MARK: - Voice AI (Xiaozhi)

    /// The Xiaozhi session: tts speech, photo/fetch tools and the "what should I do?" prompts.
    private func wireAiSession() {
        aiSession.onStateChange = { [weak self] state in
            self?.aiReady = (state == .ready)
        }
        aiSession.onTtsSentence = { [weak self] text in
            self?.showBubble("🤖 " + text) // the opus frames of its tts are the actual voice
        }
        aiSession.onTtsState = { [weak self] speaking in
            guard let self = self else { return }
            if speaking {
                // Never listen to the robot's own voice.
                self.speaking = true
                self.synth.stopSpeaking(at: .immediate)
                self.endRecognition()
            } else {
                self.finishAiSpeaking()
            }
        }
        aiSession.onBinaryAudio = { [weak self] data, sampleRate in
            self?.enqueuePlayback(data, sampleRate: sampleRate)
        }
        aiSession.toolHandler = { [weak self] name, args in
            self?.handleAiTool(name, args) ?? false
        }
        aiSession.photoHandler = { [weak self] question, done in
            guard let self = self else {
                done(nil, "screen closed")
                return
            }
            self.requestPhoto(question, done: done)
        }
        aiSession.fetchHandler = { [weak self] object, done in
            guard let self = self else {
                done(nil, "screen closed")
                return
            }
            self.startAiFetch(object, done: done)
        }
    }

    /// The assistant's tools/call lands here: the same tool→VoiceCommand map as the Voice
    /// Control page (VoiceCommand.from(tool:)), then this page's execute path — so the AI's
    /// commands behave identically on both pages ("stop" halts the mission here too).
    private func handleAiTool(_ name: String, _ args: [String: Any]) -> Bool {
        guard let command = VoiceCommand.from(tool: name, args: args) else { return false }
        executeAiCommand(command)
        return true
    }

    /// Runs one AI command with the Voice Control page's semantics, on this page's machinery:
    /// lights, indicators, sonar, gripper and the choreographed tricks, not just drive moves.
    /// Silent like the Voice Control page — the assistant's own tts reply is the narration.
    private func executeAiCommand(_ command: VoiceCommand) {
        switch command.action {
        case .faster, .slower:
            cycleSpeed(command.action == .faster)
        case .chat, .unknown, .stop:
            setAutoMode(false) // halts the mission and the wheels (stopManualMove runs inside)
        case .grip:
            creepAndGrip(speak: false) // the AI's grab: creep in, grip at 5 cm
        case .release:
            manualGrip(false)
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
                    self.say(answer)
                }
            })
    }

    /**
     Always-on watcher: when the camera sees an object and no mission runs, the Voice AI
     asks the user what to do with it ("What should I do with the cup?"). The spoken reply
     comes back through the local recognizer, so "pick it up" starts the fetch right away.
     Rate-limited so it never nags: at most one question per 20 s, and the same object
     only once a minute.
     */
    private func maybeAskAboutSighting(_ seen: Detector.Recognition?) {
        guard let cls = seen?.getTitle(), !cls.isEmpty, cls != "person" else { return }
        guard aiReady, listening, !speaking else { return }
        brainLock.lock()
        defer { brainLock.unlock() }
        guard brain.mode == .idle else { return }
        brain.rememberObject(cls) // "pick it up" resolves to this object
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

    /// The AI's fetch tool: start the local brain's fetch mission ("pick up the …").
    private func startAiFetch(_ object: String, done: @escaping (String?, String?) -> Void) {
        guard viewIfLoaded?.window != nil else {
            done(nil, "screen closed")
            return
        }
        let target = object.trimmingCharacters(in: .whitespaces)
        guard !target.isEmpty else {
            done(nil, "No object named")
            return
        }
        guard isBluetoothConnected else {
            done(nil, "Robot not connected")
            return
        }
        var started = false
        var busy = false
        brainLock.lock()
        busy = brain.mode != .idle
        if !busy {
            objConfirmStreak = 0 // a new mission starts the three-frame count over
            brain.command(VoiceGrabParser.parse("pick up the " + target), now: Self.now())
            started = brain.mode != .idle
        }
        brainLock.unlock()
        if !started {
            done(nil, busy ? "The robot is already busy" : "I didn't get that")
            return
        }
        if !autoMode { setAutoMode(true) } // the camera loop runs the brain
        done("Fetching the " + target + ".", nil) // the assistant speaks the ack
    }

    /// The AI stopped speaking: re-arm the listener once its audio has drained.
    private func finishAiSpeaking() {
        if playbackBusy { return } // the drain callback finishes the turn
        speaking = false
        face.setFace(lastEmotion, talking: false) // the mouth stops with the voice
        if listening {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in self?.startListening() }
        }
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
            if self.speaking { self.finishAiSpeaking() }
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

    // MARK: - Phrases

    private func phrase(_ s: VoiceGrabBrain.Say, _ object: String) -> String {
        let thing = vietnamese ? Self.viName(object) : object
        let vi = vietnamese
        switch s {
        case .okGet: return vi ? "Được, mình đi lấy \(thing) nhé." : "Okay, I'll get the \(thing)."
        case .seeIt: return vi ? "Mình thấy \(thing) rồi!" : "I see the \(thing)!"
        case .gotIt: return vi ? "Lấy được rồi!" : "Got it!"
        case .whereTo: return vi ? "Mình mang nó đi đâu?" : "Where should I take it?"
        case .coming: return vi ? "Mình đi ngay!" : "On my way!"
        case .hereYouAre: return vi ? "Của bạn đây!" : "Here you are!"
        case .arrived: return vi ? "Đến nơi rồi." : "Here we are."
        case .askNext: return vi ? "Bạn cần mình làm gì tiếp?" : "What should I do next?"
        case .thereYouGo: return vi ? "Xong rồi nhé." : "There you go."
        case .cantFind: return vi ? "Mình không tìm thấy \(thing)." : "I can't find the \(thing)."
        case .following: return vi ? "Mình đi theo bạn đây." : "I'm following you."
        case .whichObject: return vi ? "Bạn muốn mình lấy vật gì?" : "Which object should I get?"
        case .stopped: return vi ? "Mình dừng rồi." : "Okay, I stopped."
        case .notHolding: return vi ? "Mình đâu có cầm gì." : "I'm not holding anything."
        case .hello: return vi ? "Xin chào! Bạn cần mình lấy gì?" : "Hello! What can I get for you?"
        case .thanks: return vi ? "Không có gì!" : "You're welcome!"
        case .didntGet: return vi ? "Xin lỗi, mình chưa hiểu. Thử nói: lấy cái cốc." : "Sorry, I didn't get that. Try: pick up the cup."
        case .cantGrab: return vi ? "Mình không kẹp được." : "I can't grab it."
        }
    }

    private static func viName(_ label: String) -> String {
        let names = ["cup": "cái cốc", "bottle": "cái chai", "sports ball": "quả bóng", "apple": "quả táo",
                     "orange": "quả cam", "banana": "quả chuối", "teddy bear": "gấu bông", "book": "quyển sách",
                     "cell phone": "điện thoại", "remote": "cái điều khiển", "mouse": "con chuột",
                     "scissors": "cái kéo", "bowl": "cái bát", "spoon": "cái thìa",
                     "dining table": "cái bàn", "chair": "cái ghế", "bed": "cái giường",
                     "couch": "ghế sofa", "tv": "cái tivi", "laptop": "máy tính",
                     "vase": "lọ hoa", "clock": "đồng hồ"]
        return names[label] ?? label
    }
}
