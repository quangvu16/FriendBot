//
//  TargetTrackingController.swift
//  OpenBot
//
//  Target Tracking — port of the interaction of longpth/Android-Object-Tracking-OpenCV:
//  drag a box around the object on the camera preview, lock it, and the car follows the box's
//  centre with the same three-zone steering as the follow modes. That project used OpenCV's
//  TrackerMedianFlow and — importantly — kept driving when tracker.update() failed; here
//  tracking is Apple's Vision VNTrackObjectRequest and a lost target stops the car until
//  you re-lock.
//

import UIKit
import AVFoundation
import Vision
import Speech

final class TargetTrackingController: VisionModeController {

    private static let minDragSize: CGFloat = 24

    private var trackRequest: VNTrackObjectRequest?
    /// Buffer-space rect waiting to be locked on the next camera frame.
    private var pendingLock: CGRect?
    private var dragRect = CGRect.zero
    private var dragging = false
    private var hasDragRect = false
    private var lockButton: UIButton?

    override func guideId() -> String? {
        // The L4 project ships no tutorial for this mode; the help button shows the usage hint.
        nil
    }

    override func helpFallback() -> String { Strings.trackHint }

    override func setupModeControls() {
        overlay.guideFractions = [1.0 / 3.0, 2.0 / 3.0]
        lockButton = addActionButton(title: Strings.trackLock) { [weak self] in
            self?.onLockButton()
        }
        setStatus(Strings.trackStatusIdle)
        let pan = UIPanGestureRecognizer(target: self, action: #selector(onPan(_:)))
        overlay.addGestureRecognizer(pan)
    }

    @objc private func onPan(_ gesture: UIPanGestureRecognizer) {
        let point = gesture.location(in: overlay)
        switch gesture.state {
        case .began:
            dragRect = CGRect(origin: overlay.viewToFrame(point), size: .zero)
            dragging = true
            hasDragRect = false
        case .changed:
            guard dragging else { return }
            let framePoint = overlay.viewToFrame(point)
            dragRect = CGRect(
                x: min(dragRect.minX, framePoint.x),
                y: min(dragRect.minY, framePoint.y),
                width: abs(framePoint.x - dragRect.minX),
                height: abs(framePoint.y - dragRect.minY))
            hasDragRect = true
        case .ended, .cancelled, .failed:
            dragging = false
            if hasDragRect && trackRequest == nil && pendingLock == nil {
                showBoxes([
                    VisionOverlayView.Box(rect: dragRect, label: Strings.trackLock, color: .yellow, width: 2)
                ])
                setStatus(Strings.trackHint)
            }
        default:
            break
        }
    }

    private func onLockButton() {
        if trackRequest != nil || pendingLock != nil {
            trackRequest = nil
            pendingLock = nil
            DispatchQueue.main.async {
                self.lockButton?.setTitle(Strings.trackLock, for: .normal)
            }
            showBoxes([])
            setStatus(Strings.trackStatusIdle)
            sendControl(Control())
            return
        }
        guard hasDragRect else {
            setStatus(Strings.trackStatusIdle)
            return
        }
        pendingLock = dragRect
        setStatus(Strings.trackStatusTracking)
    }

    override func processVisionFrame(_ pixelBuffer: CVPixelBuffer) {
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)

        if let roi = pendingLock {
            pendingLock = nil
            // Vision boxes are normalized with the origin at the bottom-left.
            let normalized = CGRect(
                x: roi.minX / CGFloat(width),
                y: 1 - roi.maxY / CGFloat(height),
                width: roi.width / CGFloat(width),
                height: roi.height / CGFloat(height))
            let observation = VNDetectedObjectObservation(boundingBox: normalized)
            let request = VNTrackObjectRequest(detectedObjectObservation: observation)
            request.trackingLevel = .accurate
            trackRequest = request
            hasDragRect = false
            DispatchQueue.main.async {
                self.lockButton?.setTitle(Strings.trackUnlock, for: .normal)
            }
        }

        guard let request = trackRequest else { return }

        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, options: [:])
        do {
            try handler.perform([request])
        } catch {
            loseTarget()
            return
        }

        guard let result = request.results?.first as? VNDetectedObjectObservation,
              result.confidence > 0.2 else {
            // The reference project kept driving on tracker loss; stopping is safe.
            loseTarget()
            return
        }

        // Feed the new observation into a fresh request for the next frame (sequential tracking).
        let next = VNTrackObjectRequest(detectedObjectObservation: result)
        next.trackingLevel = .accurate
        trackRequest = next

        let b = result.boundingBox
        let targetPixels = CGRect(
            x: b.minX * CGFloat(width),
            y: (1 - b.maxY) * CGFloat(height),
            width: b.width * CGFloat(width),
            height: b.height * CGFloat(height))

        showBoxes([
            VisionOverlayView.Box(
                rect: targetPixels, label: Strings.tileTargetTracking, color: .green, width: 3)
        ])

        let viewPoint = overlay.frameToView(
            CGPoint(x: targetPixels.midX, y: targetPixels.midY))
        let fitted = overlay.fittedFrameRect
        let third = fitted.width / 3
        if viewPoint.x < fitted.minX + third {
            setStatus("◀ " + Strings.trackStatusTracking)
            sendControl(Control(left: -1, right: 1))
        } else if viewPoint.x > fitted.maxX - third {
            setStatus("▶ " + Strings.trackStatusTracking)
            sendControl(Control(left: 1, right: -1))
        } else {
            setStatus("▲ " + Strings.trackStatusTracking)
            sendControl(Control(left: 1, right: 1))
        }
    }

    private func loseTarget() {
        trackRequest = nil
        setStatus(Strings.trackStatusLost)
        showBoxes([])
        sendControl(Control())
        DispatchQueue.main.async {
            self.lockButton?.setTitle(Strings.trackLock, for: .normal)
        }
    }
}


// MARK: - Grab Object (AI model + front sonar + gripper)

/// Same decisions as Android's GrabSteering (tested there in a simulation: 48/48 grabs):
/// search → turn to the object → drive, slower as the sonar distance shrinks → approach mode
/// under 35 cm (the camera loses the object right in front of the claw; the sonar still sees
/// it) → stop and grip at the chosen distance.
final class GrabSteering {
    enum State { case search, turn, approach, reaching, grip, holding, lost, arrived, aborted }

    struct Out {
        var left: Float = 0
        var right: Float = 0
        var grip = false
        var approach = false
        var state: State = .search
        var status = ""
    }

