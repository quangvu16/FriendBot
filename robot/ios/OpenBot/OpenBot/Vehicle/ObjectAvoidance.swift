//
//  ObjectAvoidance.swift
//  OpenBot
//
//  ArduPilot Rover-style Simple Object Avoidance: the avoidance law, the
//  `DriveGate` chokepoint every drive command passes through, its proximity
//  state and the persisted knobs, plus the quick-disable bar button and the
//  settings sheet. Kept from the earlier ranging-core file after the scan
//  sensors and scan views were removed: the gate stays on the wire path of
//  every motion mode, its proximity state is simply never fed any more.
//

import Foundation
import UIKit

// MARK: - Proximity

/// Number of proximity octants the avoidance law checks.
let proximitySectors = 8

/// How old the last report may be before it is considered stale.
let proximityStaleAfterMs = 500.0

/// Age reported before any reading has arrived.
let proximityNeverFedAgeMs = 1000.0

/// The eight proximity octants the avoidance law uses.
///
/// The layout is the MAVLink `DISTANCE_SENSOR` octant arrangement that
/// ArduPilot's proximity library uses: sector 0 is straight ahead and the
/// numbering goes clockwise, one sector every 45 degrees::
///
///            7   0   1        0 = front        4 = back
///          6   +   2          1 = front-right  5 = back-left
///            5   4   3        2 = right        6 = left
///                              3 = back-right   7 = front-left
///
/// **Units and the zero convention.** Distances are ingested in centimetres
/// and converted on ingest: metres internally, and a 0 becomes `.infinity` so
/// an empty sector can never be mistaken for an obstacle sitting on top of the
/// robot. Staleness is signalled by reports simply not arriving, which is what
/// `dataState` watches for.
final class Proximity {
    private var sectorsM = [Double](repeating: .infinity, count: proximitySectors)
    private var timestampMs: Double = 0
    private let clock: () -> Double

    /// Injectable clock so the tests can age the reading without sleeping.
    init(clock: @escaping () -> Double = monotonicMs) {
        self.clock = clock
    }

    /// Ingest one report. `sectorCm` is eight octant distances in centimetres;
    /// `0` means "no return" and is stored as infinite.
    ///
    /// Returns true if the report was accepted. A nil, short or otherwise
    /// unusable list is rejected and the previous reading is left alone rather
    /// than being replaced with zeros that look like clear road.
    @discardableResult
    func setSectorsCm(_ sectorCm: [Double]?) -> Bool {
        guard let sectorCm, sectorCm.count == proximitySectors else { return false }
        for cm in sectorCm {
            guard cm.isFinite, cm >= 0 else { return false }
        }
        for (i, cm) in sectorCm.enumerated() {
            // 0 cm means "nothing measured here", not "an obstacle at zero".
            // A real return at 0 is outside the sensor's minimum range anyway,
            // so the two cannot be confused.
            sectorsM[i] = cm == 0 ? .infinity : cm / 100.0
        }
        timestampMs = clock()
        return true
    }

    /// The eight octant distances in metres, clear sectors as `.infinity`.
    func sectors() -> [Double] {
        sectorsM
    }

    /// Distance of one sector in metres, or `.infinity` when there is no return.
    func sector(_ index: Int) -> Double {
        guard index >= 0, index < proximitySectors else { return .infinity }
        return sectorsM[index]
    }

    /// Milliseconds since the last accepted report, or `neverFedAgeMs` before it.
    func ageMs() -> Double {
        timestampMs == 0 ? proximityNeverFedAgeMs : clock() - timestampMs
    }

    /// True once at least one report has been ingested.
    func hasEverReported() -> Bool {
        timestampMs > 0
    }

    /// How fresh the picture is: "never", "stale" or "fresh".
    ///
    /// "never" never blocks driving - a robot with no ranging sensor must stay
    /// drivable. "stale" blocks forward motion - a sensor that died mid-drive
    /// must not leave the robot driving blind. "fresh" can be acted on.
    func dataState() -> String {
        if timestampMs == 0 { return "never" }
        return ageMs() > proximityStaleAfterMs ? "stale" : "fresh"
    }

