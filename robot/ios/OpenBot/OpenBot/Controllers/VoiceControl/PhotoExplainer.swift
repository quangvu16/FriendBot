//
//  PhotoExplainer.swift
//  OpenBot
//
//  Phone-camera "vision" for the Voice Control screen: the live corner preview (its "eye"),
//  the front/back lens choice, and the Xiaozhi photo tool (modeled on the Keyestudio KS5028
//  S3CAM firmware's `self.camera.take_photo`) — capture one JPEG and upload it to the Xiaozhi
//  vision explain service. The service's response body is the tool result and is spoken by the
//  server like any other reply — the phone never speaks it.
//
//  The upload mirrors the firmware's `Esp32Camera::Explain` exactly: POST multipart/form-data
//  with the `question` and `file` fields (boundary `----ESP32_CAMERA_BOUNDARY`,
//  Device-Id/Client-Id/Authorization headers) to the explain URL + token the server pushes in
//  the MCP `initialize`.
//
//  The session stays open while the screen shows (preview + capture share it); after every
//  capture the raw shot goes back to the UI for the shutter still so the user sees the
//  picture being taken.
//

import AVFoundation
import UIKit

/// Small well that keeps its `AVCaptureVideoPreviewLayer` sized to its bounds.
final class CameraPreviewHost: UIView {

    private var previewLayer: AVCaptureVideoPreviewLayer?

    func attach(layer: AVCaptureVideoPreviewLayer) {
        previewLayer?.removeFromSuperlayer()
        previewLayer = layer
        layer.frame = bounds
        self.layer.insertSublayer(layer, at: 0)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        previewLayer?.frame = bounds
    }
}

final class PhotoExplainer: NSObject, AVCapturePhotoCaptureDelegate {

    /// Main-thread callbacks of one capture.
    struct Handlers {
        /// The shutter fired; `jpeg` is the raw capture (for the corner still flash).
        let captured: (Data?) -> Void
        /// `photoJson` (the explain service's response body) non-nil on success; `error` then
        /// holds a short reason.
        let explained: (String?, String) -> Void
    }

    /// Longest edge kept when shrinking the capture before upload.
    private static let maxEdge: CGFloat = 1024

    /// Same fixed multipart boundary as the firmware's camera upload.
    private static let uploadBoundary = "----ESP32_CAMERA_BOUNDARY"

    private let sessionQueue = DispatchQueue(label: "org.openbot.friendbot.photo")
    private var session: AVCaptureSession?
    private var photoOutput: AVCapturePhotoOutput?
    private(set) var isFront = false

    private var pending: Handlers?
    private var pendingQuestion = ""

    /// Explain endpoint of the current capture (one photo job runs at a time).
    private var explainUrl = ""
    private var explainToken = ""

    // MARK: Live corner preview

