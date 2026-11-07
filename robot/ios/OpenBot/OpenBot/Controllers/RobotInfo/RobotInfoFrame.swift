//
// Created by Nitish Yadav on 22/12/22.
// Car setup screen: robot type, drive info, the feature flags the firmware
// reports and motor/steering test commands, plus the RC receiver state.
//

import Foundation
import UIKit

class RobotInfoFrame: UIViewController {

    let bluetooth = bluetoothDataController.shared
    var robotInfo: String = ""
    @IBOutlet weak var openBotIcon: UIImageView!

    private let scroll = UIScrollView()
    private let stack = UIStackView()
    private let robotName = UILabel()
    private let driveInfo = UILabel()
    private let iconView = UIImageView()
    private var bluetoothIconButton = UIButton(type: .system)
    private var features: [String: (Checkbox, UILabel)] = [:]
    private let rcModeValue = UILabel()
    private let linkValue = UILabel()
    private var rcTimer: Timer?
    /// Sensor link (WebSocket) button: share sensors with a browser or script.
    private lazy var sensorLinkItem = SensorLinkBarItem(owner: self, screen: "Car setup")

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        openBotIcon?.isHidden = true // the storyboard icon is replaced by iconView in the stack
        setupNavigationBarItem()
        createUI()
        NotificationCenter.default.addObserver(self, selector: #selector(updateConnect), name: .bluetoothConnected, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(updateConnect), name: .bluetoothDisconnected, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(updateScreen), name: .updateLabel, object: nil)
        bluetooth.sendData(payload: "f\n")
        updateRobotInfo()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        updateRobotInfo()
        if !isBluetoothConnected { updateScreenOnBluetoothDisconnection() }
        rcTimer?.invalidate()
        rcTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in self?.showRcMode() }
        showRcMode()
        sensorLinkItem.appeared()
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        sensorLinkItem.disappeared()
        rcTimer?.invalidate()
        rcTimer = nil
    }

    // MARK: Layout (a scrolling stack: works in portrait and landscape)

    private func createUI() {
        scroll.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scroll)
        stack.axis = .vertical
        stack.spacing = 6
        stack.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(stack)
        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 12),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -24),
            stack.leadingAnchor.constraint(equalTo: scroll.frameLayoutGuide.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(equalTo: scroll.frameLayoutGuide.trailingAnchor, constant: -16),
        ])

        // Header: robot type, refresh, Bluetooth.
        let typeLabel = UILabel()
        typeLabel.text = Strings.robotTypeHeading
        typeLabel.font = .boldSystemFont(ofSize: 14)
        robotName.text = "N/A"
        robotName.font = .systemFont(ofSize: 14)
        let refresh = UIButton(type: .system)
        refresh.setImage(UIImage(named: "refresh") ?? UIImage(systemName: "arrow.clockwise"), for: .normal)
        refresh.addTarget(self, action: #selector(refreshTapped), for: .touchUpInside)
        bluetoothIconButton.setImage(isBluetoothConnected ? Images.bluetoothConnected_v2 : Images.bluetoothDisconnected_v2, for: .normal)
        bluetoothIconButton.addTarget(self, action: #selector(openBluetoothSettings(_:)), for: .touchUpInside)
        let header = UIStackView(arrangedSubviews: [typeLabel, robotName, UIView(), refresh, bluetoothIconButton])
        header.spacing = 8
        header.alignment = .center
        stack.addArrangedSubview(header)

        iconView.contentMode = .scaleAspectFit
        iconView.image = UIImage(named: "openBotLogo")
        iconView.heightAnchor.constraint(equalToConstant: 120).isActive = true
        stack.addArrangedSubview(iconView)
        driveInfo.text = L10n.string("setup_drive_unknown")
        driveInfo.font = .systemFont(ofSize: 13)
        driveInfo.textAlignment = .center
        driveInfo.numberOfLines = 0
        stack.addArrangedSubview(driveInfo)

        // What the car reports in its 'f' reply (plus the RC receiver once 'm' arrives).
        stack.addArrangedSubview(heading("setup_features"))
        let keys: [(String, String)] = [
            ("rc", "setup_feat_rc"),
        ]
        for pair in stride(from: 0, to: keys.count, by: 2) {
            let row = UIStackView()
            row.distribution = .fillEqually
            row.spacing = 8
            for k in keys[pair..<min(pair + 2, keys.count)] {
                let box = Checkbox(frame: CGRect(x: 0, y: 0, width: 22, height: 22))
                let dark = traitCollection.userInterfaceStyle == .dark
                box.checkedBorderColor = dark ? .white : .black
                box.uncheckedBorderColor = dark ? .white : .black
                box.checkmarkColor = dark ? .white : .black
                box.checkmarkStyle = .tick
                box.isEnabled = false
                box.widthAnchor.constraint(equalToConstant: 22).isActive = true
                box.heightAnchor.constraint(equalToConstant: 22).isActive = true
                let label = UILabel()
                label.text = L10n.string(k.1)
                label.font = .systemFont(ofSize: 13)
                label.numberOfLines = 0
                let cell = UIStackView(arrangedSubviews: [box, label])
                cell.spacing = 6
                cell.alignment = .center
                row.addArrangedSubview(cell)
                features[k.0] = (box, label)
            }
            stack.addArrangedSubview(row)
        }

        // Live readings.
        stack.addArrangedSubview(heading("readings"))
        stack.addArrangedSubview(reading("setup_rc_mode", rcModeValue))
        stack.addArrangedSubview(reading("setup_link", linkValue))

        // Tests.
        stack.addArrangedSubview(heading("sendCommand"))
        stack.addArrangedSubview(buttonRow(Strings.motors, [
            (Strings.forward, "arrow.up", #selector(forwardButton(_:))), (Strings.backward, "arrow.down", #selector(backwardButton(_:))),
            (Strings.stopLabel, "stop.fill", #selector(stopButton(_:))),
        ]))
        stack.addArrangedSubview(buttonRow(L10n.string("setup_steering"), [
            (L10n.string("setup_left"), "arrow.turn.up.left", #selector(steerLeft)),
            (L10n.string("setup_centre"), "steeringwheel", #selector(steerCentre)),
            (L10n.string("setup_right"), "arrow.turn.up.right", #selector(steerRight)),
        ]))
        let note = UILabel()
        note.text = L10n.string("setup_test_note")
        note.font = .systemFont(ofSize: 12)
        note.textColor = .secondaryLabel
        note.numberOfLines = 0
        stack.addArrangedSubview(note)

        rcModeValue.text = "N/A"
        showLink()
        showFeatures()
    }

    private func heading(_ key: String) -> UILabel {
        let l = UILabel()
        l.text = L10n.string(key)
        l.font = .systemFont(ofSize: 15, weight: .heavy)
        l.textColor = Colors.border
        l.heightAnchor.constraint(greaterThanOrEqualToConstant: 32).isActive = true
        return l
    }

    private func reading(_ key: String, _ value: UILabel) -> UIStackView {
        let name = UILabel()
        name.text = L10n.string(key)
        name.font = .systemFont(ofSize: 13)
        value.font = .boldSystemFont(ofSize: 13)
        let row = UIStackView(arrangedSubviews: [name, value])
        row.distribution = .fillEqually
        return row
    }

    private func buttonRow(_ title: String, _ buttons: [(String, String, Selector)]) -> UIStackView {
        let label = UILabel()
        label.text = title
        label.font = .systemFont(ofSize: 13)
        label.widthAnchor.constraint(equalToConstant: 84).isActive = true
        let row = UIStackView(arrangedSubviews: [label])
        row.spacing = 6
        row.alignment = .center
        let group = UIStackView()
        group.distribution = .fillEqually
        group.spacing = 6
        for (text, symbol, sel) in buttons {
            var c = UIButton.Configuration.filled()
            c.baseBackgroundColor = Colors.title
            let b = UIButton.icon(symbol, label: text, style: c)
            b.addTarget(self, action: sel, for: .touchUpInside)
            b.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
            group.addArrangedSubview(b)
        }
        row.addArrangedSubview(group)
        return row
    }

    // MARK: State

    func updateRobotInfo() {
        if isBluetoothConnected { bluetooth.sendData(payload: "f\n") }
        robotInfo = bluetooth.robotInfo
        if robotInfo.isEmpty { return }
        let name = robotName(from: robotInfo)
        robotName.text = name
        let ackermann = name.hasPrefix("RC_")
        driveInfo.text = L10n.string(ackermann ? "setup_drive_ackermann" : "setup_drive_unknown")
        updateLogo(ackermann: ackermann)
        showFeatures()
        showLink()
    }

    /// Feature flags as the firmware sends them in its `f` reply. Parsed
    /// leniently: unknown fields are ignored, so old and new firmware mixes
    /// both work.
    private func has(_ flag: String) -> Bool { robotInfo.contains(":" + flag + ":") }

    private func showFeatures() {
        showRcMode()
    }

    private func showRcMode() {
        let mode = RcModeFaceOverlay.shared.currentMode
        features["rc"]?.0.isChecked = mode != nil
        switch mode {
        case .manual?: rcModeValue.text = L10n.string("setup_rc_manual")
        case .auto?: rcModeValue.text = L10n.string("setup_rc_auto")
        case .hold?: rcModeValue.text = L10n.string("setup_rc_hold")
        case .lost?: rcModeValue.text = L10n.string("setup_rc_lost")
        case nil: rcModeValue.text = "N/A"
        }
    }

    private func showLink() {
        linkValue.text = L10n.string(isBluetoothConnected ? "setup_link_ble" : "setup_link_none")
    }

    private func robotName(from info: String) -> String {
        guard let f = info.firstIndex(of: "f"), let colon = info.firstIndex(of: ":"), f < colon else { return "N/A" }
        return String(info[info.index(after: f)..<colon])
    }

    private func updateLogo(ackermann: Bool) {
        if ackermann, let data = NSDataAsset(name: "rc_car")?.data {
            iconView.image = UIImage.gifImageWithData(data)
        } else {
            iconView.image = UIImage(named: "openBotLogo")
        }
    }

    @objc func updateConnect(_ notification: Notification) {
        bluetoothIconButton.setImage(isBluetoothConnected ? Images.bluetoothConnected_v2 : Images.bluetoothDisconnected_v2, for: .normal)
        if isBluetoothConnected { updateRobotInfo() } else { updateScreenOnBluetoothDisconnection() }
    }

    @objc func updateScreen() {
        if robotInfo.isEmpty { updateRobotInfo() }
    }

    func updateScreenOnBluetoothDisconnection() {
        robotInfo = ""
        robotName.text = "N/A"
        driveInfo.text = L10n.string("setup_drive_unknown")
        updateLogo(ackermann: false)
        showFeatures()
        showLink()
    }

    // MARK: Tests (each lasts about a second: the car's heartbeat then stops it)

    /// Arcade mix, positive steer to the left: left = throttle - steer,
    /// right = throttle + steer, each in -1...1 scaled to the wire's
    /// -255...255 PWM range.
    private func send(throttle: Double, steer: Double) {
        let left = max(-1, min(1, throttle - steer))
        let right = max(-1, min(1, throttle + steer))
        bluetooth.sendData(payload: "c" + String(Int(left * 255)) + "," + String(Int(right * 255)) + "\n")
    }

    @objc func forwardButton(_ sender: UIButton) { bluetooth.sendData(payload: "c192,192\n") }
    @objc func backwardButton(_ sender: UIButton) { bluetooth.sendData(payload: "c-192,-192\n") }
    @objc func stopButton(_ sender: UIButton) { bluetooth.sendData(payload: "c0,0\n") }
    @objc func steerLeft() { send(throttle: 0, steer: 1) }
    @objc func steerCentre() { send(throttle: 0, steer: 0) }
    @objc func steerRight() { send(throttle: 0, steer: -1) }

    @objc func openBluetoothSettings(_ sender: UIButton) {
        let next = storyboard?.instantiateViewController(withIdentifier: Strings.bluetoothScreen)
        if let next { navigationController?.pushViewController(next, animated: true) }
    }

    @objc func refreshTapped() {
        if isBluetoothConnected { updateRobotInfo() } else { updateScreenOnBluetoothDisconnection() }
    }

    func setupNavigationBarItem() {
        if UIImage(named: "back") != nil {
            let backNavigationIcon = (UIImage(named: "back")?.withRenderingMode(.alwaysOriginal))!
            let newBackButton = UIBarButtonItem(image: backNavigationIcon, title: Strings.robotInfo, target: self, action: #selector(RobotInfoFrame.back(sender:)), titleColor: Colors.navigationColor ?? .white)
            navigationItem.leftBarButtonItem = newBackButton
        }
        // Full avoidance settings: quick-disable, AVOID_MARGIN, AVOID_BACKUP_SPD.
        let avoid = UIBarButtonItem(
            image: UIImage(systemName: "shield.lefthalf.filled"),
            style: .plain,
            target: self,
            action: #selector(showAvoidanceSettings)
        )
        avoid.accessibilityLabel = L10n.string("avoid_settings")
        navigationItem.rightBarButtonItems = [avoid, sensorLinkItem]
    }

    @objc func showAvoidanceSettings() {
        let settings = AvoidanceSettingsController()
        settings.modalPresentationStyle = .pageSheet
        present(settings, animated: true)
    }

    @objc func back(sender: UIBarButtonItem) {
        _ = navigationController?.popViewController(animated: true)
    }
}

extension String {
    func index(from: Int) -> Index {
        self.index(startIndex, offsetBy: from)
    }

    func substring(from: Int) -> String {
        let fromIndex = index(from: from)
        return String(self[fromIndex...])
    }

    func substring(to: Int) -> String {
        let toIndex = index(from: to)
        return String(self[..<toIndex])
    }

    func substring(with r: Range<Int>) -> String {
        let startIndex = index(from: r.lowerBound)
        let endIndex = index(from: r.upperBound)
        return String(self[startIndex..<endIndex])
    }
}