    static let centred: Float = 0.22
    static let gripCentred: Float = 0.4
    static let turnMin: Float = 0.3
    static let turnGain: Float = 0.2
    static let steerGain: Float = 0.25
    static let slowest: Float = 0.22
    static let fastest: Float = 0.55
    static let finalCm: Float = 35
    static let sonarMaxCm: Float = 250
    static let blindMs: Double = 1800
    static let flickerMs: Double = 600
    static let searchTurnMs: Double = 330
    static let searchPauseMs: Double = 500
    static let searchSpeed: Float = 0.35
    /// Give up on the search after this long ("the bottle isn't found in 15 seconds").
    static let searchGiveUpMs: Double = 15000
    static let gripWaitMs: Double = 1000
    /// Without a claw report, wait this long for one before trusting the blind outcome.
    static let gripWaitMaxMs: Double = 2500
    /// Reading closer than this during the grab sequence is unsafe — stop and give up.
    static let tooCloseCm: Float = 2
    static let noSonarGripHeight: Float = 0.7

    let object: String
    var grabCm: Float
    /// "go to the cup": drive to `grabCm` and stop there — never signal grip or reach-mode.
    let approachOnly: Bool
    private(set) var state: State = .search
    private var lastSeen: Double = -1e9
    private var lastOffset: Float = 0
    private var searchSince: Double = -1
    private var gripAt: Double = 0
    private var gripTries = 0

    init(object: String, grabCm: Float, approachOnly: Bool = false) {
        self.object = object
        self.grabCm = grabCm
        self.approachOnly = approachOnly
    }

    func reset() {
        state = .search
        lastSeen = -1e9
        searchSince = -1
        gripTries = 0
    }

    /// now in ms; offset -1 (left) … +1 (right); height = box height / picture height; sonar ≤ 0 = unknown.
    /// gripperState is the claw's own answer to its last report: 1 closed, 0 open, -1 unknown yet.
    func step(now: Double, seen: Bool, offset: Float, height: Float, sonarCm: Float, gripperState: Int = -1) -> Out {
        var o = Out()
        let sonarOk = sonarCm > 0 && sonarCm < Self.sonarMaxCm
        if seen {
            lastSeen = now
            lastOffset = offset
            searchSince = -1
        }
        func hold(_ status: String) -> Out {
            var h = Out()
            h.state = state
            h.status = status
            h.grip = o.grip
            return h
        }
        if state == .holding || state == .lost || state == .arrived || state == .aborted {
            switch state {
            case .holding: return hold("Holding the \(object)")
            case .arrived: return hold("At the \(object)")
            case .aborted: return hold("Stopped near the \(object)")
            default: return hold("Cannot find the \(object)")
            }
        }
        if state == .grip {
            let waited = now - gripAt
            if waited < Self.gripWaitMs { return hold("Gripping the \(object)") }
            if gripperState == 0 {
                // The claw is still open after closing — it missed. One more try, then give up.
                if gripTries < 1 {
                    gripTries = 1
                    gripAt = now
                    o.grip = true
                    return hold("Retrying the grip on the \(object)")
                }
                state = .aborted
                return hold("Missed the \(object)")
            }
            if gripperState >= 0 || waited >= Self.gripWaitMaxMs {
                state = .holding
                return hold("Holding the \(object)")
            }
            return hold("Gripping the \(object)…")
        }
        // Too close for a clean grab (the 2 cm fail-safe): stop, the claw never gets a shot from here.
        if sonarOk && sonarCm < Self.tooCloseCm {
            state = .aborted
            return hold("Too close to the \(object)")
        }
        let recentlySeen = now - lastSeen < Self.blindMs
        let centredNow = seen ? abs(offset) < Self.gripCentred : (recentlySeen && abs(lastOffset) < Self.gripCentred)
        if sonarOk && sonarCm <= grabCm && centredNow {
            if approachOnly {
                state = .arrived
                return hold(String(format: "At the %@ (%.0f cm)", object, sonarCm))
            }
            state = .grip
            gripAt = now
            o.grip = true
            return hold(String(format: "Gripping the %@ (%.0f cm)", object, sonarCm))
        }
        if !sonarOk && seen && height >= Self.noSonarGripHeight && abs(offset) < Self.gripCentred {
            if approachOnly {
                state = .arrived
                return hold("At the \(object)")
            }
            state = .grip
            gripAt = now
            o.grip = true
            return hold("Gripping the \(object) (no sonar)")
        }
        if seen {
            if abs(offset) > Self.centred {
                state = .turn
                let s = Self.turnMin + Self.turnGain * min(1, abs(offset))
                let dir: Float = offset < 0 ? -1 : 1
                o.left = dir * s
                o.right = -dir * s
                o.state = state
                o.status = (dir < 0 ? "◀ " : "▶ ") + "Turning to the \(object)"
                return o
            }
            let v: Float = sonarOk
                ? min(Self.fastest, max(Self.slowest, Self.slowest + (sonarCm - grabCm) / 120))
                : (height > 0.5 ? Self.slowest : 0.45)
            o.left = v + Self.steerGain * offset
            o.right = v - Self.steerGain * offset
            let fin = sonarOk && sonarCm <= Self.finalCm && !approachOnly
            o.approach = fin
            state = fin ? .reaching : .approach
            o.state = state
            o.status = sonarOk
                ? String(format: "▲ %@ %@ · %.0f cm", fin ? "Reaching" : "Driving to", object, sonarCm)
                : "▲ Driving to the \(object)"
            return o
        }
        if recentlySeen && abs(lastOffset) < Self.gripCentred && sonarOk && sonarCm <= Self.finalCm {
            state = .reaching
            o.left = Self.slowest
            o.right = Self.slowest
            o.approach = !approachOnly
            o.state = state
            o.status = String(format: "▲ Reaching the %@ by sonar · %.0f cm", object, sonarCm)
            return o
        }
        if now - lastSeen < Self.flickerMs { return hold("Looking for the \(object)") }
        state = .search
        if searchSince < 0 { searchSince = now }
        if now - searchSince > Self.searchGiveUpMs {
            state = .lost
            return hold("Cannot find the \(object)")
        }
        let phase = (now - searchSince).truncatingRemainder(dividingBy: Self.searchTurnMs + Self.searchPauseMs)
        if phase < Self.searchTurnMs {
            o.left = Self.searchSpeed
            o.right = -Self.searchSpeed
        }
        o.state = state
        o.status = "Searching for the \(object)"
        return o
    }
}

/// Grab Object: the phone's AI model (the Object Tracking detector, 80 everyday objects) finds
/// the chosen object, the robot steers to it, the front sonar measures the real distance, and at
/// the grip distance the robot stops and closes the gripper.
final class GrabObjectController: VisionModeController {