    /// Opens the live corner preview and the capture path on the chosen lens.
    func start(in host: CameraPreviewHost, useFront: Bool) {
        isFront = useFront
        AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
            guard let self = self, granted else { return }
            self.sessionQueue.async {
                self.configure(host: host, position: useFront ? .front : .back)
            }
        }
    }

    /// Flips between the phone's back and front cameras (the preview follows).
    func switchCamera(in host: CameraPreviewHost) {
        start(in: host, useFront: !isFront)
    }

    /// The corner preview goes away with the screen; the next capture binds again.
    func teardown() {
        sessionQueue.async { self.teardownSession() }
    }

    private func configure(host: CameraPreviewHost, position: AVCaptureDevice.Position) {
        teardownSession()
        let session = AVCaptureSession()
        session.sessionPreset = .photo
        guard let device = cameraDevice(position: position),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            return
        }
        session.addInput(input)
        let output = AVCapturePhotoOutput()
        guard session.canAddOutput(output) else { return }
        session.addOutput(output)
        self.session = session
        self.photoOutput = output
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        DispatchQueue.main.async { host.attach(layer: layer) }
        session.startRunning()
    }

    private func teardownSession() {
        session?.stopRunning()
        session = nil
        photoOutput = nil
    }

    private func cameraDevice(position: AVCaptureDevice.Position) -> AVCaptureDevice? {
        AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position)
            ?? (position == .back ? AVCaptureDevice.default(for: .video) : nil)
    }

    // MARK: Capture → explain

    /// Capture → shutter flash → explain → handlers. Uses the live preview's camera when it
    /// is running and falls back to a one-shot bind otherwise.
    ///
    /// - Parameters:
    ///   - explainUrl: vision explain endpoint pushed by the server (MCP initialize)
    ///   - explainToken: bearer token for it (may be empty)
    func captureAndExplain(
        question: String,
        explainUrl: String,
        explainToken: String,
        onCaptured: @escaping (Data?) -> Void,
        explained: @escaping (String?, String) -> Void
    ) {
        pendingQuestion = question
        self.explainUrl = explainUrl
        self.explainToken = explainToken
        pending = Handlers(captured: onCaptured, explained: explained)
        AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
            guard let self = self else { return }
            guard granted else {
                self.finish(photoJson: nil, error: "Camera permission denied")
                return
            }
            self.sessionQueue.async { self.shoot() }
        }
    }

    private func shoot() {
        if let output = photoOutput, let session = session, session.isRunning {
            takePhoto(with: output)
            return
        }
        let session = AVCaptureSession()
        session.sessionPreset = .photo
        guard let device = cameraDevice(position: isFront ? .front : .back),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            finish(photoJson: nil, error: "Camera unavailable")
            return
        }
        session.addInput(input)
        let output = AVCapturePhotoOutput()
        guard session.canAddOutput(output) else {
            finish(photoJson: nil, error: "Camera unavailable")
            return
        }
        session.addOutput(output)
        self.session = session
        self.photoOutput = output
        session.startRunning()
        takePhoto(with: output)
    }

    private func takePhoto(with output: AVCapturePhotoOutput) {
        let settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
        settings.flashMode = .off
        output.capturePhoto(with: settings, delegate: self)
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto,
                     error: Error?) {
        let handlers = pending
        guard error == nil, let data = photo.fileDataRepresentation() else {
            DispatchQueue.main.async { handlers?.captured(nil) }
            finish(photoJson: nil, error: "Failed to capture photo")
            return
        }
        DispatchQueue.main.async { handlers?.captured(data) }
        explain(jpeg: Self.shrink(data) ?? data)
    }

    // MARK: Upload (vision explain service) and finish

    /// Uploads the photo to the vision explain endpoint — the exact wire format of the S3CAM
    /// firmware's `Esp32Camera::Explain`: multipart/form-data with the `question` and `file`
    /// fields, bearer token plus Device-Id/Client-Id. The service's response body is the answer.
    private func explain(jpeg: Data) {
        guard !explainUrl.isEmpty, let url = URL(string: explainUrl) else {
            finish(photoJson: nil, error: "Image explain URL or token is not set")
            return
        }
        let boundary = Self.uploadBoundary
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 30
        request.setValue("multipart/form-data; boundary=\(boundary)",
                         forHTTPHeaderField: "Content-Type")
        if !explainToken.isEmpty {
            request.setValue("Bearer \(explainToken)", forHTTPHeaderField: "Authorization")
        }
        request.setValue(XiaozhiActivator.savedDeviceId(), forHTTPHeaderField: "Device-Id")
        request.setValue(XiaozhiActivator.savedClientId(), forHTTPHeaderField: "Client-Id")
        let question = pendingQuestion
        var body = Data()
        body.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"question\"\r\n\r\n\(question)\r\n"
            .data(using: .utf8)!)
        body.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"camera.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"
            .data(using: .utf8)!)
        body.append(jpeg)
        body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        request.httpBody = body
        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            guard let self = self else { return }
            guard error == nil,
                  let http = response as? HTTPURLResponse,
                  (200..<300).contains(http.statusCode),
                  let data = data,
                  let body = String(data: data, encoding: .utf8),
                  !body.isEmpty else {
                self.finish(photoJson: nil, error: "Failed to upload photo")
                return
            }
            self.finish(photoJson: body, error: "")
        }.resume()
    }

    private func finish(photoJson: String?, error: String) {
        let explained = pending?.explained
        pending = nil
        DispatchQueue.main.async {
            explained?(photoJson, error)
        }
    }

    /// Downscales the capture to `maxEdge` and re-encodes it; nil when not decodable.
    private static func shrink(_ jpeg: Data) -> Data? {
        guard let image = UIImage(data: jpeg) else { return nil }
        let longest = max(image.size.width, image.size.height)
        guard longest > maxEdge else {
            return image.jpegData(compressionQuality: 0.8)
        }
        let scale = maxEdge / longest
        let size = CGSize(width: (image.size.width * scale).rounded(),
                          height: (image.size.height * scale).rounded())
        let renderer = UIGraphicsImageRenderer(size: size)
        let scaled = renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
        return scaled.jpegData(compressionQuality: 0.8)
    }
}