    /// Reset to "nothing has ever been measured".
    func reset() {
        sectorsM = [Double](repeating: .infinity, count: proximitySectors)
        timestampMs = 0
    }
}

// MARK: - Object avoidance

/// Sector indices, clockwise from the front. See `Proximity`.
enum ProximitySector: Int, CaseIterable {
    case front = 0
    case frontRight = 1
    case right = 2
    case backRight = 3
    case back = 4
    case backLeft = 5
    case left = 6
    case frontLeft = 7
}

/// The front arc checked against forward motion: front-left, front, front-right.
///
/// A 135 degree sweep - a finer-grained version of the forward quadrant
/// ArduPilot checks, and it covers the corners an Ackermann car swings through
/// as it steers.
let frontArc: [ProximitySector] = [.frontLeft, .front, .frontRight]

/// The rear arc checked against reverse motion, the mirror of `frontArc`.
let rearArc: [ProximitySector] = [.backLeft, .back, .backRight]

/// What the object-avoidance law decided.
///
/// `left` and `right` are the commands to actually drive, in `[-1, 1]`.
struct AvoidanceDecision {
    let left: Float
    let right: Float
    /// True when an obstacle vetoed motion the driver or autopilot actually asked for.
    let blocked: Bool
    /// True when the robot is actively reversing to restore the margin.
    let backing: Bool
    /// Sector index of the nearest obstacle in the checked arc, or -1 when clear.
    let nearestSector: Int
    /// Distance to that obstacle in metres, or `.infinity` when clear.
    let nearestMeters: Double

    /// True when this decision is holding the robot back, stopped or backing.
    var isHolding: Bool {
        blocked || backing
    }

    static let passthrough = AvoidanceDecision(
        left: 0, right: 0, blocked: false, backing: false,
        nearestSector: -1, nearestMeters: .infinity
    )
}