    private static let grabbable = ["cup", "bottle", "sports ball", "apple", "orange", "banana",
                                    "teddy bear", "mouse", "remote", "cell phone", "book", "scissors",
                                    "toothbrush", "wine glass", "bowl", "donut", "carrot", "spoon",
                                    "clock", "vase"]
    private let defaults = UserDefaults.standard
    private var detector: Detector?
    private var target = "cup"
    private var grabCm: Float = 8
    private var reversed = false
    private var steering: GrabSteering?
    private var lastApproach: Double = 0
    private var announced = false
    private var busy = false
    private let inferenceQueue = DispatchQueue(label: "friendbot.grab.inference")
    private var objectButton: UIButton?
    private let bluetooth = bluetoothDataController.shared

    override func helpFallback() -> String { L10n.string("grab_hint") }

    override func setupModeControls() {
        target = defaults.string(forKey: "grab_object") ?? "cup"
        grabCm = Float(defaults.object(forKey: "grab_cm") as? Int ?? 8)
        reversed = defaults.bool(forKey: "grab_reverse")
        overlay.guideFractions = [0.39, 0.61]
        objectButton = addActionButton(title: "🎯 \(target)") { [weak self] in self?.pickObject() }
        addStepper(label: L10n.string("grab_distance"), min: 4, max: 20, step: 1, initial: Int(grabCm)) { [weak self] v in
            self?.grabCm = Float(v)
            self?.steering?.grabCm = Float(v)
            self?.defaults.set(v, forKey: "grab_cm")
        }
        addActionButton(title: "✊") { [weak self] in self?.grip(true) }
        addActionButton(title: "✋") { [weak self] in self?.grip(false) }
        addActionButton(title: "⇄") { [weak self] in
            guard let self = self else { return }
            self.reversed.toggle()
            self.defaults.set(self.reversed, forKey: "grab_reverse")
            self.setStatus(L10n.string(self.reversed ? "grab_flip_on" : "grab_flip_off"))
        }
        setStatus(String(format: L10n.string("grab_idle"), target))
    }

    private func pickObject() {
        let sheet = UIAlertController(title: L10n.string("grab_object"), message: nil, preferredStyle: .actionSheet)
        for name in Self.grabbable {
            sheet.addAction(UIAlertAction(title: name, style: .default) { [weak self] _ in
                guard let self = self else { return }
                self.target = name
                self.defaults.set(name, forKey: "grab_object")
                self.objectButton?.setTitle("🎯 \(name)", for: .normal)
                if self.autoMode { self.setAutoMode(false) }
                self.setStatus(String(format: L10n.string("grab_idle"), name))
            })
        }
        sheet.addAction(UIAlertAction(title: L10n.string("voice_cancel"), style: .cancel))
        if let pop = sheet.popoverPresentationController, let b = objectButton {
            pop.sourceView = b
            pop.sourceRect = b.bounds
        }
        present(sheet, animated: true)
    }

    private func grip(_ close: Bool) {
        guard isBluetoothConnected else { return setStatus(L10n.string("voice_not_connected")) }
        bluetooth.sendData(payload: close ? "g1\n" : "g0\n")
        if !close {
            steering?.reset()
            announced = false
        }
    }

    override func setAutoMode(_ enabled: Bool) {
        if enabled {
            steering = GrabSteering(object: target, grabCm: grabCm)
            announced = false
            lastApproach = 0
            if isBluetoothConnected {
                bluetooth.sendData(payload: "g0\n")    // open, ready to grab
                bluetooth.sendData(payload: "s100\n")  // live distance: the sonar decides when to grip
            }
        } else if isBluetoothConnected {
            bluetooth.sendData(payload: "a0\n")
            bluetooth.sendData(payload: "s0\n")
        }
        super.setAutoMode(enabled)
        if enabled { setStatus(String(format: L10n.string("grab_searching"), target)) }
    }

    override func viewDidDisappear(_ animated: Bool) {
        if isBluetoothConnected {
            bluetooth.sendData(payload: "a0\n")
            bluetooth.sendData(payload: "s0\n")
        }
        super.viewDidDisappear(animated)
    }

    /// Camera queue: run the detector off it (one frame at a time), then decide.
    override func processVisionFrame(_ pixelBuffer: CVPixelBuffer) {
        guard !busy else { return }
        busy = true
        let front = isFrontCamera()
        inferenceQueue.async { [weak self] in
            defer { self?.busy = false }
            guard let self = self, self.autoMode, let steering = self.steering else { return }
            if self.detector == nil { self.createDetector() }
            guard let detector = self.detector else {
                self.setStatus(L10n.string("grab_no_model"))
                self.sendControl(Control())
                return
            }
            detector.setSelectedClass(newClass: self.target)
            let w = CGFloat(CVPixelBufferGetWidth(pixelBuffer))
            let h = CGFloat(CVPixelBufferGetHeight(pixelBuffer))
            let results = detector.recognizeImage(pixelBuffer: pixelBuffer, detectionType: "single")
            let best = results
                .filter { $0.getTitle() == self.target && $0.getConfidence() >= 0.5 }
                .max { $0.getConfidence() < $1.getConfidence() }
            var seen = false
            var offset: Float = 0
            var height: Float = 0
            var boxes: [VisionOverlayView.Box] = []
            if let best = best, w > 0, h > 0 {
                let box = best.getLocation()
                offset = Float((box.midX / w) * 2 - 1) * (front ? -1 : 1)
                height = Float(box.height / h)
                seen = true
                boxes.append(VisionOverlayView.Box(
                    rect: box, label: String(format: "%@ %.0f%%", self.target, best.getConfidence() * 100),
                    color: .green, width: 4))
            }
            self.showBoxes(boxes)
            let now = Date().timeIntervalSince1970 * 1000
            let sonar = self.bluetooth.sonarDistanceCm
            let out = steering.step(now: now, seen: seen, offset: offset, height: height, sonarCm: sonar,
                                        gripperState: self.bluetooth.gripperState)
            if out.approach && now - self.lastApproach > 2000, isBluetoothConnected {
                self.lastApproach = now
                self.bluetooth.sendData(payload: "a3000\n")  // firmware: reach 4 cm, not 15 cm
            }
            if out.grip {
                self.drive(0, 0)
                if isBluetoothConnected { self.bluetooth.sendData(payload: "g1\n") }
            } else {
                self.drive(out.left, out.right)
            }
            let distance = sonar > 0 ? String(format: "%.0f cm", sonar) : "—"
            self.setStatus(out.status + "  ·  " + String(format: L10n.string("grab_sonar"), distance))
            if (out.state == .holding || out.state == .lost) && !self.announced {
                self.announced = true
                let holding = out.state == .holding
                let name = self.target
                DispatchQueue.main.async {
                    self.setAutoMode(false)
                    self.setStatus(String(format: L10n.string(holding ? "grab_holding" : "grab_lost"), name))
                }
            }
        }
    }

