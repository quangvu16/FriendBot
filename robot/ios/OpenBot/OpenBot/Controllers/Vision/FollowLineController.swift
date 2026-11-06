//
//  FollowLineController.swift
//  OpenBot
//
//  Follow a Black Line — port of stage 3 of Kirstin813/L4-Individual-Project.
//
//  Three virtual sensors along the bottom of the camera image: the frame is split into
//  thirds, each sensor is a strip along the bottom edge, and a sensor counts as "on the
//  line" when it matches the chosen line colour — black by default (mean brightness under
//  the threshold), any preset colour matched per pixel. Control follows the priority
//  centre → left → right; no sensor on the line stops the car. Point the camera down at a
//  tape circuit and pick the line colour.
//

import UIKit
import AVFoundation

final class FollowLineController: VisionModeController {

    private static let defaultThreshold = 90
    private static let sensorCount = 3
    /// Sensor strip height as a fraction of the fitted preview (L4 used 50 px of ~480).
    private static let sensorHeightFraction: CGFloat = 0.12
    private static let scanStride = 2
    /// Per-channel RGB tolerance when matching a coloured line.
    private static let colorTolerance = 60
    /// A sensor counts as on-line when this fraction of its pixels matches the line colour.
    private static let matchFractionThreshold: Double = 0.12

    /// Preset line colours: black, red, green, blue, yellow (RGB 0...255).
    private static let lineColors: [(Int, Int, Int)] = [
        (0, 0, 0), (229, 57, 53), (67, 160, 71), (30, 136, 229), (253, 216, 53),
    ]

    private var blackThreshold = FollowLineController.defaultThreshold
    private var lineColor = FollowLineController.lineColors[0]

    override func guideId() -> String? { GuideController.guideLine }

    override func setupModeControls() {
        addColorRow(
            label: Strings.followLineColor,
            names: [
                Strings.colorBlack, Strings.colorRed, Strings.colorGreen, Strings.colorBlue,
                Strings.colorYellow,
            ],
            colors: FollowLineController.lineColors.map {
                UIColor(
                    red: CGFloat($0.0) / 255, green: CGFloat($0.1) / 255,
                    blue: CGFloat($0.2) / 255, alpha: 1)
            }
        ) { [weak self] index in
            self?.lineColor = FollowLineController.lineColors[index]
        }
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

            // Black keeps the original brightness-threshold test; other colours match per pixel.
            let blackLine = self.lineColor == (0, 0, 0)
            var onLine = [Bool](repeating: false, count: FollowLineController.sensorCount)
            for (i, rect) in bufferSensors.enumerated() {
                onLine[i] = blackLine
                    ? self.meanBrightness(rect, pixels) < Double(self.blackThreshold)
                    : self.matchFraction(rect, pixels)
                        >= FollowLineController.matchFractionThreshold
            }

            // Priority: centre → left → right, like the original sketch.
            if onLine[1] {
                self.setStatus("▲ " + Strings.followLineSeeing)
                self.sendControl(Control(left: 1, right: 1))
            } else if onLine[0] {
                self.setStatus("◀ " + Strings.followLineSeeing)
                self.sendControl(Control(left: -1, right: 1))
            } else if onLine[2] {
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

    /// Fraction of pixels in a buffer rectangle within colorTolerance of the line colour.
    private func matchFraction(_ rect: CGRect, _ pixels: FramePixels) -> Double {
        let (targetR, targetG, targetB) = lineColor
        let x0 = clamp(Int(rect.minX), 0, pixels.width - 1)
        let x1 = clamp(Int(rect.maxX), 0, pixels.width - 1)
        let y0 = clamp(Int(rect.minY), 0, pixels.height - 1)
        let y1 = clamp(Int(rect.maxY), 0, pixels.height - 1)
        var matches = 0
        var count = 0
        var y = y0
        while y <= y1 {
            var x = x0
            while x <= x1 {
                let (r, g, b) = pixels.rgb(x: x, y: y)
                if abs(r - targetR) <= FollowLineController.colorTolerance,
                    abs(g - targetG) <= FollowLineController.colorTolerance,
                    abs(b - targetB) <= FollowLineController.colorTolerance {
                    matches += 1
                }
                count += 1
                x += FollowLineController.scanStride
            }
            y += FollowLineController.scanStride
        }
        return count == 0 ? 0 : Double(matches) / Double(count)
    }

    private func clamp(_ v: Int, _ minV: Int, _ maxV: Int) -> Int {
        Swift.max(minV, Swift.min(maxV, v))
    }
}
