//
//  TargetTrackingController.swift
//  OpenBot
//
//  Target Tracking — port of the interaction of longpth/Android-Object-Tracking-OpenCV:
//  drag a box around the object on the camera preview, lock it, and the car follows the box's
//  centre with the same three-zone steering as the follow modes. That project used OpenCV's
//  TrackerMedianFlow and — importantly — kept driving when tracker.update() failed; here
//  tracking is Apple's Vision VNTrackObjectRequest and a lost target stops the car until
//  you re-lock.
//

import UIKit
import AVFoundation
import Vision

final class TargetTrackingController: VisionModeController {

    private static let minDragSize: CGFloat = 24

    private var trackRequest: VNTrackObjectRequest?
    /// Buffer-space rect waiting to be locked on the next camera frame.
    private var pendingLock: CGRect?
    private var dragRect = CGRect.zero
    private var dragging = false
    private var hasDragRect = false
    private var lockButton: UIButton?

    override func guideId() -> String? {
        // The L4 project ships no tutorial for this mode; the help button shows the usage hint.
        nil
    }

    override func helpFallback() -> String { Strings.trackHint }

    override func setupModeControls() {
        overlay.guideFractions = [1.0 / 3.0, 2.0 / 3.0]
        lockButton = addActionButton(title: Strings.trackLock) { [weak self] in
            self?.onLockButton()
        }
        setStatus(Strings.trackStatusIdle)
        let pan = UIPanGestureRecognizer(target: self, action: #selector(onPan(_:)))
        overlay.addGestureRecognizer(pan)
    }

    @objc private func onPan(_ gesture: UIPanGestureRecognizer) {
        let point = gesture.location(in: overlay)
        switch gesture.state {
        case .began:
            dragRect = CGRect(origin: overlay.viewToFrame(point), size: .zero)
            dragging = true
            hasDragRect = false
        case .changed:
            guard dragging else { return }
            let framePoint = overlay.viewToFrame(point)
            dragRect = CGRect(
                x: min(dragRect.minX, framePoint.x),
                y: min(dragRect.minY, framePoint.y),
                width: abs(framePoint.x - dragRect.minX),
                height: abs(framePoint.y - dragRect.minY))
            hasDragRect = true
        case .ended, .cancelled, .failed:
            dragging = false
            if hasDragRect && trackRequest == nil && pendingLock == nil {
                showBoxes([
                    VisionOverlayView.Box(rect: dragRect, label: Strings.trackLock, color: .yellow, width: 2)
                ])
                setStatus(Strings.trackHint)
            }
        default:
            break
        }
    }

    private func onLockButton() {
        if trackRequest != nil || pendingLock != nil {
            trackRequest = nil
            pendingLock = nil
            DispatchQueue.main.async {
                self.lockButton?.setTitle(Strings.trackLock, for: .normal)
            }
            showBoxes([])
            setStatus(Strings.trackStatusIdle)
            sendControl(Control())
            return
        }
        guard hasDragRect else {
            setStatus(Strings.trackStatusIdle)
            return
        }
        pendingLock = dragRect
        setStatus(Strings.trackStatusTracking)
    }

    override func processVisionFrame(_ pixelBuffer: CVPixelBuffer) {
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)

        if let roi = pendingLock {
            pendingLock = nil
            // Vision boxes are normalized with the origin at the bottom-left.
            let normalized = CGRect(
                x: roi.minX / CGFloat(width),
                y: 1 - roi.maxY / CGFloat(height),
                width: roi.width / CGFloat(width),
                height: roi.height / CGFloat(height))
            let observation = VNDetectedObjectObservation(boundingBox: normalized)
            let request = VNTrackObjectRequest(detectedObjectObservation: observation)
            request.trackingLevel = .accurate
            trackRequest = request
            hasDragRect = false
            DispatchQueue.main.async {
                self.lockButton?.setTitle(Strings.trackUnlock, for: .normal)
            }
        }

        guard let request = trackRequest else { return }

        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, options: [:])
        do {
            try handler.perform([request])
        } catch {
            loseTarget()
            return
        }

        guard let result = request.results?.first as? VNDetectedObjectObservation,
              result.confidence > 0.2 else {
            // The reference project kept driving on tracker loss; stopping is safe.
            loseTarget()
            return
        }

        // Feed the new observation into a fresh request for the next frame (sequential tracking).
        let next = VNTrackObjectRequest(detectedObjectObservation: result)
        next.trackingLevel = .accurate
        trackRequest = next

        let b = result.boundingBox
        let targetPixels = CGRect(
            x: b.minX * CGFloat(width),
            y: (1 - b.maxY) * CGFloat(height),
            width: b.width * CGFloat(width),
            height: b.height * CGFloat(height))

        showBoxes([
            VisionOverlayView.Box(
                rect: targetPixels, label: Strings.tileTargetTracking, color: .green, width: 3)
        ])

        let viewPoint = overlay.frameToView(
            CGPoint(x: targetPixels.midX, y: targetPixels.midY))
        let fitted = overlay.fittedFrameRect
        let third = fitted.width / 3
        if viewPoint.x < fitted.minX + third {
            setStatus("◀ " + Strings.trackStatusTracking)
            sendControl(Control(left: -1, right: 1))
        } else if viewPoint.x > fitted.maxX - third {
            setStatus("▶ " + Strings.trackStatusTracking)
            sendControl(Control(left: 1, right: -1))
        } else {
            setStatus("▲ " + Strings.trackStatusTracking)
            sendControl(Control(left: 1, right: 1))
        }
    }

    private func loseTarget() {
        trackRequest = nil
        setStatus(Strings.trackStatusLost)
        showBoxes([])
        sendControl(Control())
        DispatchQueue.main.async {
            self.lockButton?.setTitle(Strings.trackLock, for: .normal)
        }
    }
}