    /// The vision pages' convention on this robot (sendControl: reversed and swapped for its
    /// wiring); "⇄" switches to the direct one for a robot that drives away from the object.
    private func drive(_ left: Float, _ right: Float) {
        if reversed {
            DispatchQueue.main.async {
                let scale = GameController.shared.selectedSpeedMode.rawValue
                self.bluetooth.sendData(payload: "c\(Int((left * scale).rounded())),\(Int((right * scale).rounded()))\n")
            }
        } else {
            sendControl(Control(left: left, right: right))
        }
    }

    private func createDetector() {
        // Use the Model Management catalog (documents config.json when present,
        // otherwise the bundled one); only models whose file exists can load.
        var items = Common.loadAllModelItems().filter { Common.isModelItemRunnable($0) }
        if items.isEmpty {
            items = Common.loadAllModelItemsFromBundle()
        }
        guard let item = items.first(where: { $0.type == TYPE.DETECTOR.rawValue }) else { return }
        detector = (try? Detector.create(model: Model.fromModelItem(item: item), device: RuntimeDevice.CPU, numThreads: 2)) as? Detector
    }
}


// MARK: - Voice Grab (talk to the robot: pick up, bring, put down, follow)

/// What the user said → one robot request, in English or Vietnamese (same rules as Android's
/// VoiceGrabParser, tested there). The object to recognize always comes from the words, never
/// from the page's object setting. Four fetch commands, each ending with "ask the new command":
///
///     1. "pick up the cup"                → pick cup             → carry to the person
///     2. "bring the cup to the table"     → bring cup → table    → carry to the new place
///     3. "find the ball"                  → find sports ball     → carry to the old place
///     4. "put the cup next to the bottle" → nextTo cup → bottle  → carry next to the bottle
///
/// Plus "put it down", "follow me", "stop", "hello", "thank you".
enum VoiceGrabParser {
    enum Intent { case pick, bring, nextTo, putDown, follow, find, approach, see, check, stop, hello, thanks, unknown }

    /// Where the robot carries the object after the claw has it.
    enum Dest { case person, home, place, nextTo }

    struct Request {
        let intent: Intent
        /// What to pick (detector label, "cup", "sports ball", …) or nil (use the last one).
        let object: String?
        /// Landmark label for `.place` / `.nextTo` ("dining table", "bottle").
        let dest: String?
        /// Where to carry it after gripping (nil for non-fetch commands).
        let destKind: Dest?
        /// Detected colour to insist on ("red", "blue", …) or nil for any colour.
        let color: String?
        /// How many objects to collect — above 1 only when the words ask for a count.
        let count: Int

        init(intent: Intent, object: String?, dest: String?, destKind: Dest?,
             color: String? = nil, count: Int = 1) {
            self.intent = intent
            self.object = object
            self.dest = dest
            self.destKind = destKind
            self.color = color
            self.count = count
        }
    }

    private static let objects: [(String, String)] = [
        ("teddy bear", "teddy bear"), ("gau bong", "teddy bear"), ("con gau", "teddy bear"),
        ("sports ball", "sports ball"), ("tennis ball", "sports ball"), ("qua bong", "sports ball"),
        ("trai banh", "sports ball"), ("qua banh", "sports ball"), ("ball", "sports ball"),
        ("bong", "sports ball"), ("banh", "sports ball"),
        ("wine glass", "wine glass"), ("ly ruou", "wine glass"),
        ("cell phone", "cell phone"), ("mobile phone", "cell phone"), ("dien thoai", "cell phone"), ("phone", "cell phone"),
        ("remote", "remote"), ("dieu khien", "remote"),
        ("toothbrush", "toothbrush"), ("ban chai", "toothbrush"),
        ("bottle", "bottle"), ("chai", "bottle"),
        ("cup", "cup"), ("mug", "cup"), ("coc", "cup"), ("ly", "cup"), ("tach", "cup"),
        ("apple", "apple"), ("tao", "apple"),
        ("orange", "orange"), ("qua cam", "orange"),
        ("banana", "banana"), ("chuoi", "banana"),
        ("book", "book"), ("sach", "book"),
        ("mouse", "mouse"), ("con chuot", "mouse"), ("chuot", "mouse"),
        ("scissors", "scissors"), ("cai keo", "scissors"),
        ("bowl", "bowl"), ("cai bat", "bowl"), ("cai to", "bowl"),
        ("spoon", "spoon"), ("cai thia", "spoon"), ("muong", "spoon"), ("thia", "spoon"),
        ("carrot", "carrot"), ("ca rot", "carrot"),
        ("donut", "donut"), ("doughnut", "donut"),
        ("vase", "vase"), ("lo hoa", "vase"), ("binh hoa", "vase"),
        ("clock", "clock"), ("dong ho", "clock"),
        // Places (destinations for "bring the cup to the …"): same detector vocabulary.
        ("dining table", "dining table"), ("table", "dining table"), ("ban", "dining table"),
        ("chair", "chair"), ("ghe", "chair"),
        ("couch", "couch"), ("sofa", "couch"), ("giuong", "bed"),
        ("bed", "bed"), ("tv", "tv"), ("tivi", "tv"),
        ("laptop", "laptop"), ("refrigerator", "refrigerator"), ("tu lanh", "refrigerator"),
        ("sink", "sink"), ("toilet", "toilet"), ("bench", "bench"),
    ]
    private static let stopW = ["stop", "halt", "freeze", "dung lai", "dung", "thoi", "ngung"]
    private static let putW = ["put it down", "put down", "drop", "let go", "release", "give it", "give me it",
                               "open the claw", "open your hand", "tha ra", "tha xuong", "bo xuong", "dat xuong",
                               "dua cho", "buong", "tha"]
    private static let bringW = ["bring", "come back", "come here", "come to me", "go back", "return", "mang", "dem",
                                 "lai day", "quay lai", "ve day", "den day"]
    private static let pickW = ["pick up", "pick", "grab", "get", "fetch", "take", "hold", "catch", "lay",
                                "collect", "gather", "nhat", "cam", "gap", "bat", "giu", "thu thap", "gom"]
    private static let followW = ["follow", "di theo", "theo toi", "theo minh"]
    private static let findW = ["find", "look for", "where is", "where's", "search", "tim", "o dau"]
    private static let helloW = ["hello", "hi robot", "hey robot", "xin chao", "chao"]
    private static let thanksW = ["thank", "good job", "well done", "cam on", "gioi lam", "tot lam"]
    /// "put the cup next to the bottle" / "đặt cái cốc bên cạnh cái chai".
    private static let nextToW = ["next to", "beside", "next", "near", "close to", "ben canh", "ke ben", "gan", "sat", "ap"]
    /// "bring the cup to the table" / "mang cái cốc đến cái bàn".
    private static let toW = ["to", "onto", "on", "at", "den", "len", "vao", "sang"]
    /// "bring me the cup", "bring it here": the person is the destination.
    private static let meW = ["me", "here", "us", "my", "toi", "minh", "day", "cho toi"]
    /// "go to the cup" / "move toward the bottle but don't touch it" — get close, never grip.
    private static let approachW = ["approach", "go to", "move toward", "move towards", "move to",
                                    "move closer", "come close", "don't touch", "do not touch",
                                    "den gan", "lai gan", "tien lai gan", "khong cham"]
    /// "what do you see?" — read out what the camera currently sees.
    private static let seeW = ["what do you see", "what can you see", "what do you see now",
                               "what's there", "whats there", "ban thay gi", "nhin thay gi", "thay gi"]
    /// "did you pick it up?" / "check the gripper" — answer from the claw's own report.
    private static let checkW = ["did you pick", "did you grab", "did you get it", "are you holding",
                                 "are you carrying", "check if", "check whether", "check the grab",
                                 "check the gripper", "check the claw", "ban da cam", "kiem tra"]
    /// Spoken colour → canonical name; the longest phrases must come first ("xanh duong" before "xanh").
    private static let colors: [(String, String)] = [
        ("xanh duong", "blue"), ("xanh lam", "blue"), ("xanh la", "green"), ("mau do", "red"),
        ("red", "red"), ("do", "red"), ("blue", "blue"), ("green", "green"),
        ("yellow", "yellow"), ("vang", "yellow"), ("xanh", "blue"),
    ]
    /// A spoken count ("three objects", "hai cái") → how many times to repeat the fetch.
    private static let counts: [(String, Int)] = [
        ("one", 1), ("two", 2), ("three", 3), ("four", 4), ("five", 5), ("six", 6),
        ("seven", 7), ("eight", 8), ("nine", 9), ("ten", 10),
        ("mot", 1), ("hai", 2), ("ba", 3), ("bon", 4), ("nam", 5), ("sau", 6),
        ("bay", 7), ("tam", 8), ("chin", 9), ("muoi", 10),
    ]

