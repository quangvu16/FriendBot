//
//  FollowColourController.swift
//  OpenBot
//
//  Follow a Coloured Object — port of stage 1 of Kirstin813/L4-Individual-Project.
//
//  The original scans the camera image for the first pixel within an RGB tolerance of the
//  target colour and drives with three zones on its x position. Here the match is a centroid
//  over all matching pixels (less noisy than the first match) but the control law is kept as
//  the original: left third turns left, middle third drives forward, right third turns right.
//  Tap the preview to sample the colour to follow.
//

import UIKit
import AVFoundation

final class FollowColourController: VisionModeController {

    private static let defaultTolerance = 15
    private static let minMatches = 8
    private static let scanStride = 3

    private var tolerance = FollowColourController.defaultTolerance
    private var targetColor: (Int, Int, Int)?
    /// Buffer-space position of a pending tap, sampled at the next frame.
    private var pendingSample: CGPoint?

    override func guideId() -> String? { GuideController.guideColour }

    override func setupModeControls() {
        overlay.guideFractions = [1.0 / 3.0, 2.0 / 3.0]
        addStepper(
            label: Strings.followColourTolerance,
            min: 5, max: 60, step: 5, initial: FollowColourController.defaultTolerance
        ) { [weak self] value in
            self?.tolerance = value
        }
        let tap = UITapGestureRecognizer(target: self, action: #selector(onTap(_:)))
        overlay.addGestureRecognizer(tap)
        setStatus(Strings.followColourPickHint)
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
                targetColor = pixels.rgb(x: x, y: y)
                if let color = targetColor {
                    DispatchQueue.main.async {
                        self.overlay.swatchColor = UIColor(
                            red: CGFloat(color.0) / 255, green: CGFloat(color.1) / 255,
                            blue: CGFloat(color.2) / 255, alpha: 1)
                    }
                }
                setStatus(Strings.followColourPickHint)
            }

            guard let target = targetColor else {
                setStatus(Strings.followColourNone)
                showBoxes([])
                sendControl(Control())
                return
            }

            var count = 0
            var sumX = 0
            var sumY = 0
            var minX = Int.max, minY = Int.max, maxX = Int.min, maxY = Int.min

            var y = 0
            while y < pixels.height {
                var x = 0
                while x < pixels.width {
                    let (r, g, b) = pixels.rgb(x: x, y: y)
                    if abs(r - target.0) <= tolerance,
                       abs(g - target.1) <= tolerance,
                       abs(b - target.2) <= tolerance {
                        count += 1
                        sumX += x
                        sumY += y
                        minX = min(minX, x); maxX = max(maxX, x)
                        minY = min(minY, y); maxY = max(maxY, y)
                    }
                    x += FollowColourController.scanStride
                }
                y += FollowColourController.scanStride
            }

            if count < FollowColourController.minMatches {
                // The original keeps driving the last action when the object disappears;
                // a real car is safer stopping, so the guide says the car stops on loss.
                setStatus(Strings.followColourNone)
                showBoxes([])
                sendControl(Control())
                return
            }

            let centroid = CGPoint(x: CGFloat(sumX) / CGFloat(count), y: CGFloat(sumY) / CGFloat(count))
            let box = VisionOverlayView.Box(
                rect: CGRect(x: minX, y: minY, width: maxX - minX, height: maxY - minY),
                label: Strings.tileFollowColour, color: .green, width: 3)
            showBoxes([box])

            // Three-zone control on the target's displayed x position, like the original sketch.
            let viewPoint = overlay.frameToView(centroid)
            let fitted = overlay.fittedFrameRect
            let third = fitted.width / 3
            if viewPoint.x < fitted.minX + third {
                setStatus("◀ " + Strings.visionRunning)
                sendControl(Control(left: -1, right: 1))
            } else if viewPoint.x > fitted.maxX - third {
                setStatus("▶ " + Strings.visionRunning)
                sendControl(Control(left: 1, right: -1))
            } else {
                setStatus("▲ " + Strings.visionRunning)
                sendControl(Control(left: 1, right: 1))
            }
        }
    }

    private func clamp(_ v: Int, _ minV: Int, _ maxV: Int) -> Int {
        Swift.max(minV, Swift.min(maxV, v))
    }
}
