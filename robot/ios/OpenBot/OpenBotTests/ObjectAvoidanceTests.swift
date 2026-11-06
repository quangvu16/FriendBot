//
//  ObjectAvoidanceTests.swift
//  OpenBotTests
//
//  The kept avoidance-gate core: proximity octants, the ArduPilot Rover-style
//  avoidance law and the DriveGate wire chokepoint. Adapted from the earlier
//  ranging tests after the scan inputs were removed; the gate is fed
//  only by `setSectorsCm` (or never at all).
//

import XCTest
@testable import OpenBot

final class ProximityTests: XCTestCase {

    func testInitialStateIsNeverFed() {
        let p = Proximity()
        XCTAssertFalse(p.hasEverReported())
        XCTAssertEqual(p.dataState(), "never")
        XCTAssertEqual(p.ageMs(), proximityNeverFedAgeMs)
        for i in 0..<proximitySectors {
            XCTAssertEqual(p.sector(i), .infinity)
        }
    }

    func testIngestsEightSectorsInMeters() {
        let p = Proximity()
        XCTAssertTrue(p.setSectorsCm([100, 200, 300, 400, 500, 600, 700, 800]))
        XCTAssertTrue(p.hasEverReported())
        XCTAssertEqual(p.sector(0), 1.0, accuracy: 1e-9)
        XCTAssertEqual(p.sector(3), 4.0, accuracy: 1e-9)
        XCTAssertEqual(p.sector(7), 8.0, accuracy: 1e-9)
        XCTAssertEqual(p.sectors().count, proximitySectors)
    }

    func testZeroCentimetersMeansNoReturnNotAnObstacle() {
        let p = Proximity()
        XCTAssertTrue(p.setSectorsCm([0, 0, 0, 0, 0, 0, 0, 0]))
        for i in 0..<proximitySectors {
            XCTAssertEqual(p.sector(i), .infinity)
        }
    }

    func testRejectsBadReportsAndKeepsTheOldPicture() {
        let p = Proximity()
        XCTAssertTrue(p.setSectorsCm([100, 100, 100, 100, 100, 100, 100, 100]))
        XCTAssertFalse(p.setSectorsCm(nil))
        XCTAssertFalse(p.setSectorsCm([100, 100])) // short
        XCTAssertFalse(p.setSectorsCm([100, 100, 100, 100, 100, 100, 100, -5])) // negative
        XCTAssertFalse(p.setSectorsCm([100, 100, 100, 100, 100, 100, 100, .nan])) // NaN
        // The rejected reports must not have replaced the accepted one.
        XCTAssertEqual(p.sector(0), 1.0, accuracy: 1e-9)
    }

    func testFreshThenStale() {
        var now = 1000.0
        let p = Proximity(clock: { now })
        XCTAssertTrue(p.setSectorsCm([100, 100, 100, 100, 100, 100, 100, 100]))
        XCTAssertEqual(p.dataState(), "fresh")
        now += proximityStaleAfterMs + 1
        XCTAssertEqual(p.dataState(), "stale")
        XCTAssertEqual(p.ageMs(), proximityStaleAfterMs + 1, accuracy: 1e-9)
    }

    func testResetForgetsEverything() {
        let p = Proximity()
        p.setSectorsCm([100, 100, 100, 100, 100, 100, 100, 100])
        p.reset()
        XCTAssertFalse(p.hasEverReported())
        XCTAssertEqual(p.dataState(), "never")
        XCTAssertEqual(p.sector(0), .infinity)
    }

    func testSectorIndexIsBounded() {
        let p = Proximity()
        p.setSectorsCm([100, 100, 100, 100, 100, 100, 100, 100])
        XCTAssertEqual(p.sector(-1), .infinity)
        XCTAssertEqual(p.sector(proximitySectors), .infinity)
    }
}

final class ObjectAvoidanceTests: XCTestCase {

    /// Eight clear sectors: no return anywhere.
    private let clear = [Double](repeating: .infinity, count: proximitySectors)

    /// An obstacle `meters` away in the given sectors only.
    private func withObstacle(_ meters: Double, _ sectors: [ProximitySector]) -> [Double] {
        var s = clear
        for sector in sectors { s[sector.rawValue] = meters }
        return s
    }

    private func evaluate(left: Float = 0.7, right: Float = 0.7,
                          sectors: [Double], state: String = "fresh",
                          margin: Double = 1.0, backup: Double = 0,
                          enabled: Bool = true) -> AvoidanceDecision {
        ObjectAvoidance.evaluate(left: left, right: right, sectorMeters: sectors,
                                 state: state, marginMeters: margin,
                                 backupSpeed: backup, enabled: enabled)
    }

