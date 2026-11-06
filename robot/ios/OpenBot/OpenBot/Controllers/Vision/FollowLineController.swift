//
//  FollowLineController.swift
//  OpenBot
//
//  Follow a Black Line — port of stage 3 of Kirstin813/L4-Individual-Project.
//
//  Three virtual sensors along the bottom of the camera image: the frame is split into
//  thirds, each sensor is a strip along the bottom edge, and a sensor counts as "black"
//  when its mean greyscale brightness is under 90/255. Control follows the priority
//  centre → left → right; no sensor black stops the car. Point the camera down at a
//  black tape circuit.
//

import UIKit
import AVFoundation

final class FollowLineController: VisionModeController {

    private static let defaultThreshold = 90
    private static let sensorCount = 3
    /// Sensor strip height as a fraction of the fitted preview (L4 used 50 px of ~480).
    private static let sensorHeightFraction: CGFloat = 0.12
    private static let scanStride = 2

    private var blackThreshold = FollowLineController.defaultThreshold

    override func guideId() -> String? { GuideController.guideLine }

    override func setupModeControls() {
        addStepper(
            label: Strings.followLineThreshold,
            min: 30, max: 150, step: 10, initial: FollowLineController.defaultThreshold
        ) { [weak self] value in
            self?.blackThreshold = value
        }
        setStatus(Strings.followLineHint)
    }

    override func processVisionFrame(_ pixelBuffer: CVPixelBuffer) {
        _ = withFramePixels(pixelBuffer) { pixels in
            // Three sensors along the bottom edge of the preview: map them to buffer
            // coordinates so the overlay draws exactly the areas being sampled.
            let fitted = overlay.fittedFrameRect
            let sensorHeight = fitted.height * FollowLineController.sensorHeightFraction
            let yTop = fitted.maxY - sensorHeight
            var bufferSensors: [CGRect] = []
            for i in 0..<FollowLineController.sensorCount {
                let viewRect = CGRect(
                    x: fitted.minX + CGFloat(i) * fitted.width / CGFloat(FollowLineController.sensorCount),
                    y: yTop,
                    width: fitted.width / CGFloat(FollowLineController.sensorCount),
                    height: sensorHeight)
                let topLeft = overlay.viewToFrame(CGPoint(x: viewRect.minX, y: viewRect.minY))
                let bottomRight = overlay.viewToFrame(CGPoint(x: viewRect.maxX, y: viewRect.maxY))
                bufferSensors.append(
                    CGRect(
                        x: min(topLeft.x, bottomRight.x),
                        y: min(topLeft.y, bottomRight.y),
                        width: abs(bottomRight.x - topLeft.x),
                        height: abs(bottomRight.y - topLeft.y)))
            }
            DispatchQueue.main.async {
                self.overlay.sensorBoxes = bufferSensors
            }

            var black = [Bool](repeating: false, count: FollowLineController.sensorCount)
            for (i, rect) in bufferSensors.enumerated() {
                black[i] = self.meanBrightness(rect, pixels) < Double(self.blackThreshold)
            }

            // Priority: centre → left → right, like the original sketch.
            if black[1] {
                self.setStatus("▲ " + Strings.followLineSeeing)
                self.sendControl(Control(left: 1, right: 1))
            } else if black[0] {
                self.setStatus("◀ " + Strings.followLineSeeing)
                self.sendControl(Control(left: -1, right: 1))
            } else if black[2] {
                self.setStatus("▶ " + Strings.followLineSeeing)
                self.sendControl(Control(left: 1, right: -1))
            } else {
                // No sensor sees the line: stop rather than blindly keep the last turn.
                self.setStatus(Strings.followLineLost)
                self.sendControl(Control())
            }
            self.showBoxes([])
        }
    }

    /// Mean greyscale brightness (0..255) of a buffer rectangle, or 255 when it is empty.
    private func meanBrightness(_ rect: CGRect, _ pixels: FramePixels) -> Double {
        let x0 = clamp(Int(rect.minX), 0, pixels.width - 1)
        let x1 = clamp(Int(rect.maxX), 0, pixels.width - 1)
        let y0 = clamp(Int(rect.minY), 0, pixels.height - 1)
        let y1 = clamp(Int(rect.maxY), 0, pixels.height - 1)
        var sum = 0
        var count = 0
        var y = y0
        while y <= y1 {
            var x = x0
            while x <= x1 {
                sum += pixels.brightness(x: x, y: y)
                count += 1
                x += FollowLineController.scanStride
            }
            y += FollowLineController.scanStride
        }
        return count == 0 ? 255 : Double(sum) / Double(count)
    }

    private func clamp(_ v: Int, _ minV: Int, _ maxV: Int) -> Int {
        Swift.max(minV, Swift.min(maxV, v))
    }
}