    static func parse(_ said: String) -> Request {
        let raw = plain(said)
        let color = extractColor(raw)
        let count = extractCount(raw)
        func rq(_ intent: Intent, _ object: String?, _ dest: String?, _ destKind: Dest?) -> Request {
            Request(intent: intent, object: object, dest: dest, destKind: destKind, color: color, count: count)
        }
        let t = " " + raw + " "
        if has(t, stopW) { return rq(.stop, nil, nil, nil) }
        if has(t, seeW) { return rq(.see, nil, nil, nil) }
        if has(t, checkW) { return rq(.check, nil, nil, nil) }
        if has(t, followW) { return rq(.follow, nil, nil, nil) }
        let objs = findObjects(t)
        let nextTo = has(t, nextToW)
        let to = has(t, toW)
        // Two objects in the words: the first is what to pick, the second names the destination.
        if objs.count >= 2 && nextTo {
            return rq(.nextTo, objs[0], objs[1], .nextTo)
        }
        if objs.count >= 2 && to {
            return rq(.bring, objs[0], objs[1], .place)
        }
        if has(t, approachW) { return rq(.approach, objs.first, nil, nil) }
        if has(t, putW) { return rq(.putDown, nil, nil, nil) }
        if objs.count == 1 {
            let o = objs[0]
            if to && !has(t, meW) {
                if has(t, pickW) {
                    // "pick up the bottle and put it at the box": the place is not a landmark we
                    // can see — carry it to the person instead of misreading the bottle as a place.
                    return rq(.pick, o, nil, .person)
                }
                // "bring it to the table": carry what is in the claw to that place.
                return rq(.bring, nil, o, .place)
            }
            if has(t, findW) { return rq(.find, o, nil, .home) }
            // "pick up the cup", "bring me the cup", "the cup!" → to the person.
            return rq(.pick, o, nil, .person)
        }
        // No object named: it is whatever the user last asked for (or is in the claw).
        if has(t, bringW) { return rq(.bring, nil, nil, .person) }
        if has(t, findW) { return rq(.find, nil, nil, .home) }
        if has(t, pickW) { return rq(.pick, nil, nil, .person) }
        if has(t, thanksW) { return rq(.thanks, nil, nil, nil) }
        if has(t, helloW) { return rq(.hello, nil, nil, nil) }
        return rq(.unknown, nil, nil, nil)
    }

    /// The leftmost colour word in the words, folded to its canonical name ("red bottle" → red).
    private static func extractColor(_ raw: String) -> String? {
        let t = " " + raw + " "
        var bestAt = Int.max
        var best: String?
        for (word, name) in colors {
            let at = indexOf(t, word)
            if at >= 0 && at < bestAt {
                bestAt = at
                best = name
            }
        }
        return best
    }

    /// The first spoken number in the words ("collect three objects" → 3); 1 when none is said.
    private static func extractCount(_ raw: String) -> Int {
        let t = " " + raw + " "
        var bestAt = Int.max
        var best = 1
        for (word, n) in counts {
            let at = indexOf(t, word)
            if at >= 0 && at < bestAt {
                bestAt = at
                best = n
            }
        }
        for token in raw.split(separator: " ") where token.allSatisfy({ $0.isNumber }) {
            if let n = Int(token), n >= 1, n <= 20 { return n }
        }
        return best
    }

    /// Lower case, Vietnamese accents removed (đ → d), punctuation → spaces.
    static func plain(_ s: String) -> String {
        let folded = s.lowercased().folding(options: .diacriticInsensitive, locale: nil).replacingOccurrences(of: "đ", with: "d")
        let kept = folded.map { ($0.isLetter && $0.isASCII) || $0.isNumber || $0 == "'" ? $0 : " " }
        return String(kept).split(separator: " ").joined(separator: " ")
    }

    private static func has(_ text: String, _ words: [String]) -> Bool {
        words.contains { text.contains(" \($0) ") || text.contains(" \($0)s ") }
    }

    /// Every object mentioned, in the order the words say them ("cup … bottle").
    private static func findObjects(_ text: String) -> [String] {
        var found: [String] = []
        var rest = text
        while true {
            var bestAt = Int.max
            var bestWord: String?
            var bestLabel: String?
            for (w, label) in objects {
                let at = indexOf(" " + rest + " ", w)
                if at >= 0 && at < bestAt {
                    bestAt = at
                    bestWord = w
                    bestLabel = label
                }
            }
            guard let bw = bestWord, let bl = bestLabel else { return found }
            found.append(bl)
            // Cut the text after this mention so "cup … cup" lists both.
            guard let r = rest.range(of: bw) else { return found }
            rest = String(rest[r.upperBound...])
        }
    }

