//
//  AvoidObjectController.swift
//  OpenBot
//
//  Object Avoidance — the fourth stage of Kirstin813/L4-Individual-Project never finished its
//  drive policy (it only drew a MediaPipe Objectron box), so the behaviour here is designed
//  fresh and documented in the in-app guide: tap the obstacle in the camera view to mark its
//  colour. While the obstacle is far away (a small part of the view) the car drives forward;
//  when it fills too much of the view the car stops and pivots toward the clearer side.
//

import UIKit
import AVFoundation

final class AvoidObjectController: VisionModeController {

    private static let tolerance = 20
    private static let minMatches = 8
    private static let scanStride = 3
    private static let defaultStopPercent = 10

    private var obstacleColor: (Int, Int, Int)?
    private var stopPercent = AvoidObjectController.defaultStopPercent
    /// Buffer-space position of a pending tap, sampled at the next frame.
    private var pendingSample: CGPoint?

    override func guideId() -> String? { GuideController.guideAvoidance }

    override func setupModeControls() {
        addStepper(
            label: Strings.avoidThreshold,
            min: 2, max: 40, step: 2, initial: AvoidObjectController.defaultStopPercent
        ) { [weak self] value in
            self?.stopPercent = value
        }
        let tap = UITapGestureRecognizer(target: self, action: #selector(onTap(_:)))
        overlay.addGestureRecognizer(tap)
        setStatus(Strings.avoidPickHint)
    }

    @objc private func onTap(_ gesture: UITapGestureRecognizer) {
        let point = gesture.location(in: overlay)
        pendingSample = overlay.viewToFrame(point)
    }

    override func processVisionFrame(_ pixelBuffer: CVPixelBuffer) {
        _ = withFramePixels(pixelBuffer) { pixels in
            if let sample = pendingSample {
                pendingSample = nil
                let x = clamp(Int(sample.x), 0, pixels.width - 1)
                let y = clamp(Int(sample.y), 0, pixels.height - 1)
                obstacleColor = pixels.rgb(x: x, y: y)
                if let color = obstacleColor {
                    DispatchQueue.main.async {
                        self.overlay.swatchColor = UIColor(
                            red: CGFloat(color.0) / 255, green: CGFloat(color.1) / 255,
                            blue: CGFloat(color.2) / 255, alpha: 1)
                    }
                }
                setStatus(Strings.avoidPickHint)
            }

            guard let target = obstacleColor else {
                setStatus(Strings.avoidPickHint)
                showBoxes([])
                sendControl(Control())
                return
            }

            var count = 0
            var leftCount = 0
            var minX = Int.max, minY = Int.max, maxX = Int.min, maxY = Int.min
            let half = pixels.width / 2

            var y = 0
            while y < pixels.height {
                var x = 0
                while x < pixels.width {
                    let (r, g, b) = pixels.rgb(x: x, y: y)
                    if abs(r - target.0) <= AvoidObjectController.tolerance,
                       abs(g - target.1) <= AvoidObjectController.tolerance,
                       abs(b - target.2) <= AvoidObjectController.tolerance {
                        count += 1
                        if x < half { leftCount += 1 }
                        minX = min(minX, x); maxX = max(maxX, x)
                        minY = min(minY, y); maxY = max(maxY, y)
                    }
                    x += AvoidObjectController.scanStride
                }
                y += AvoidObjectController.scanStride
            }

            let scanned = max(
                1,
                (pixels.height / AvoidObjectController.scanStride)
                    * (pixels.width / AvoidObjectController.scanStride))
            let percent = 100 * count / scanned

            if count < AvoidObjectController.minMatches || percent < stopPercent {
                // Obstacle far away (or out of view): drive on.
                setStatus(Strings.avoidStatusClear)
                showBoxes([])
                sendControl(Control(left: 1, right: 1))
                return
            }

            showBoxes([
                VisionOverlayView.Box(
                    rect: CGRect(x: minX, y: minY, width: maxX - minX, height: maxY - minY),
                    label: Strings.tileObjectAvoidance, color: .red, width: 3)
            ])

            // Too close: pivot toward the clearer side of the view.
            let rightCount = count - leftCount
            if leftCount <= rightCount {
                setStatus("◀ " + Strings.avoidStatusBlocked)
                sendControl(Control(left: -1, right: 1))
            } else {
                setStatus("▶ " + Strings.avoidStatusBlocked)
                sendControl(Control(left: 1, right: -1))
            }
        }
    }

    private func clamp(_ v: Int, _ minV: Int, _ maxV: Int) -> Int {
        Swift.max(minV, Swift.min(maxV, v))
    }
}