/// ArduPilot Rover-style Simple Object Avoidance.
///
/// The model follows ArduPilot's *Rover* behaviour rather than Copter's:
///
/// - **Rover always stops.** Copter's `AVOID_BEHAVE` (Stop vs Slide) has no Rover
///   equivalent - there is no sliding sideways past an obstacle on a car.
/// - **Applies in every mode except manual.** ArduPilot applies this in the
///   firmware's navigation stack so no flight mode escapes it. Here the
///   equivalent chokepoint is the caller's drive path.
/// - **`AVOID_MARGIN`** is the standoff at which the vehicle stops, in metres.
/// - **`AVOID_BACKUP_SPD`** actively backs away to restore the margin. `0`
///   disables it, matching ArduPilot's default of 0.
/// - **"SAFETY FIRST!"** ArduPilot insists on a quick-disable switch
///   (`RCx_OPTION = 40`) before first use. That is the `enabled` argument here.
///
/// Two deliberate deviations from ArduPilot, both toward stopping rather than
/// driving:
///
/// - A **stale** sensor (reports arrived, then stopped) blocks forward motion.
///   ArduPilot simply fails to avoid when it has no data; for a robot with no
///   other ranging that means driving blind, which is the one outcome the
///   feature exists to prevent. Reverse stays available so the driver can back
///   out. A sensor that has **never** reported is treated as "not fitted" and
///   never blocks - otherwise a robot without a ranging sensor would be
///   undrivable.
/// - Blocking zeroes the throttle but **keeps the steering**, so the wheels stay
///   pointed where the driver put them while the robot is held.
enum ObjectAvoidance {
    static func evaluate(
        left: Float,
        right: Float,
        sectorMeters: [Double],
        state: String,
        marginMeters: Double,
        backupSpeed: Double,
        enabled: Bool
    ) -> AvoidanceDecision {
        let l = clampControl(left)
        let r = clampControl(right)

        if !enabled || marginMeters <= 0 || state == "never" {
            return AvoidanceDecision(
                left: l, right: r, blocked: false, backing: false,
                nearestSector: -1, nearestMeters: .infinity
            )
        }

        if state == "stale" {
            // The last picture we had is too old to trust. A frozen "clear road"
            // is exactly the reading that drives a robot into a wall, so block
            // the direction we can no longer see. Reverse stays open - backing
            // out of trouble is what the driver wants.
            if throttle(l, r) > 0 {
                return stopKeepingSteering(l, r, nearestSector: -1, nearestMeters: .infinity)
            }
            return AvoidanceDecision(
                left: l, right: r, blocked: false, backing: false,
                nearestSector: -1, nearestMeters: .infinity
            )
        }

        let t = throttle(l, r)
        if t > 0 {
            let obstacle = nearest(sectorMeters, arc: frontArc)
            if obstacle.meters < marginMeters {
                if backupSpeed > 0 {
                    let rear = nearest(sectorMeters, arc: rearArc)
                    if rear.meters >= marginMeters {
                        // ArduPilot's AVOID_BACKUP_SPD: actively back away to
                        // restore the margin rather than sitting against the
                        // barrier. Only when the way behind is clear - backing
                        // into a wall to get away from a wall is not an
                        // improvement.
                        let b = clampControl(Float(backupSpeed))
                        return AvoidanceDecision(
                            left: -b, right: -b, blocked: true, backing: true,
                            nearestSector: obstacle.sector, nearestMeters: obstacle.meters
                        )
                    }
                }
                return stopKeepingSteering(
                    l, r, nearestSector: obstacle.sector, nearestMeters: obstacle.meters
                )
            }
        } else if t < 0 {
            let obstacle = nearest(sectorMeters, arc: rearArc)
            if obstacle.meters < marginMeters {
                return stopKeepingSteering(
                    l, r, nearestSector: obstacle.sector, nearestMeters: obstacle.meters
                )
            }
        }

        return AvoidanceDecision(
            left: l, right: r, blocked: false, backing: false,
            nearestSector: -1, nearestMeters: .infinity
        )
    }

    /// Throttle of a tank-style pair: the same mix the firmware uses to drive the ESC.
    private static func throttle(_ left: Float, _ right: Float) -> Float {
        left + right
    }

    /// Zero the throttle but keep the steering angle the driver asked for.
    ///
    /// With throttle 0 and `steering = left - right` preserved, the two tracks
    /// become `+/- steering/2` - the wheels stay pointed, the robot does not move.
    private static func stopKeepingSteering(
        _ left: Float, _ right: Float, nearestSector: Int, nearestMeters: Double
    ) -> AvoidanceDecision {
        let steering = left - right
        let half = clampControl(steering / 2.0)
        return AvoidanceDecision(
            left: half, right: -half, blocked: true, backing: false,
            nearestSector: nearestSector, nearestMeters: nearestMeters
        )
    }

    /// Nearest return across the given sectors.
    ///
    /// A sector with no return is infinite here, so a clear arc never looks like
    /// an obstacle sitting at zero.
    private static func nearest(
        _ sectorMeters: [Double], arc: [ProximitySector]
    ) -> (sector: Int, meters: Double) {
        var bestSector = -1
        var best = Double.infinity
        guard sectorMeters.count >= proximitySectors else { return (bestSector, best) }
        for sector in arc {
            let index = sector.rawValue
            guard index >= 0, index < sectorMeters.count else { continue }
            let d = sectorMeters[index]
            guard d > 0, d.isFinite else { continue }
            if d < best {
                best = d
                bestSector = index
            }
        }
        return (bestSector, best)
    }

    static func clampControl(_ value: Float) -> Float {
        guard value.isFinite else { return 0 }
        return max(-1, min(1, value))
    }
}

// MARK: - DriveGate