    private static func indexOf(_ text: String, _ word: String) -> Int {
        var at = Int.max
        for suffix in [" ", "s ", "es "] {
            if let r = text.range(of: " " + word + suffix) {
                at = min(at, text.distance(from: text.startIndex, to: r.lowerBound))
            }
        }
        return at == Int.max ? -1 : at
    }
}

/// The "act like a person" layer (same logic as Android's VoiceGrabBrain, tested there in a
/// simulation). The four fetch commands share one shape: GrabSteering finds, reaches and grips
/// the object ("move around to find object, object in the centre of the screen, sonar to stop,
/// gripper to pick"); then the robot carries it to the destination the words named and asks
/// "What should I do next?".
final class VoiceGrabBrain {
    enum Mode { case idle, pick, celebrate, goto, putDown, follow, hello }
    enum Face { case neutral, listening, thinking, happy, surprised, sad, confused }
    enum Say { case okGet, seeIt, gotIt, whereTo, coming, hereYouAre, arrived, askNext, thereYouGo,
               cantFind, following, stopped, notHolding, whichObject, hello, thanks, didntGet,
               cantGrab }

    struct Out {
        var left: Float = 0, right: Float = 0
        var grip = false, release = false, approach = false
        var say: Say?
        var sayObject = ""
        var face: Face?
        var status = ""
    }

    static var cmPerS: Double = 60
    static var degPerS: Double = 184
    static let personStopCm: Float = 35, followCm: Float = 60, homeCm: Double = 25
    /// Stop this far from the landmark the words named ("to the table").
    static let placeStopCm: Float = 20
    /// Stop this far from the other object ("next to the bottle"): room to put it down beside.
    static let nextToStopCm: Float = 35
    /// "Go to the cup" stops here — close, but the claw never touches it.
    static let approachStopCm: Float = 12
    /// The grab distance: creep until the sonar reads this (the 5 cm rule), then close the claw.
    static let grabCm: Float = 5
    static let wiggleMs: Double = 220, wiggles = 4, putDownWaitMs: Double = 700, backOffMs: Double = 450
    /// While the claw holds something, the middle sonar sees that object: ignore readings this close.
    static let bringGiveUpMs: Double = 30000, heldObjectCm: Float = 15, personNearHeight: Float = 0.75
    static let lookAroundMs: Double = 14000
    /// After arriving: pause this long, then ask for the new command.
    static let askDelayMs: Double = 1200

    private(set) var mode: Mode = .idle
    private(set) var object = ""
    /// The landmark the words named ("dining table", "bottle"), for `wantsDest`.
    private(set) var destLabel = ""
    private(set) var holding = false
    private var destKind: VoiceGrabParser.Dest = .person
    private var grab: GrabSteering?
    private var destSteering: GrabSteering?
    private var modeSince: Double = 0
    private var lastStep: Double = -1
    private var lastLeft: Float = 0, lastRight: Float = 0
    private var saidSeeIt = false
    private var lookAroundSince: Double = -1
    private var arrivedAt: Double = 0
    private var x = 0.0, y = 0.0, heading = 0.0
    private var homeX = 0.0, homeY = 0.0, homeHeading = 0.0
    private var pending: Out?
    /// "collect three objects": fetches still to do; back to 1 for a single command.
    private var collectRemaining = 1
    /// The colour the words asked for ("the red block"): nil = any colour. Set with each
    /// command so a fresh fetch without a colour never inherits the previous filter.
    private(set) var color: String?

    /// Detector labels this frame should look for (the object, a person and/or the destination).
    var wantsPerson: Bool { (mode == .goto && destKind == .person) || mode == .follow }
    /// True while carrying to the landmark the words named (place / nextTo).
    var wantsDest: Bool { mode == .goto && destSteering != nil }
    /// The landmark the words named ("dining table", "bottle").
    var dest: String { destLabel }

    func setHolding(_ h: Bool) { holding = h }

    /// Seeds the "it" memory without a spoken command: after the robot asks the user what to
    /// do with a seen object, "pick it up" resolves to that object.
    func rememberObject(_ name: String) {
        if !name.isEmpty { object = name }
    }

    private func setMode(_ m: Mode, _ now: Double) {
        mode = m
        modeSince = now
    }

    /// A voice request (main thread).
    func command(_ r: VoiceGrabParser.Request, now: Double) {
        var o = Out()
        color = r.color
        switch r.intent {
        case .stop:
            setMode(.idle, now); arrivedAt = 0; o.say = .stopped; o.face = .neutral
        case .pick, .bring, .find, .nextTo:
            if r.intent == .pick { collectRemaining = r.count }
            startFetch(r, now: now, o: &o)
        case .approach:
            startApproach(r, now: now, o: &o)
        case .see, .check:
            break // the page answers these directly (the detections list / the claw's own report)
        case .putDown:
            if !holding { o.say = .notHolding; o.face = .confused; o.release = true; break }
            setMode(.putDown, now); o.release = true; o.face = .happy
        case .follow:
            setMode(.follow, now); o.say = .following; o.face = .happy
        case .hello:
            setMode(.hello, now); o.say = .hello; o.face = .happy
        case .thanks:
            o.say = .thanks; o.face = .happy
        case .unknown:
            o.say = .didntGet; o.face = .confused
        }
        pending = o
    }

    /// The shared pick-then-carry shape of the four fetch commands.
    private func startFetch(_ r: VoiceGrabParser.Request, now: Double, o: inout Out) {
        let kind = r.destKind ?? .person
        let wanted = (r.object?.isEmpty == false) ? r.object! : object

        // Already in the claw: skip the pick phase and just carry it ("bring it to me",
        // "put the cup next to the bottle" while holding the cup).
        if holding && (r.object == nil || r.object!.isEmpty || r.object == object) {
            if (kind == .place || kind == .nextTo) && (r.dest == nil || r.dest!.isEmpty) {
                o.say = .whereTo; o.face = .confused
                return
            }
            beginCarry(kind, r.dest, now: now)
            o.say = .coming; o.face = .happy
            return
        }
        if holding { o.say = .whereTo; o.face = .confused; return } // hands full of something else
        if wanted.isEmpty { o.say = .whichObject; o.face = .confused; return }
        if (kind == .place || kind == .nextTo) && (r.dest == nil || r.dest!.isEmpty) {
            o.say = .whereTo; o.face = .confused
            return
        }
        object = wanted
        destKind = kind
        destLabel = r.dest ?? ""
        rememberHome()
        grab = GrabSteering(object: object, grabCm: Self.grabCm)
        destSteering = kind == .place ? GrabSteering(object: destLabel, grabCm: Self.placeStopCm)
            : kind == .nextTo ? GrabSteering(object: destLabel, grabCm: Self.nextToStopCm)
            : nil
        saidSeeIt = false
        arrivedAt = 0
        setMode(.pick, now)
        o.say = .okGet; o.sayObject = object; o.face = .thinking
        o.release = true // open the claw, ready
    }

