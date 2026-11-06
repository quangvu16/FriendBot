//
//  VisionModeController.swift
//  OpenBot
//
//  Shared plumbing for the camera-based vision modes (follow coloured object, follow face,
//  follow black line, object avoidance, target tracking): the camera overlay, the
//  Auto / speed / camera flip / help controls, and de-duplicated drive output to the vehicle.
//  Mirrors the Android VisionModeFragment.
//

import UIKit
import AVFoundation

/// Read-only BGRA access to a camera frame for pixel-scanning modes.
struct FramePixels {
    let width: Int
    let height: Int
    private let bytesPerRow: Int
    private let base: UnsafeMutablePointer<UInt8>

    init(width: Int, height: Int, bytesPerRow: Int, base: UnsafeMutablePointer<UInt8>) {
        self.width = width
        self.height = height
        self.bytesPerRow = bytesPerRow
        self.base = base
    }

    /// Samples the pixel at (x, y) as red, green, blue in 0...255.
    func rgb(x: Int, y: Int) -> (Int, Int, Int) {
        let p = base + y * bytesPerRow + x * 4
        return (Int(p[2]), Int(p[1]), Int(p[0]))
    }

    /// Greyscale brightness 0...255 of the pixel at (x, y).
    func brightness(x: Int, y: Int) -> Int {
        let (r, g, b) = rgb(x: x, y: y)
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}

/// Locks a CVPixelBuffer and hands pixel access to `body`.
func withFramePixels<T>(_ buffer: CVPixelBuffer, _ body: (FramePixels) -> T) -> T? {
    guard CVPixelBufferLockBaseAddress(buffer, .readOnly) == kCVReturnSuccess else { return nil }
    defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
    guard let addr = CVPixelBufferGetBaseAddress(buffer) else { return nil }
    let pixels = FramePixels(
        width: CVPixelBufferGetWidth(buffer),
        height: CVPixelBufferGetHeight(buffer),
        bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),
        base: addr.assumingMemoryBound(to: UInt8.self))
    return body(pixels)
}

class VisionModeController: CameraController {

    let overlay = VisionOverlayView()
    private let statusLabel = UILabel()
    private let controlPanel = UIView()
    private let autoSwitch = UISwitch()
    private let speedButton = UIButton(type: .system)
    private let cameraButton = UIButton(type: .system)
    private let helpButton = UIButton(type: .system)
    let modePanel = UIStackView()

    /// Written on the camera queue, read on the main queue for the Auto toggle state.
    var autoMode = false
    private var lastControl = Control()
    private let bluetooth = bluetoothDataController.shared

    /// Navigation title of the instruction guide that goes with this mode, or nil when none.
    func guideId() -> String? { nil }

    /// Builds the mode-specific row of controls in `modePanel`.
    func setupModeControls() {}

    /// Runs on the camera queue for every frame while Auto is on.
    func processVisionFrame(_ pixelBuffer: CVPixelBuffer) {}

    override func viewDidLoad() {
        super.viewDidLoad()
        createCameraView()
        setupChrome()
        gameController.resetControl = false
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        autoMode = false
        sendControl(Control())
    }

    // MARK: - UI