/// The single chokepoint every drive command passes through.
///
/// ArduPilot applies Simple Object Avoidance inside the navigation stack so no
/// flight mode escapes it; on this robot the equivalent is the one place every
/// caller already uses to reach the motors.
///
/// `control` is what the driver or autopilot asked for; `lastDecision` is what
/// actually went to the wheels after the law had its say. Keeping both is what
/// lets a cleared blockage release without the driver re-moving the stick:
/// every new proximity report re-evaluates the stored request.
final class DriveGate {
    let proximity: Proximity
    var marginMeters: Double
    var backupSpeed: Double
    /// The quick-disable switch, ArduPilot's `RCx_OPTION = 40`.
    var enabled: Bool

    /// The last request the driver or autopilot made, before avoidance.
    private(set) var control: (left: Float, right: Float) = (0, 0)
    private(set) var lastDecision = AvoidanceDecision.passthrough

    init(
        proximity: Proximity = Proximity(),
        marginMeters: Double = 1.0,
        backupSpeed: Double = 0,
        enabled: Bool = true
    ) {
        self.proximity = proximity
        self.marginMeters = marginMeters
        self.backupSpeed = backupSpeed
        self.enabled = enabled
    }

    /// Run one control request through the law and return what to actually drive.
    @discardableResult
    func apply(left: Float, right: Float) -> AvoidanceDecision {
        control = (left, right)
        lastDecision = ObjectAvoidance.evaluate(
            left: left,
            right: right,
            sectorMeters: proximity.sectors(),
            state: proximity.dataState(),
            marginMeters: marginMeters,
            backupSpeed: backupSpeed,
            enabled: enabled
        )
        return lastDecision
    }

    /// Toggle the quick-disable switch and re-evaluate the stored request.
    ///
    /// Returns the new decision so a caller can re-send immediately without
    /// waiting for the next stick move.
    @discardableResult
    func setEnabled(_ enabled: Bool) -> AvoidanceDecision {
        self.enabled = enabled
        return apply(left: control.left, right: control.right)
    }

    /// Re-evaluate after a new proximity report.
    ///
    /// Called whenever proximity data changes so a cleared blockage releases the
    /// robot without the driver re-moving the stick.
    @discardableResult
    func onProximityUpdate() -> AvoidanceDecision {
        apply(left: control.left, right: control.right)
    }

    /// One-line status for a HUD, matching the Android app's wording.
    func statusText() -> String {
        if !enabled { return "Off" }
        if !proximity.hasEverReported() { return "Idle" }
        if proximity.dataState() == "stale" { return "Blind" }
        if lastDecision.backing { return "Backing" }
        if lastDecision.blocked { return "Holding" }
        return "Clear"
    }

    /// Apply the law to an OpenBot wire command `c<left>,<right>`.
    ///
    /// Only `c` payloads are touched; everything else passes through untouched.
    /// Values on the wire are integers in `[-255, 255]` (the firmware's PWM
    /// scale), so they are normalised to `[-1, 1]` for the law and scaled back
    /// out afterwards. Returns the rewritten payload, or the original when it
    /// is not a drive command.
    func applyWire(_ payload: String, scale: Double = 255) -> String {
        guard payload.first == "c", scale > 0 else { return payload }
        let body = payload.dropFirst()
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        let parts = trimmed.split(separator: ",")
        guard parts.count == 2,
              let leftValue = Double(parts[0]),
              let rightValue = Double(parts[1]
                  .trimmingCharacters(in: CharacterSet(charactersIn: "\r\n")))
        else {
            return payload
        }
        let decision = apply(
            left: Float(leftValue / scale),
            right: Float(rightValue / scale)
        )
        let leftOut = Int((decision.left * Float(scale)).rounded())
        let rightOut = Int((decision.right * Float(scale)).rounded())
        return "c\(leftOut),\(rightOut)\n"
    }
}

// MARK: - Clock

/// Milliseconds on a clock that never jumps. Wall-clock time (`Date()`) can step
/// backwards or forwards when the phone syncs its time, which would make a stale
/// reading look fresh - or a fresh one stale - for however long the step was.
func monotonicMs() -> Double {
    ProcessInfo.processInfo.systemUptime * 1000.0
}