    func testClearRoadPassesThrough() {
        let d = evaluate(sectors: clear)
        XCTAssertEqual(d.left, 0.7, accuracy: 1e-6)
        XCTAssertEqual(d.right, 0.7, accuracy: 1e-6)
        XCTAssertFalse(d.blocked)
        XCTAssertFalse(d.isHolding)
    }

    func testObstacleInsideTheMarginBlocksForwardMotion() {
        let d = evaluate(sectors: withObstacle(0.5, [.front]))
        XCTAssertTrue(d.blocked)
        XCTAssertEqual(d.left + d.right, 0, accuracy: 1e-6) // throttle zeroed
    }

    func testBlockingKeepsTheSteering() {
        // The wheel split survives as +/- (left - right)/2: throttle zeroed,
        // wheels still pointed where the driver put them.
        let d = evaluate(left: 0.2, right: 0.8, sectors: withObstacle(0.5, [.frontLeft]))
        XCTAssertTrue(d.blocked)
        XCTAssertEqual(d.left, -0.3, accuracy: 1e-6)   // (0.2 - 0.8) / 2
        XCTAssertEqual(d.right, 0.3, accuracy: 1e-6)
    }

    func testObstacleBehindBlocksReverseOnly() {
        let rear = evaluate(left: -0.6, right: -0.6, sectors: withObstacle(0.5, [.back]))
        XCTAssertTrue(rear.blocked)
        let forward = evaluate(left: 0.6, right: 0.6, sectors: withObstacle(0.5, [.back]))
        XCTAssertFalse(forward.blocked) // rear returns do not stop forward motion
    }

    func testFrontObstacleDoesNotBlockReverse() {
        let d = evaluate(left: -0.6, right: -0.6, sectors: withObstacle(0.5, [.front]))
        XCTAssertFalse(d.blocked)
    }

    func testNearestObstacleIsReported() {
        let d = evaluate(sectors: withObstacle(0.7, [.frontRight]))
        XCTAssertTrue(d.blocked)
        XCTAssertEqual(d.nearestSector, ProximitySector.frontRight.rawValue)
        XCTAssertEqual(d.nearestMeters, 0.7, accuracy: 1e-9)
    }

    func testBackupSpeedReversesWhenTheRearIsClear() {
        let d = evaluate(sectors: withObstacle(0.5, [.front]), backup: 0.3)
        XCTAssertTrue(d.blocked)
        XCTAssertTrue(d.backing)
        XCTAssertEqual(d.left, -0.3, accuracy: 1e-6)
        XCTAssertEqual(d.right, -0.3, accuracy: 1e-6)
    }

    func testBackupIsSkippedWhenBothEndsAreBlocked() {
        let s = withObstacle(0.5, [.front, .back])
        let d = evaluate(sectors: s, backup: 0.3)
        XCTAssertTrue(d.blocked)
        XCTAssertFalse(d.backing) // never back into a wall
    }

    func testDisabledNeverBlocks() {
        let d = evaluate(sectors: withObstacle(0.2, [.front]), enabled: false)
        XCTAssertFalse(d.blocked)
    }

    func testZeroMarginNeverBlocks() {
        let d = evaluate(sectors: withObstacle(0.2, [.front]), margin: 0)
        XCTAssertFalse(d.blocked)
    }

    func testNeverFedStateNeverBlocks() {
        let d = evaluate(sectors: clear, state: "never")
        XCTAssertFalse(d.blocked)
    }

    func testStaleStateBlocksForwardButNotReverse() {
        let forward = evaluate(sectors: clear, state: "stale")
        XCTAssertTrue(forward.blocked)
        let reverse = evaluate(left: -0.5, right: -0.5, sectors: clear, state: "stale")
        XCTAssertFalse(reverse.blocked)
    }

    func testControlsAreClamped() {
        let d = evaluate(left: 5, right: -7, sectors: clear)
        XCTAssertEqual(d.left, 1, accuracy: 1e-6)
        XCTAssertEqual(d.right, -1, accuracy: 1e-6)
        XCTAssertEqual(ObjectAvoidance.clampControl(.nan), 0)
    }
}

final class DriveGateTests: XCTestCase {

    private func gate(backup: Double = 0) -> DriveGate {
        DriveGate(proximity: Proximity(), marginMeters: 1.0, backupSpeed: backup, enabled: true)
    }

