//
//  TiltDrive.swift
//  OpenBot
//
//  Tilt drive mode: "only use phone sensors to move". The phone's gravity
//  vector (Core Motion, ~30 Hz) steers the robot - tip the top edge forward to
//  drive, roll right to turn right - through the same wire path as every other
//  motion mode, so the speed scale, the avoidance gate, the STOP latch and the
//  250 ms resend cadence all apply.
//

import CoreMotion
import UIKit

final class TiltDrive {
    static let shared = TiltDrive()

    /// Gravity sampling rate.
    private static let sampleHz: Double = 30.0
    /// Re-send cadence - the same 250 ms every other motion path uses, which
    /// also feeds the firmware's control-timeout failsafe.
    private static let resendSeconds: Double = 0.25
    /// |tilt| below this is neutral; output is full at `fullTiltDegrees`.
    private static let deadZoneDegrees: Double = 4.0
    private static let fullTiltDegrees: Double = 35.0
    private static let hintShownKey = "driveTiltHintShown"

    private let motion = CMMotionManager()
    private let bluetooth = bluetoothDataController.shared
    private let gameController = GameController.shared
    private var resendTimer: Timer?
    private var lastLeft = 0
    private var lastRight = 0
    private(set) var isActive = false

    private init() {}

    /// Start driving by tilting: gravity updates plus a 250 ms resend.
    func start() {
        guard !isActive else { return }
        isActive = true
        lastLeft = 0
        lastRight = 0
        motion.deviceMotionUpdateInterval = 1.0 / Self.sampleHz
        motion.startDeviceMotionUpdates(using: .xArbitraryZVertical, to: .main) { [weak self] motion, _ in
            guard let self, let motion else { return }
            self.step(gravity: motion.gravity)
        }
        resendTimer?.invalidate()
        resendTimer = Timer.scheduledTimer(withTimeInterval: Self.resendSeconds, repeats: true) { [weak self] _ in
            self?.resend()
        }
    }

    /// Leaving the mode/screen stops the robot at once - never rely on the
    /// firmware failsafe to catch a dropped stream.
    func stop() {
        let wasActive = isActive
        isActive = false
        if motion.isDeviceMotionActive {
            motion.stopDeviceMotionUpdates()
        }
        resendTimer?.invalidate()
        resendTimer = nil
        if wasActive || lastLeft != 0 || lastRight != 0 {
            lastLeft = 0
            lastRight = 0
            bluetooth.sendData(payload: "c0,0\n")
        }
    }

    /// One gravity sample -> tilt angles -> arcade mix -> wire.
    private func step(gravity: CMAcceleration) {
        // Rotation-aware: portrait baseline, swap the axes when the display is
        // landscape. `currentOrientation` tracks the device orientation the
        // app already stores (see DeviceCurrentOrientation).
        let gx: Double
        let gy: Double
        switch currentOrientation {
        case .landscapeLeft:
            gx = -gravity.y
            gy = gravity.x
        case .landscapeRight:
            gx = gravity.y
            gy = -gravity.x
        case .portraitUpsideDown:
            gx = -gravity.x
            gy = -gravity.y
        default:
            gx = gravity.x
            gy = gravity.y
        }
        // Throttle = forward tilt of the screen's top edge, steering = roll
        // (tilt right = turn right). Both measured from flat, screen up.
        // The arcade mix takes positive steering to the left
        // (left = throttle - steering, right = throttle + steering), so a right
        // roll goes in as negative steering.
        let throttle = Self.curve(gy)
        let steering = Self.curve(-gx)
        let left = Self.clampUnit(throttle - steering)
        let right = Self.clampUnit(throttle + steering)
        send(left: left, right: right)
    }

    /// Dead-zone curve: |angle| < 4 deg is neutral, full output at 35 deg,
    /// linear in between. The gravity component is the sine of the tilt angle.
    private static func curve(_ component: Double) -> Double {
        let clamped = max(-1, min(1, component))
        let degrees = abs(asin(clamped) * 180.0 / .pi)
        guard degrees >= deadZoneDegrees else { return 0 }
        let out = min(1, (degrees - deadZoneDegrees) / (fullTiltDegrees - deadZoneDegrees))
        return clamped < 0 ? -out : out
    }

    private static func clampUnit(_ value: Double) -> Double {
        max(-1, min(1, value))
    }

    /// Send one mix, with the same speed scale as the other drive modes.
    /// Neutral (`c0,0`) goes out immediately on change - don't rely on the
    /// firmware failsafe.
    private func send(left: Double, right: Double) {
        let scale = Double(gameController.selectedSpeedMode.rawValue)
        let l = Int((left * scale).rounded())
        let r = Int((right * scale).rounded())
        guard l != lastLeft || r != lastRight else { return }
        lastLeft = l
        lastRight = r
        bluetooth.sendData(payload: "c\(l),\(r)\n")
        NotificationCenter.default.post(name: .updateSpeedLabel, object: String(l) + "," + String(r))
    }

    /// 250 ms resend of the held command, matching the other motion paths.
    private func resend() {
        bluetooth.sendData(payload: "c\(lastLeft),\(lastRight)\n")
    }

    // MARK: - One-time hint

    /// "Tilt the phone to drive", returned the first time tilt mode is entered
    /// only (persisted across launches).
    static func hintIfFirstTime() -> String? {
        let defaults = UserDefaults.standard
        guard !defaults.bool(forKey: hintShownKey) else { return nil }
        defaults.set(true, forKey: hintShownKey)
        return L10n.string("drive_tilt_hint")
    }
}