    private func setupChrome() {
        overlay.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(overlay)

        statusLabel.textColor = .white
        statusLabel.font = UIFont.systemFont(ofSize: 14, weight: .semibold)
        statusLabel.backgroundColor = UIColor.black.withAlphaComponent(0.6)
        statusLabel.layer.cornerRadius = 8
        statusLabel.layer.masksToBounds = true
        statusLabel.text = "  " + Strings.visionStopped + "  "
        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(statusLabel)

        controlPanel.backgroundColor = UIColor.black.withAlphaComponent(0.65)
        controlPanel.layer.cornerRadius = 16
        controlPanel.layer.maskedCorners = [.layerMinXMinYCorner, .layerMaxXMinYCorner]
        controlPanel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(controlPanel)

        let autoLabel = UILabel()
        autoLabel.text = Strings.visionAuto
        autoLabel.textColor = .white
        autoLabel.font = UIFont.systemFont(ofSize: 14, weight: .semibold)
        autoSwitch.onTintColor = .systemGreen
        autoSwitch.addTarget(self, action: #selector(autoToggled), for: .valueChanged)

        speedButton.setTitle(Strings.speed, for: .normal)
        speedButton.setTitleColor(.white, for: .normal)
        speedButton.titleLabel?.font = UIFont.systemFont(ofSize: 14, weight: .semibold)
        speedButton.addTarget(self, action: #selector(speedTapped), for: .touchUpInside)

        cameraButton.setImage(UIImage(systemName: "camera.rotate"), for: .normal)
        cameraButton.tintColor = .white
        cameraButton.addTarget(self, action: #selector(cameraTapped), for: .touchUpInside)

        helpButton.setTitle(Strings.visionHelp, for: .normal)
        helpButton.setTitleColor(.white, for: .normal)
        helpButton.titleLabel?.font = UIFont.systemFont(ofSize: 14, weight: .semibold)
        helpButton.addTarget(self, action: #selector(helpTapped), for: .touchUpInside)

        let row1 = UIStackView(arrangedSubviews: [autoLabel, autoSwitch, speedButton, cameraButton, helpButton])
        row1.axis = .horizontal
        row1.alignment = .center
        row1.spacing = 12

        modePanel.axis = .horizontal
        modePanel.alignment = .center
        modePanel.spacing = 10

        let panelStack = UIStackView(arrangedSubviews: [row1, modePanel])
        panelStack.axis = .vertical
        panelStack.spacing = 12
        panelStack.translatesAutoresizingMaskIntoConstraints = false
        controlPanel.addSubview(panelStack)

        NSLayoutConstraint.activate([
            overlay.topAnchor.constraint(equalTo: view.topAnchor),
            overlay.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            overlay.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            overlay.trailingAnchor.constraint(equalTo: view.trailingAnchor),

            statusLabel.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            statusLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),

            controlPanel.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            controlPanel.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            controlPanel.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            panelStack.topAnchor.constraint(equalTo: controlPanel.topAnchor, constant: 14),
            panelStack.leadingAnchor.constraint(equalTo: controlPanel.leadingAnchor, constant: 16),
            panelStack.trailingAnchor.constraint(equalTo: controlPanel.trailingAnchor, constant: -16),
            panelStack.bottomAnchor.constraint(
                equalTo: controlPanel.safeAreaLayoutGuide.bottomAnchor, constant: -12)
        ])

        updateSpeedButton()
        setupModeControls()
        setStatus(autoMode ? Strings.visionRunning : Strings.visionStopped)
    }

    @objc private func autoToggled() {
        setAutoMode(autoSwitch.isOn)
    }

    private func setAutoMode(_ enabled: Bool) {
        autoMode = enabled
        if !enabled {
            sendControl(Control())
            setStatus(Strings.visionStopped)
        } else {
            setStatus(Strings.visionRunning)
        }
    }

    @objc private func speedTapped() {
        switch gameController.selectedSpeedMode {
        case .SLOW: gameController.selectedSpeedMode = .NORMAL
        case .NORMAL: gameController.selectedSpeedMode = .FAST
        case .FAST: gameController.selectedSpeedMode = .SLOW
        }
        updateSpeedButton()
    }

    private func updateSpeedButton() {
        switch gameController.selectedSpeedMode {
        case .SLOW: speedButton.setTitle(Strings.slow, for: .normal)
        case .NORMAL: speedButton.setTitle(Strings.medium, for: .normal)
        case .FAST: speedButton.setTitle(Strings.fast, for: .normal)
        }
    }

    @objc private func cameraTapped() {
        switchCameraView()
    }

    @objc private func helpTapped() {
        guard let guideId = guideId() else {
            setStatus(helpFallback())
            return
        }
        let controller = GuideController(guideId: guideId)
        navigationController?.pushViewController(controller, animated: true)
    }

    /// Status-line fallback when a mode has no guide page.
    func helpFallback() -> String { Strings.visionHelp }

    // MARK: - Mode panel helpers

    /// Adds a "label [−] value [+]" stepper to the mode panel.
    @discardableResult
    func addStepper(
        label: String, min: Int, max: Int, step: Int, initial: Int,
        onChange: @escaping (Int) -> Void
    ) -> UIView {
        let labelView = UILabel()
        labelView.text = label
        labelView.textColor = .white
        labelView.font = UIFont.systemFont(ofSize: 13)

        let valueLabel = UILabel()
        valueLabel.text = String(initial)
        valueLabel.textColor = .white
        valueLabel.font = UIFont.monospacedDigitSystemFont(ofSize: 14, weight: .bold)
        valueLabel.widthAnchor.constraint(equalToConstant: 32).isActive = true

        var value = initial
        let minus = makePanelButton(title: "−")
        let plus = makePanelButton(title: "+")
        minus.addAction(UIAction { _ in
            guard value > min else { return }
            value = Swift.max(min, value - step)
            valueLabel.text = String(value)
            onChange(value)
        }, for: .touchUpInside)
        plus.addAction(UIAction { _ in
            guard value < max else { return }
            value = Swift.min(max, value + step)
            valueLabel.text = String(value)
            onChange(value)
        }, for: .touchUpInside)

        let stepper = UIStackView(arrangedSubviews: [labelView, minus, valueLabel, plus])
        stepper.axis = .horizontal
        stepper.alignment = .center
        stepper.spacing = 6
        modePanel.addArrangedSubview(stepper)
        onChange(initial)
        return stepper
    }

    /// Adds a small action button to the mode panel.
    @discardableResult
    func addActionButton(title: String, action: @escaping () -> Void) -> UIButton {
        let button = makePanelButton(title: title)
        button.addAction(UIAction { _ in action() }, for: .touchUpInside)
        modePanel.addArrangedSubview(button)
        return button
    }

    private func makePanelButton(title: String) -> UIButton {
        let button = UIButton(type: .system)
        button.setTitle(title, for: .normal)
        button.setTitleColor(.white, for: .normal)
        button.titleLabel?.font = UIFont.systemFont(ofSize: 13, weight: .semibold)
        button.backgroundColor = UIColor.white.withAlphaComponent(0.18)
        button.layer.cornerRadius = 8
        button.contentEdgeInsets = UIEdgeInsets(top: 6, left: 10, bottom: 6, right: 10)
        return button
    }

    // MARK: - Frame plumbing

    override func captureOutput(
        _ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        CameraHub.shared.offer(sampleBuffer)
        super.captureOutput(output, didOutput: sampleBuffer, from: connection)
        guard autoMode, let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        overlay.bufferSize = CGSize(
            width: CVPixelBufferGetWidth(pixelBuffer), height: CVPixelBufferGetHeight(pixelBuffer))
        overlay.mirrored = isFrontCamera()
        processVisionFrame(pixelBuffer)
    }

    func isFrontCamera() -> Bool {
        guard let input = captureSession?.inputs.first as? AVCaptureDeviceInput else { return false }
        return input.device.position == .front
    }

    /// Sends a drive command, skipping repeats (the car only hears changes).
    func sendControl(_ control: Control) {
        DispatchQueue.main.async {
            if control.getLeft() == self.lastControl.getLeft(),
               control.getRight() == self.lastControl.getRight() {
                return
            }
            self.lastControl = control
            let left = control.getLeft() * GameController.shared.selectedSpeedMode.rawValue
            let right = control.getRight() * GameController.shared.selectedSpeedMode.rawValue
            self.bluetooth.sendData(payload: "c" + String(left) + "," + String(right) + "\n")
        }
    }

    /// Status line in the top-left corner; safe from any thread.
    func setStatus(_ status: String) {
        DispatchQueue.main.async {
            self.statusLabel.text = "  " + status + "  "
        }
    }

    /// Publishes boxes (camera-buffer coordinates) on the overlay; safe from any thread.
    func showBoxes(_ boxes: [VisionOverlayView.Box]) {
        DispatchQueue.main.async {
            self.overlay.boxes = boxes
        }
    }
}