    func testFreshObstacleHoldsThenClearRoadReleases() {
        let g = gate()
        let blocked = g.apply(left: 0.8, right: 0.8)
        // Nothing has ever been reported: "never" never blocks.
        XCTAssertFalse(blocked.blocked)

        g.proximity.setSectorsCm([50, 100, 100, 100, 100, 100, 100, 100]) // 0.5 m ahead
        let held = g.onProximityUpdate()
        XCTAssertTrue(held.blocked)
        XCTAssertEqual(g.statusText(), "Holding")

        g.proximity.setSectorsCm([500, 500, 500, 500, 500, 500, 500, 500]) // clear again
        let released = g.onProximityUpdate()
        XCTAssertFalse(released.blocked)
        XCTAssertEqual(g.statusText(), "Clear")
    }

    func testStoredControlSurvivesReEvaluations() {
        let g = gate()
        g.apply(left: 0.6, right: 0.6)
        XCTAssertEqual(g.control.left, 0.6, accuracy: 1e-6)
        XCTAssertEqual(g.control.right, 0.6, accuracy: 1e-6)
    }

    func testQuickDisableReleasesImmediately() {
        let g = gate()
        g.proximity.setSectorsCm([50, 50, 50, 50, 50, 50, 50, 50])
        let held = g.apply(left: 0.5, right: 0.5)
        XCTAssertTrue(held.blocked)
        let off = g.setEnabled(false)
        XCTAssertFalse(off.blocked)
        XCTAssertEqual(g.statusText(), "Off")
        let on = g.setEnabled(true)
        XCTAssertTrue(on.blocked) // the same request is held again
    }

    func testApplyWireRewritesOnlyDriveCommands() {
        let g = gate()
        g.proximity.setSectorsCm([50, 50, 50, 50, 500, 500, 500, 500]) // wall ahead
        XCTAssertEqual(g.applyWire("h500\n"), "h500\n")     // heartbeat untouched
        XCTAssertEqual(g.applyWire("tstop\n"), "tstop\n")   // text untouched
        XCTAssertEqual(g.applyWire("f\n"), "f\n")           // query untouched
        XCTAssertEqual(g.applyWire("garbage"), "garbage")
        let out = g.applyWire("c255,255\n", scale: 255)
        XCTAssertTrue(out.hasPrefix("c"))
        let values = out.dropFirst().split(separator: ",").compactMap { Double($0.trimmingCharacters(in: .whitespacesAndNewlines)) }
        XCTAssertEqual(values.count, 2)
        XCTAssertEqual(values[0] + values[1], 0, accuracy: 1.1) // throttle zeroed on the wire
    }

    func testApplyWirePassesClearCommandsThrough() {
        let g = gate()
        XCTAssertEqual(g.applyWire("c255,255\n"), "c255,255\n")
        XCTAssertEqual(g.applyWire("c0,0\n"), "c0,0\n")
    }

    func testStatusTextFollowsThePicture() {
        let g = gate()
        XCTAssertEqual(g.statusText(), "Idle")
        g.proximity.setSectorsCm([500, 500, 500, 500, 500, 500, 500, 500])
        g.apply(left: 0.5, right: 0.5)
        XCTAssertEqual(g.statusText(), "Clear")
    }

    func testBackupReportsBacking() {
        let g = gate(backup: 0.3)
        // Wall ahead (sectors 0-2), the whole rear arc clear.
        g.proximity.setSectorsCm([50, 50, 50, 500, 500, 500, 500, 500])
        let d = g.apply(left: 0.5, right: 0.5)
        XCTAssertTrue(d.backing)
        XCTAssertEqual(g.statusText(), "Backing")
    }

    func testSettingsRoundTrip() {
        let g = gate()
        let d = g.apply(settings: AvoidanceSettings(enabled: true, marginMeters: 2.0, backupSpeed: 0.2))
        XCTAssertEqual(g.marginMeters, 2.0, accuracy: 1e-9)
        XCTAssertEqual(g.backupSpeed, 0.2, accuracy: 1e-9)
        XCTAssertFalse(d.blocked) // nothing fed yet
        let settings = g.settings
        XCTAssertEqual(settings.marginMeters, 2.0, accuracy: 1e-9)
        XCTAssertEqual(settings.backupSpeed, 0.2, accuracy: 1e-9)
    }

    func testSettingsAreClamped() {
        let s = AvoidanceSettings(enabled: true, marginMeters: 99, backupSpeed: -3).clamped()
        XCTAssertEqual(s.marginMeters, AvoidanceSettings.marginRange.upperBound, accuracy: 1e-9)
        XCTAssertEqual(s.backupSpeed, AvoidanceSettings.backupRange.lowerBound, accuracy: 1e-9)
    }
}