// MARK: - Persisted settings

/// The three avoidance knobs, persisted so they survive a relaunch.
///
/// Same names and meaning as ArduPilot: `AVOID_MARGIN` is the standoff in
/// metres, `AVOID_BACKUP_SPD` the reverse command used to back away (0 turns it
/// off) and `enabled` the quick-disable switch (`RCx_OPTION = 40`).
struct AvoidanceSettings: Equatable {
    static let marginRange: ClosedRange<Double> = 0.3...3.0
    static let backupRange: ClosedRange<Double> = 0.0...0.5

    var enabled: Bool
    var marginMeters: Double
    var backupSpeed: Double

    static let defaults = AvoidanceSettings(enabled: true, marginMeters: 1.0, backupSpeed: 0)

    private static let enabledKey = "objectAvoidanceEnabled"
    private static let marginKey = "objectAvoidanceMargin"
    private static let backupKey = "objectAvoidanceBackup"

    static func load(from defaults: UserDefaults = .standard) -> AvoidanceSettings {
        var settings = AvoidanceSettings.defaults
        if defaults.object(forKey: enabledKey) != nil {
            settings.enabled = defaults.bool(forKey: enabledKey)
        }
        if defaults.object(forKey: marginKey) != nil {
            settings.marginMeters = defaults.double(forKey: marginKey)
        }
        if defaults.object(forKey: backupKey) != nil {
            settings.backupSpeed = defaults.double(forKey: backupKey)
        }
        return settings.clamped()
    }

    func save(to defaults: UserDefaults = .standard) {
        let value = clamped()
        defaults.set(value.enabled, forKey: AvoidanceSettings.enabledKey)
        defaults.set(value.marginMeters, forKey: AvoidanceSettings.marginKey)
        defaults.set(value.backupSpeed, forKey: AvoidanceSettings.backupKey)
    }

    /// Values pulled back into the ranges the settings screen offers, so a
    /// hand-edited or corrupt preference can never disable the law by accident.
    func clamped() -> AvoidanceSettings {
        AvoidanceSettings(
            enabled: enabled,
            marginMeters: min(max(marginMeters.isFinite ? marginMeters : 1.0,
                                  AvoidanceSettings.marginRange.lowerBound),
                              AvoidanceSettings.marginRange.upperBound),
            backupSpeed: min(max(backupSpeed.isFinite ? backupSpeed : 0,
                                 AvoidanceSettings.backupRange.lowerBound),
                             AvoidanceSettings.backupRange.upperBound)
        )
    }
}

extension DriveGate {
    /// The gate's current knobs as a settings value.
    var settings: AvoidanceSettings {
        AvoidanceSettings(enabled: enabled, marginMeters: marginMeters, backupSpeed: backupSpeed)
    }

    /// Apply new knobs and re-evaluate the stored request.
    ///
    /// Returns the new decision so the caller can re-send immediately - a car
    /// held at a barrier resumes the moment the switch goes off, and stops the
    /// moment it goes on, without waiting for the next stick move.
    @discardableResult
    func apply(settings: AvoidanceSettings) -> AvoidanceDecision {
        let value = settings.clamped()
        enabled = value.enabled
        marginMeters = value.marginMeters
        backupSpeed = value.backupSpeed
        return onProximityUpdate()
    }
}

// MARK: - Object avoidance controls

/// The quick-disable switch as a navigation bar button: one tap flips it.
///
/// ArduPilot's "SAFETY FIRST!" point is that the switch must be in reach while
/// the vehicle is moving, not buried in settings, so every screen that drives
/// the car puts this in its bar. The title always says the current state.
final class AvoidanceToggleItem: UIBarButtonItem {
    private let bluetooth = bluetoothDataController.shared

    override init() {
        super.init()
        target = self
        action = #selector(toggle)
        refresh()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        target = self
        action = #selector(toggle)
        refresh()
    }