    /// Hands full: go straight to the carrying leg.
    private func beginCarry(_ kind: VoiceGrabParser.Dest, _ dest: String?, now: Double) {
        destKind = kind
        destLabel = dest ?? ""
        destSteering = kind == .place ? GrabSteering(object: destLabel, grabCm: Self.placeStopCm)
            : kind == .nextTo ? GrabSteering(object: destLabel, grabCm: Self.nextToStopCm)
            : nil
        rememberHome()
        lookAroundSince = -1
        arrivedAt = 0
        setMode(.goto, now)
    }

    /// "go to the cup" / "move toward the bottle but don't touch it": find it, drive to it and
    /// stop near it. The claw is never signalled — its state stays whatever it already was.
    private func startApproach(_ r: VoiceGrabParser.Request, now: Double, o: inout Out) {
        let wanted = (r.object?.isEmpty == false) ? r.object! : object
        if wanted.isEmpty { o.say = .whichObject; o.face = .confused; return }
        object = wanted
        destKind = .person
        destLabel = ""
        destSteering = nil
        rememberHome()
        grab = GrabSteering(object: object, grabCm: Self.approachStopCm, approachOnly: true)
        saidSeeIt = false
        arrivedAt = 0
        setMode(.pick, now)
        o.say = .okGet; o.sayObject = object; o.face = .thinking
    }

    /// Remember "here" as where the speaker is (the old place to come back to).
    private func rememberHome() {
        homeX = x; homeY = y; homeHeading = heading
    }

    /// One camera frame. gripperState is the claw's own report (1 closed, 0 open, -1 unknown).
    func step(now: Double, objSeen: Bool, objOffset: Float, objHeight: Float,
              personSeen: Bool, personOffset: Float, personHeight: Float,
              destSeen: Bool, destOffset: Float, destHeight: Float, sonarCm: Float,
              gripperState: Int = -1) -> Out {
        integrate(now)
        var o = pending ?? Out()
        pending = nil
        var sonarOk = sonarCm > 0 && sonarCm < 250
        if holding && sonarOk && sonarCm < Self.heldObjectCm { sonarOk = false } // the object in the claw
        switch mode {
        case .idle: break
        case .pick: stepPick(&o, now, objSeen, objOffset, objHeight, sonarCm, sonarOk, gripperState)
        case .celebrate:
            let n = Int((now - modeSince) / Self.wiggleMs)
            if n < Self.wiggles {
                let s: Float = n % 2 == 0 ? 0.45 : -0.45
                o.left = s; o.right = -s
            } else {
                // Got it: now carry it where the words said.
                lookAroundSince = -1; arrivedAt = 0
                setMode(.goto, now); o.say = .coming
            }
            o.face = .happy; o.status = "Got the \(object)!"
        case .goto:
            if arrivedAt > 0 {
                // Stopped at the destination: a beat, then ask for the new command — or, while
                // collecting ("collect three objects"), drop this one off and fetch the next.
                if now - arrivedAt >= Self.askDelayMs {
                    arrivedAt = 0
                    if collectRemaining > 1 && destKind == .person {
                        collectRemaining -= 1
                        holding = false
                        startFetch(VoiceGrabParser.Request(intent: .pick, object: object, dest: nil,
                                                           destKind: .person), now: now, o: &o)
                    } else {
                        setMode(.idle, now); o.say = .askNext; o.face = .happy
                    }
                }
                break
            }
            switch destKind {
            case .person: stepBring(&o, now, personSeen, personOffset, personHeight, sonarCm, sonarOk)
            case .home: stepHome(&o, now, sonarCm, sonarOk)
            case .place, .nextTo: stepDest(&o, now, destSeen, destOffset, destHeight, sonarCm, sonarOk)
            }
        case .putDown:
            let t = now - modeSince
            if t < Self.putDownWaitMs { o.status = "Putting it down" }
            else if t < Self.putDownWaitMs + Self.backOffMs { o.left = -0.35; o.right = -0.35; o.status = "Stepping back" }
            else { holding = false; setMode(.idle, now); o.say = .thereYouGo; o.face = .happy }
        case .follow: stepFollow(&o, personSeen, personOffset, personHeight, sonarCm, sonarOk)
        case .hello:
            let t = now - modeSince
            if t < 2 * Self.wiggleMs { let s: Float = t < Self.wiggleMs ? 0.4 : -0.4; o.left = s; o.right = -s }
            else { setMode(.idle, now) }
        }
        lastLeft = o.left; lastRight = o.right
        if o.status.isEmpty { o.status = holding ? "Holding the \(object) ✊" : "Listening…" }
        return o
    }

    /// Find, reach and grip the object (all four commands start here) — or just reach it, for
    /// "go to …" (approachOnly steering ends at .arrived and never closes the claw).
    private func stepPick(_ o: inout Out, _ now: Double, _ seen: Bool, _ off: Float, _ h: Float, _ sonar: Float, _ sonarOk: Bool, _ gripperState: Int) {
        if seen && !saidSeeIt {
            saidSeeIt = true
            if o.say == nil { o.say = .seeIt; o.sayObject = object }
            if o.face == nil { o.face = .surprised }
        }
        guard let grab = grab else { return }
        let g = grab.step(now: now, seen: seen, offset: off, height: h, sonarCm: sonar, gripperState: gripperState)
        o.left = g.left; o.right = g.right
        o.approach = g.approach
        o.status = g.status
        if g.grip { o.grip = true }
        if g.state == .holding {
            holding = true; setMode(.celebrate, now); o.say = .gotIt; o.face = .happy
        } else if g.state == .arrived {
            // "go to the cup": close enough — stopped, nothing touched.
            setMode(.idle, now); o.say = .arrived; o.face = .happy
        } else if g.state == .aborted {
            setMode(.idle, now); o.say = .cantGrab; o.face = .sad
        } else if g.state == .lost {
            setMode(.idle, now); o.say = .cantGrab; o.sayObject = object; o.face = .sad
        }
    }

