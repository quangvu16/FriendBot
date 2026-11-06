//
//  FollowFaceController.swift
//  OpenBot
//
//  Follow a Face — port of stage 2 of Kirstin813/L4-Individual-Project.
//
//  The original uses MediaPipe Face Detection (short-range model) and steers by the nose
//  landmark: left third turns left, right third turns right, and the middle third means STOP —
//  the robot turns to face you but never drives at you. Apple's Vision framework is the native
//  iOS equivalent, so the control law is ported unchanged.
//

import UIKit
import AVFoundation
import Vision

final class FollowFaceController: VisionModeController {

    private var isProcessing = false

    override func guideId() -> String? { GuideController.guideFace }

    override func setupModeControls() {
        overlay.guideFractions = [1.0 / 3.0, 2.0 / 3.0]
        setStatus(Strings.followFaceStatusScanning)
    }

    override func processVisionFrame(_ pixelBuffer: CVPixelBuffer) {
        guard !isProcessing else { return }
        isProcessing = true

        let request = VNDetectFaceLandmarksRequest()
        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, options: [:])
        defer { isProcessing = false }
        do {
            try handler.perform([request])
        } catch {
            setStatus(Strings.followFaceStatusScanning)
            showBoxes([])
            sendControl(Control())
            return
        }

        guard let face = request.results?.first as? VNFaceObservation else {
            setStatus(Strings.followFaceStatusScanning)
            showBoxes([])
            sendControl(Control())
            return
        }

        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)

        // Vision boxes are normalized with the origin at the bottom-left.
        let b = face.boundingBox
        let boxPixels = CGRect(
            x: b.minX * CGFloat(width),
            y: (1 - b.maxY) * CGFloat(height),
            width: b.width * CGFloat(width),
            height: b.height * CGFloat(height))

        // Steer by the nose landmark; fall back to the box centre without one.
        var centre = CGPoint(x: boxPixels.midX, y: boxPixels.midY)
        if let nose = face.landmarks?.nose, nose.pointCount > 0 {
            var nx: CGFloat = 0, ny: CGFloat = 0
            let points = nose.normalizedPoints
            for i in 0..<nose.pointCount {
                nx += CGFloat(points[i].x)
                ny += CGFloat(points[i].y)
            }
            let n = CGFloat(nose.pointCount)
            // Nose points are normalized within the face box, origin bottom-left.
            let normX = b.minX + (nx / n) * b.width
            let normY = b.minY + (ny / n) * b.height
            centre = CGPoint(x: normX * CGFloat(width), y: (1 - normY) * CGFloat(height))
        }

        showBoxes([
            VisionOverlayView.Box(
                rect: boxPixels, label: Strings.tileFollowFace, color: .cyan, width: 3)
        ])

        let viewPoint = overlay.frameToView(centre)
        let fitted = overlay.fittedFrameRect
        let third = fitted.width / 3
        if viewPoint.x < fitted.minX + third {
            setStatus("◀ " + Strings.followFaceStatusFound)
            sendControl(Control(left: -1, right: 1))
        } else if viewPoint.x > fitted.maxX - third {
            setStatus("▶ " + Strings.followFaceStatusFound)
            sendControl(Control(left: 1, right: -1))
        } else {
            // Middle zone: stop. The robot turns to face you but never drives at you.
            setStatus("■ " + Strings.followFaceStatusFound)
            sendControl(Control())
        }
    }
}