    func refresh() {
        let on = bluetooth.driveGate.enabled
        title = L10n.string(on ? "avoid_on" : "avoid_off")
        tintColor = on ? .systemGreen : .systemRed
        accessibilityLabel = L10n.string("avoid_title")
    }

    @objc private func toggle() {
        var settings = bluetooth.driveGate.settings
        settings.enabled.toggle()
        bluetooth.updateObjectAvoidance(settings)
        refresh()
    }
}

/// Settings sheet for the three ArduPilot knobs: the quick-disable switch,
/// `AVOID_MARGIN` and `AVOID_BACKUP_SPD`. Changes apply and persist at once.
final class AvoidanceSettingsController: UIViewController {
    private let bluetooth = bluetoothDataController.shared
    private let enabledSwitch = UISwitch()
    private let marginSlider = UISlider()
    private let marginValue = UILabel()
    private let backupSlider = UISlider()
    private let backupValue = UILabel()

    /// Called after every change, so the presenting screen can refresh its own
    /// toggle and margin ring.
    var onChange: (() -> Void)?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground

        let title = UILabel()
        title.text = L10n.string("avoid_title")
        title.font = .systemFont(ofSize: 20, weight: .semibold)

        let note = UILabel()
        note.text = L10n.string("avoid_note")
        note.font = .systemFont(ofSize: 13)
        note.textColor = .secondaryLabel
        note.numberOfLines = 0

        let settings = bluetooth.driveGate.settings
        enabledSwitch.isOn = settings.enabled
        enabledSwitch.addTarget(self, action: #selector(changed), for: .valueChanged)

        marginSlider.minimumValue = Float(AvoidanceSettings.marginRange.lowerBound)
        marginSlider.maximumValue = Float(AvoidanceSettings.marginRange.upperBound)
        marginSlider.value = Float(settings.marginMeters)
        marginSlider.addTarget(self, action: #selector(changed), for: .valueChanged)

        backupSlider.minimumValue = Float(AvoidanceSettings.backupRange.lowerBound)
        backupSlider.maximumValue = Float(AvoidanceSettings.backupRange.upperBound)
        backupSlider.value = Float(settings.backupSpeed)
        backupSlider.addTarget(self, action: #selector(changed), for: .valueChanged)

        let stack = UIStackView(arrangedSubviews: [
            title,
            row(L10n.string("avoid_enabled"), enabledSwitch),
            row(L10n.string("avoid_margin"), marginValue),
            marginSlider,
            row(L10n.string("avoid_backup"), backupValue),
            backupSlider,
            note,
        ])
        stack.axis = .vertical
        stack.spacing = 14
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -20),
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 24),
        ])
        refreshLabels()

        if let sheet = sheetPresentationController {
            sheet.detents = [.medium(), .large()]
            sheet.prefersGrabberVisible = true
        }
    }

    private func row(_ text: String, _ trailing: UIView) -> UIStackView {
        let label = UILabel()
        label.text = text
        label.font = .systemFont(ofSize: 16)
        let row = UIStackView(arrangedSubviews: [label, trailing])
        row.axis = .horizontal
        row.distribution = .equalSpacing
        row.alignment = .center
        return row
    }

    @objc private func changed() {
        // Snap to the steps the Android sliders use: 0.1 m and 0.05.
        let margin = (Double(marginSlider.value) * 10).rounded() / 10
        let backup = (Double(backupSlider.value) * 20).rounded() / 20
        bluetooth.updateObjectAvoidance(AvoidanceSettings(
            enabled: enabledSwitch.isOn,
            marginMeters: margin,
            backupSpeed: backup
        ))
        refreshLabels()
        onChange?()
    }

    private func refreshLabels() {
        let settings = bluetooth.driveGate.settings
        marginValue.text = String(format: "%.1f m", settings.marginMeters)
        backupValue.text = settings.backupSpeed <= 0
            ? L10n.string("avoid_backup_off")
            : String(format: "%.0f%%", settings.backupSpeed * 100)
    }
}