    /// Carry to the person (camera + dead reckoning): command 1.
    private func stepBring(_ o: inout Out, _ now: Double, _ personSeen: Bool, _ personOff: Float, _ personH: Float,
                           _ sonar: Float, _ sonarOk: Bool) {
        if now - modeSince > Self.bringGiveUpMs { arrive(&o, now); return } // as close as it got
        if personSeen {
            // You are in view: steer to you, stop in front of you (sonar, or how tall you look).
            lookAroundSince = -1
            let near = sonarOk ? sonar <= Self.personStopCm : personH >= Self.personNearHeight
            if near && abs(personOff) < 0.4 { arrive(&o, now); return }
            if abs(personOff) > 0.25 {
                let s: Float = 0.3 + 0.2 * min(1, abs(personOff))
                o.left = (personOff < 0 ? -1 : 1) * s; o.right = -o.left
            } else {
                let v: Float = sonarOk ? min(0.5, max(0.25, 0.25 + (sonar - Self.personStopCm) / 150)) : (personH > 0.5 ? 0.25 : 0.4)
                o.left = v + 0.25 * personOff; o.right = v - 0.25 * personOff
            }
            o.status = "Coming to you" + (sonarOk ? String(format: " · %.0f cm", sonar) : "")
            return
        }
        // Not in view: head back to where you were (dead reckoning).
        let dx = homeX - x, dy = homeY - y, dist = hypot(dx, dy)
        if dist < Self.homeCm || lookAroundSince >= 0 {
            // Back where you asked from, but you are not in view: look around for you, like a person.
            if lookAroundSince < 0 { lookAroundSince = now }
            let t = now - lookAroundSince
            if t > Self.lookAroundMs { arrive(&o, now); return }
            if t.truncatingRemainder(dividingBy: 830) < 330 { o.left = 0.35; o.right = -0.35 }
            o.face = .thinking; o.status = "Looking for you"
            return
        }
        if sonarOk && sonar <= Self.personStopCm { arrive(&o, now); return } // something right in front on the way back
        driveToHome(&o, dist, dx, dy, "Coming to you")
    }

    /// Carry to the old place (dead reckoning to where the robot was when you asked): command 3.
    private func stepHome(_ o: inout Out, _ now: Double, _ sonar: Float, _ sonarOk: Bool) {
        if now - modeSince > Self.bringGiveUpMs { arrive(&o, now); return } // as close as it got
        let dx = homeX - x, dy = homeY - y, dist = hypot(dx, dy)
        if dist < Self.homeCm { arrive(&o, now); return }
        if sonarOk && sonar <= Self.personStopCm { arrive(&o, now); return } // something right in front: stop here
        driveToHome(&o, dist, dx, dy, "Bringing it back")
    }

    private func driveToHome(_ o: inout Out, _ dist: Double, _ dx: Double, _ dy: Double, _ what: String) {
        let want = atan2(dx, dy) * 180 / .pi
        let err = Self.wrap(want - heading)
        if abs(err) > 20 {
            let s: Float = err < 0 ? -0.4 : 0.4
            o.left = s; o.right = -s
        } else {
            let v: Float = dist > 60 ? 0.5 : 0.3
            let steer = Float(err / 60)
            o.left = v + 0.2 * steer; o.right = v - 0.2 * steer
        }
        o.status = String(format: "%@ · %.0f cm to go", what, dist)
    }

    /// Carry to the landmark the words named — "to the new place" (command 2) or "next to the
    /// other object" (command 4). GrabSteering finds and centres it; its "grip" distance is the
    /// stop distance here (the claw keeps holding).
    private func stepDest(_ o: inout Out, _ now: Double, _ seen: Bool, _ off: Float, _ h: Float,
                          _ sonar: Float, _ sonarOk: Bool) {
        if now - modeSince > Self.bringGiveUpMs { arrive(&o, now); return } // as close as it got
        guard let destSteering = destSteering else { return }
        let g = destSteering.step(now: now, seen: seen, offset: off, height: h, sonarCm: sonar)
        if g.state == .lost {
            setMode(.idle, now); o.say = .cantFind; o.sayObject = destLabel; o.face = .sad
            return
        }
        if g.state == .grip || g.state == .holding || g.state == .aborted {
            arrive(&o, now) // at the stop distance from the destination (or blocked just short of it)
            return
        }
        o.left = g.left; o.right = g.right
        // Never forward g.approach / g.grip: the claw keeps holding, no firmware reach-mode.
        o.status = g.status
    }

    private func arrive(_ o: inout Out, _ now: Double) {
        lookAroundSince = -1
        arrivedAt = now
        if destKind == .place || destKind == .nextTo {
            o.release = true // "put it at the table" — open the claw on arrival
            holding = false
        }
        o.say = destKind == .person ? .hereYouAre : .arrived
        o.sayObject = destLabel
        o.face = .happy
        o.status = destKind == .person ? "Here you are!"
            : destKind == .home ? "Back where we started!"
            : destKind == .nextTo ? "Here, next to the \(destLabel)!"
            : "Here at the \(destLabel)!"
    }

    private func stepFollow(_ o: inout Out, _ seen: Bool, _ off: Float, _ personH: Float, _ sonar: Float, _ sonarOk: Bool) {
        guard seen else { o.face = .thinking; o.status = "Where are you?"; return } // never drive blind after a person
        let turn: Float = abs(off) > 0.2 ? 0.25 * off : 0
        var v: Float = 0
        if sonarOk {
            if sonar > Self.followCm + 15 { v = min(0.55, max(0.25, (sonar - Self.followCm) / 120)) }
            else if sonar < Self.followCm - 20 { v = -0.25 } // too close: step back
        } else {
            v = personH > 0.7 ? 0 : personH > 0.45 ? 0.25 : 0.4 // no sonar: how tall you look
        }
        let spin: Float = v == 0 ? 0.3 * (turn < 0 ? -1 : turn > 0 ? 1 : 0) : 0
        o.left = v + turn + spin; o.right = v - turn - spin
        o.status = "Following you" + (sonarOk ? String(format: " · %.0f cm", sonar) : "")
    }

    /// Dead reckoning from the wheel commands of the last frame.
    private func integrate(_ now: Double) {
        if lastStep >= 0 {
            let dt = min(0.5, (now - lastStep) / 1000)
            let v = Double(lastLeft + lastRight) / 2 * Self.cmPerS
            let w = Double(lastLeft - lastRight) / 2 * Self.degPerS
            heading = Self.wrap(heading + w * dt)
            x += v * dt * sin(heading * .pi / 180)
            y += v * dt * cos(heading * .pi / 180)
        }
        lastStep = now
    }

    private static func wrap(_ a: Double) -> Double {
        var a = a
        while a > 180 { a -= 360 }
        while a < -180 { a += 360 }
        return a
    }
}
