// Reading the robot phone's QR on a Mac: from the camera, from a picture file, or pasted text.

import AppKit
import AVFoundation
import CoreImage
import SwiftUI
import UniformTypeIdentifiers

enum QRReader {
    private static let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil,
                                             options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])

    static func read(_ image: CIImage) -> String? {
        detector?.features(in: image).compactMap { ($0 as? CIQRCodeFeature)?.messageString }.first
    }

    /// Pick a screenshot or photo of the QR.
    static func fromFile() -> String? {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.image]
        panel.message = "Choose a picture of the robot phone's QR"
        guard panel.runModal() == .OK, let url = panel.url, let img = CIImage(contentsOf: url) else { return nil }
        return read(img)
    }

    static func fromClipboard() -> String? {
        if let s = NSPasteboard.general.string(forType: .string), !s.isEmpty { return s }
        if let img = NSImage(pasteboard: NSPasteboard.general), let tiff = img.tiffRepresentation,
           let ci = CIImage(data: tiff) { return read(ci) }
        return nil
    }
}

/// The Mac camera, looking for a QR code. Stops itself after the first code.
final class QRCamera: NSObject, ObservableObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    let session = AVCaptureSession()
    @Published var message = "Hold the robot phone's QR up to the camera"
    var onCode: ((String) -> Void)?
    private let queue = DispatchQueue(label: "friendbot.mac.qr")
    private var lastCheck = Date.distantPast
    private var done = false

    func start() {
        AVCaptureDevice.requestAccess(for: .video) { ok in
            DispatchQueue.main.async {
                guard ok else { self.message = "Camera blocked — allow it in System Settings › Privacy"; return }
                self.queue.async { self.configure() }
            }
        }
    }

    private func configure() {
        guard let cam = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: cam),
              session.canAddInput(input) else {
            DispatchQueue.main.async { self.message = "No camera found — use “Picture” instead" }
            return
        }
        session.beginConfiguration()
        session.addInput(input)
        let out = AVCaptureVideoDataOutput()
        out.setSampleBufferDelegate(self, queue: queue)
        if session.canAddOutput(out) { session.addOutput(out) }
        session.commitConfiguration()
        session.startRunning()
    }

    func stop() { queue.async { if self.session.isRunning { self.session.stopRunning() } } }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        guard !done, Date().timeIntervalSince(lastCheck) > 0.25,
              let px = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        lastCheck = Date()
        if let text = QRReader.read(CIImage(cvPixelBuffer: px)) {
            done = true
            stop()
            DispatchQueue.main.async { self.onCode?(text) }
        }
    }
}

struct CameraPreview: NSViewRepresentable {
    let session: AVCaptureSession

    func makeNSView(context: Context) -> NSView {
        let v = NSView()
        v.wantsLayer = true
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.autoresizingMask = [.layerWidthSizable, .layerHeightSizable]
        v.layer = layer
        return v
    }

    func updateNSView(_ nsView: NSView, context: Context) {}
}

struct QRScanSheet: View {
    @StateObject private var camera = QRCamera()
    let onResult: (String?) -> Void

    var body: some View {
        VStack(spacing: 14) {
            Text("SCAN QR").font(.system(size: 12, weight: .bold, design: .monospaced))
                .tracking(2).foregroundColor(Palette.kicker)
            ZStack {
                CameraPreview(session: camera.session)
                RoundedRectangle(cornerRadius: 14).stroke(Palette.cyan, lineWidth: 2).padding(40)
            }
            .frame(width: 320, height: 320)
            .clipShape(RoundedRectangle(cornerRadius: 18))
            Text(camera.message).font(.system(size: 12, design: .monospaced))
                .foregroundColor(Palette.muted).multilineTextAlignment(.center)
            HStack(spacing: 10) {
                RoboButton(title: "PICTURE", filled: false) { finish(QRReader.fromFile()) }
                RoboButton(title: "PASTE", filled: false) { finish(QRReader.fromClipboard()) }
                RoboButton(title: "CANCEL", filled: false) { finish(nil) }
            }
        }
        .padding(22)
        .background(Palette.ink)
        .onAppear {
            camera.onCode = { finish($0) }
            camera.start()
        }
        .onDisappear { camera.stop() }
    }

    private func finish(_ text: String?) {
        camera.stop()
        onResult(text)
    }
}
