//
//  VisionOverlayView.swift
//  OpenBot
//
//  Transparent overlay drawn on top of the camera preview by the vision-mode screens.
//  Boxes are given in camera-buffer coordinates and mapped to the on-screen preview
//  (aspect-fill, front camera mirrored), the same mapping used for touch positions.
//

import UIKit

final class VisionOverlayView: UIView {

    /// One rectangle to draw, in camera-buffer coordinates.
    struct Box {
        var rect: CGRect
        var label: String?
        var color: UIColor
        var width: CGFloat

        init(rect: CGRect, label: String? = nil, color: UIColor, width: CGFloat = 3) {
            self.rect = rect
            self.label = label
            self.color = color
            self.width = width
        }
    }

    /// Camera-buffer size of the current frames; written from the camera queue.
    var bufferSize: CGSize = .zero
    /// Front-camera previews are shown mirrored; flip the mapping so overlay and touch agree.
    var mirrored = false

    var boxes: [Box] = [] { didSet { setNeedsDisplay() } }
    /// Fixed rectangles (e.g. the line sensors), in camera-buffer coordinates.
    var sensorBoxes: [CGRect] = [] { didSet { setNeedsDisplay() } }
    /// Vertical guide lines at these fractions (0..1) of the fitted frame width.
    var guideFractions: [CGFloat]? { didSet { setNeedsDisplay() } }
    /// Colour chip shown in the top-left corner (the picked target colour), or nil to hide.
    var swatchColor: UIColor? { didSet { setNeedsDisplay() } }

    /// The aspect-fill rectangle the buffer covers inside this view.
    var fittedFrameRect: CGRect {
        guard bufferSize.width > 0, bufferSize.height > 0, bounds.width > 0 else { return bounds }
        let scale = max(bounds.width / bufferSize.width, bounds.height / bufferSize.height)
        let drawn = CGSize(width: bufferSize.width * scale, height: bufferSize.height * scale)
        return CGRect(
            x: (bounds.width - drawn.width) / 2,
            y: (bounds.height - drawn.height) / 2,
            width: drawn.width,
            height: drawn.height)
    }

    /// Maps a camera-buffer point to this view's coordinates.
    func frameToView(_ point: CGPoint) -> CGPoint {
        guard bufferSize.width > 0, bufferSize.height > 0 else { return point }
        let scale = max(bounds.width / bufferSize.width, bounds.height / bufferSize.height)
        let fitted = fittedFrameRect
        var x = point.x * scale + fitted.minX
        let y = point.y * scale + fitted.minY
        if mirrored { x = bounds.width - x }
        return CGPoint(x: x, y: y)
    }

    /// Maps a touch on this view to camera-buffer coordinates.
    func viewToFrame(_ point: CGPoint) -> CGPoint {
        guard bufferSize.width > 0, bufferSize.height > 0 else { return point }
        let scale = max(bounds.width / bufferSize.width, bounds.height / bufferSize.height)
        let fitted = fittedFrameRect
        var x = point.x
        if mirrored { x = bounds.width - x }
        return CGPoint(
            x: (x - fitted.minX) / scale,
            y: (point.y - fitted.minY) / scale)
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isUserInteractionEnabled = true
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        backgroundColor = .clear
        isUserInteractionEnabled = true
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }

        // Vertical guides (thirds of the fitted frame).
        if let guides = guideFractions {
            let fitted = fittedFrameRect
            ctx.setStrokeColor(UIColor.white.withAlphaComponent(0.55).cgColor)
            ctx.setLineWidth(1.5)
            for f in guides {
                let x = fitted.minX + f * fitted.width
                ctx.move(to: CGPoint(x: x, y: fitted.minY))
                ctx.addLine(to: CGPoint(x: x, y: fitted.maxY))
            }
            ctx.strokePath()
        }

        // Fixed sensor boxes.
        ctx.setLineWidth(2)
        for box in sensorBoxes {
            let mapped = mapRect(box)
            ctx.setStrokeColor(UIColor.white.cgColor)
            ctx.stroke(mapped)
        }

        // Tracked/detected boxes.
        for box in boxes {
            let mapped = mapRect(box.rect)
            ctx.setStrokeColor(box.color.cgColor)
            ctx.setLineWidth(box.width)
            ctx.stroke(mapped)
            if let label = box.label, !label.isEmpty {
                let attrs: [NSAttributedString.Key: Any] = [
                    .font: UIFont.boldSystemFont(ofSize: 13),
                    .foregroundColor: box.color,
                    .backgroundColor: UIColor.black.withAlphaComponent(0.4)
                ]
                (label as NSString).draw(at: CGPoint(x: mapped.minX + 2, y: mapped.minY + 2), withAttributes: attrs)
            }
        }

        // Target colour swatch.
        if let color = swatchColor {
            let box = CGRect(x: 16, y: 16, width: 56, height: 56)
            ctx.setFillColor(color.cgColor)
            ctx.fill(box)
            ctx.setStrokeColor(UIColor.white.cgColor)
            ctx.setLineWidth(2)
            ctx.stroke(box)
        }
    }

    private func mapRect(_ rect: CGRect) -> CGRect {
        let topLeft = frameToView(CGPoint(x: rect.minX, y: rect.minY))
        let bottomRight = frameToView(CGPoint(x: rect.maxX, y: rect.maxY))
        return CGRect(
            x: min(topLeft.x, bottomRight.x),
            y: min(topLeft.y, bottomRight.y),
            width: abs(bottomRight.x - topLeft.x),
            height: abs(bottomRight.y - topLeft.y))
    }
}
