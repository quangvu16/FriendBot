//
//  HardwareTestController.swift
//  OpenBot
//
//  Hardware Test (General tab), Google Material style: toggle the gripper and the two LEDs and
//  watch the middle sonar report live while the page is open. Plain firmware commands - g0/g1/g2
//  for the claw, i<left>,<right> for the LEDs and s<ms> for the sonar stream - mirroring the
//  Android HardwareTestFragment.
//

import UIKit

class HardwareTestController: UIViewController {
    private let bluetooth = bluetoothDataController.shared

    private let gripperSwitch = UISwitch()
    private let gripperStateLabel = UILabel()
    private let gripperChip = UIView()
    private let leftLedSwitch = UISwitch()
    private let rightLedSwitch = UISwitch()
    private let sonarLabel = UILabel()
    private let sonarBar = UIProgressView(progressViewStyle: .default)
    private let linkLabel = UILabel()

    /// Motors: hold a direction button to drive; the work item is the 3 s auto-stop.
    private var motorStopWork: DispatchWorkItem?
    /// ~60% duty, the same Int((left * 255).rounded()) scaling the Voice Control page uses.
    private static let motorSpeed: Float = 0.6
    private static let motorMaxHold: TimeInterval = 3

    override func viewDidLoad() {
        super.viewDidLoad()
        title = Strings.tileHardwareTest
        view.backgroundColor = .systemGroupedBackground
        buildLayout()

        gripperSwitch.addTarget(self, action: #selector(gripperChanged), for: .valueChanged)
        leftLedSwitch.addTarget(self, action: #selector(ledsChanged), for: .valueChanged)
        rightLedSwitch.addTarget(self, action: #selector(ledsChanged), for: .valueChanged)

        // Every incoming BLE line posts .updateLabel, so the screen refreshes itself live.
        NotificationCenter.default.addObserver(self, selector: #selector(refresh), name: .updateLabel, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(refresh), name: .bluetoothConnected, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(refresh), name: .bluetoothDisconnected, object: nil)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        bluetooth.sendData(payload: "g2\n")  // firmware replies with the current claw state
        bluetooth.sendData(payload: "s500\n") // live sonar at 2 Hz while the page is open
        refresh()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        stopMotors() // never leave the wheels running behind this page
        bluetooth.sendData(payload: "s0\n") // stop the stream when the page goes away
    }

    // MARK: - Motors (hold to drive)

    /// One hold-to-drive button: the action fires on touch down and any release stops the wheels.
    private func makeMotorButton(_ title: String, tag: Int, stop: Bool = false) -> UIButton {
        let button = UIButton(type: .system)
        button.setTitle(title, for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: stop ? 14 : 20, weight: .medium)
        button.tag = tag
        button.backgroundColor = stop ? .systemRed : .tertiarySystemFill
        if stop { button.setTitleColor(.white, for: .normal) }
        button.layer.cornerRadius = 10
        if !stop {
            // Only the direction buttons drive; Stop (tag -1) halts on touch up alone.
            button.addTarget(self, action: #selector(motorDown(_:)), for: .touchDown)
        }
        button.addTarget(self, action: #selector(motorReleased),
                         for: [.touchUpInside, .touchUpOutside, .touchCancel])
        button.heightAnchor.constraint(equalToConstant: 48).isActive = true
        return button
    }

    /// touchDown on a direction button: drive that way and arm the 3 s auto-stop.
    @objc private func motorDown(_ sender: UIButton) {
        let s = Self.motorSpeed
        switch sender.tag {
        case 0: driveMotors(s, s)        // forward
        case 1: driveMotors(-s, -s)      // backward
        case 2: driveMotors(-s, s)       // spin left
        default: driveMotors(s, -s)      // spin right
        }
    }

    /// Any release (and the Stop button) halts the wheels at once.
    @objc private func motorReleased() { stopMotors() }

    /// Raw "c<left>,<right>" at ±(0.6 * 255), exactly like VoiceControlController.sendControl.
    private func driveMotors(_ left: Float, _ right: Float) {
        let l = Int((left * 255).rounded())
        let r = Int((right * 255).rounded())
        bluetooth.sendData(payload: "c\(l),\(r)\n")
        motorStopWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.stopMotors() }
        motorStopWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.motorMaxHold, execute: work)
    }

    private func stopMotors() {
        motorStopWork?.cancel()
        motorStopWork = nil
        bluetooth.sendData(payload: "c0,0\n")
    }

    @objc private func gripperChanged() {
        bluetooth.sendData(payload: gripperSwitch.isOn ? "g1\n" : "g0\n")
    }

    @objc private func ledsChanged() {
        bluetooth.sendData(payload: "i\(leftLedSwitch.isOn ? 1 : 0),\(rightLedSwitch.isOn ? 1 : 0)\n")
    }

    /// Re-reads what the firmware has reported so far: the claw state, the sonar distance
    /// and the link status.
    @objc private func refresh() {
        let state = bluetooth.gripperState
        switch state {
        case 0:
            gripperStateLabel.text = Strings.hwStateOpen
            gripperChip.backgroundColor = .tertiarySystemFill
            gripperStateLabel.textColor = .secondaryLabel
        case 1:
            gripperStateLabel.text = Strings.hwStateClosed
            gripperChip.backgroundColor = .systemBlue.withAlphaComponent(0.15)
            gripperStateLabel.textColor = .systemBlue
        default:
            gripperStateLabel.text = Strings.hwStateUnknown
            gripperChip.backgroundColor = .tertiarySystemFill
            gripperStateLabel.textColor = .secondaryLabel
        }
        // Programmatic setOn does not fire .valueChanged, so no command loops back.
        if state >= 0 && gripperSwitch.isOn != (state == 1) {
            gripperSwitch.setOn(state == 1, animated: true)
        }

        let cm = bluetooth.sonarDistanceCm
        sonarLabel.text = cm < 0 ? Strings.hwSonarNone : String(format: Strings.hwSonarValue, cm)
        let clamped = max(0, min(Double(cm), 200))
        sonarBar.setProgress(Float(clamped / 200), animated: true)
        sonarBar.progressTintColor = cm < 0 ? .systemGray
            : cm < 20 ? .systemRed
            : cm < 50 ? .systemYellow
            : .systemGreen
        linkLabel.text = isBluetoothConnected ? Strings.hwConnected : Strings.hwDisconnected
    }

    // MARK: - Layout helpers

    /// A white rounded card holding the given views in a vertical stack.
    private func makeCard(_ views: [UIView]) -> UIView {
        let card = UIView()
        card.backgroundColor = .secondarySystemGroupedBackground
        card.layer.cornerRadius = 16
        card.layer.borderWidth = 1
        card.layer.borderColor = UIColor.separator.cgColor

        let stack = UIStackView(arrangedSubviews: views)
        stack.axis = .vertical
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false
        card.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: card.topAnchor, constant: 16),
            stack.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -16),
            stack.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -16),
        ])
        return card
    }

    private func row(_ views: [UIView]) -> UIStackView {
        let stack = UIStackView(arrangedSubviews: views)
        stack.axis = .horizontal
        stack.alignment = .center
        stack.spacing = 12
        return stack
    }

    /// Title (16 pt) above a grey hint line (12 pt).
    private func textColumn(_ title: String, _ hint: String) -> UIStackView {
        let titleLabel = UILabel()
        titleLabel.text = title
        titleLabel.font = .systemFont(ofSize: 16, weight: .medium)

        let hintLabel = UILabel()
        hintLabel.text = hint
        hintLabel.font = .systemFont(ofSize: 12)
        hintLabel.textColor = .secondaryLabel
        hintLabel.numberOfLines = 0

        let stack = UIStackView(arrangedSubviews: [titleLabel, hintLabel])
        stack.axis = .vertical
        stack.spacing = 2
        stack.setContentHuggingPriority(.defaultLow, for: .horizontal)
        return stack
    }

    private func sectionTitle(_ text: String) -> UILabel {
        let label = UILabel()
        label.text = text
        label.font = .systemFont(ofSize: 16, weight: .medium)
        return label
    }

    private func ledDot(_ color: UIColor) -> UIView {
        let dot = UIView()
        dot.backgroundColor = color
        dot.layer.cornerRadius = 6
        dot.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            dot.widthAnchor.constraint(equalToConstant: 12),
            dot.heightAnchor.constraint(equalToConstant: 12),
        ])
        return dot
    }

    private func buildLayout() {
        // Gripper: title + state chip, then the switch row.
        gripperStateLabel.font = .systemFont(ofSize: 12, weight: .medium)
        gripperStateLabel.translatesAutoresizingMaskIntoConstraints = false
        gripperChip.layer.cornerRadius = 8
        gripperChip.addSubview(gripperStateLabel)
        NSLayoutConstraint.activate([
            gripperStateLabel.topAnchor.constraint(equalTo: gripperChip.topAnchor, constant: 4),
            gripperStateLabel.bottomAnchor.constraint(equalTo: gripperChip.bottomAnchor, constant: -4),
            gripperStateLabel.leadingAnchor.constraint(equalTo: gripperChip.leadingAnchor, constant: 10),
            gripperStateLabel.trailingAnchor.constraint(equalTo: gripperChip.trailingAnchor, constant: -10),
        ])
        let gripperHeader = row([sectionTitle(Strings.hwGripper), UIView(), gripperChip])
        let gripperCard = makeCard([
            gripperHeader,
            row([textColumn(Strings.hwGripperClose, Strings.hwGripperHint), gripperSwitch]),
        ])

        // Motors: hold a direction to drive, release (or 3 s) stops them. The STOP/RESUME
        // filter in writeWire zeroes these "c" lines while the e-stop is latched.
        let motorStack = UIStackView(arrangedSubviews: [
            makeMotorButton("◀", tag: 2),
            makeMotorButton("▲", tag: 0),
            makeMotorButton("▼", tag: 1),
            makeMotorButton("▶", tag: 3),
            makeMotorButton(Strings.hwMotorStop, tag: -1, stop: true),
        ])
        motorStack.axis = .horizontal
        motorStack.spacing = 8
        motorStack.distribution = .fillEqually
        let motorCard = makeCard([
            textColumn(Strings.hwMotors, Strings.hwMotorsHint),
            motorStack,
        ])

        // LEDs: one switch per LED, each with a coloured dot.
        let leftRow = row([ledDot(.systemYellow), textColumn(Strings.hwLeftLed, Strings.hwLedHint), leftLedSwitch])
        let rightRow = row([ledDot(.systemGreen), textColumn(Strings.hwRightLed, Strings.hwLedHint), rightLedSwitch])
        let ledCard = makeCard([sectionTitle(Strings.hwLeds), leftRow, rightRow])

        // Sonar: live distance, bar, scale and link status.
        sonarLabel.font = .systemFont(ofSize: 48, weight: .regular)
        linkLabel.font = .systemFont(ofSize: 12)
        linkLabel.textColor = .secondaryLabel

        let scale = UIStackView(arrangedSubviews: ["0 cm", "100 cm", "200 cm"].map { text -> UILabel in
            let label = UILabel()
            label.text = text
            label.font = .systemFont(ofSize: 12)
            label.textColor = .secondaryLabel
            return label
        })
        scale.axis = .horizontal
        scale.distribution = .equalSpacing

        let sonarHeader = row([
            textColumn(Strings.hwSonar, Strings.hwSonarHint),
            UIView(),
        ])
        let sonarCard = makeCard([sonarHeader, sonarLabel, sonarBar, scale, linkLabel])

        // Info note in a light blue tint.
        let info = UILabel()
        info.text = Strings.hwInfo
        info.font = .systemFont(ofSize: 13)
        info.numberOfLines = 0
        let infoCard = makeCard([info])
        infoCard.backgroundColor = .systemBlue.withAlphaComponent(0.1)
        infoCard.layer.borderWidth = 0

        // Scrollable column of cards.
        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scroll)

        let content = UIStackView(arrangedSubviews: [gripperCard, motorCard, ledCard, sonarCard, infoCard])
        content.axis = .vertical
        content.spacing = 16
        content.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(content)

        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),

            content.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 16),
            content.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -16),
            content.leadingAnchor.constraint(equalTo: scroll.frameLayoutGuide.leadingAnchor, constant: 16),
            content.trailingAnchor.constraint(equalTo: scroll.frameLayoutGuide.trailingAnchor, constant: -16),
        ])
    }
}
