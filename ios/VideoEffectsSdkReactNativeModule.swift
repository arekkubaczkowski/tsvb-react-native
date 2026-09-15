import ExpoModulesCore
import AVFoundation
import LiveKitWebRTC
import UIKit
@preconcurrency import TSVB
import os

// MARK: - State

enum TsvbState: Int {
    case uninitialized = 0
    case authenticating = 1
    case idle = 2    // SDK ready, no effect enabled
    case active = 3  // Effect enabled, processing frames
    case error = 4
}

// MARK: - Frame Processor

/// Per-usage frame processor. NOT a singleton.
/// Holds a strong reference to the pipeline and lock.
/// Created when effects are initialized, destroyed on cleanup.
final class TsvbFrameProcessor: NSObject {

    private var pipeline: Pipeline?
    private var lock = os_unfair_lock()
    private var _active: Bool = false

    private static var _deviceRotationLock = os_unfair_lock()
    private static var _deviceRotation: Rotation = ._270
    private static var orientationObserver: NSObjectProtocol?

    static var deviceRotation: Rotation {
        os_unfair_lock_lock(&_deviceRotationLock)
        let val = _deviceRotation
        os_unfair_lock_unlock(&_deviceRotationLock)
        return val
    }

    static func startObservingOrientation() {
        guard orientationObserver == nil else { return }
        UIDevice.current.beginGeneratingDeviceOrientationNotifications()
        updateDeviceRotation()
        orientationObserver = NotificationCenter.default.addObserver(
            forName: UIDevice.orientationDidChangeNotification,
            object: nil,
            queue: .main
        ) { _ in
            updateDeviceRotation()
        }
    }

    private static func updateDeviceRotation() {
        os_unfair_lock_lock(&_deviceRotationLock)
        switch UIDevice.current.orientation {
        case .landscapeLeft: _deviceRotation = ._0
        case .landscapeRight: _deviceRotation = ._180
        default: _deviceRotation = ._270
        }
        os_unfair_lock_unlock(&_deviceRotationLock)
    }

    // Frame capture state
    private var _captureEnabled: Bool = false
    private var _captureIntervalMs: Int = 5000
    private var _lastCaptureTime: UInt64 = 0
    private var _lastCapturedFilePath: String?
    private let ciContext = CIContext()
    var onFrameCaptured: ((_ filePath: String, _ width: Int, _ height: Int, _ timestamp: Double) -> Void)?

    init(pipeline: Pipeline) {
        self.pipeline = pipeline
        super.init()
    }

    var isActive: Bool {
        os_unfair_lock_lock(&lock)
        let val = _active
        os_unfair_lock_unlock(&lock)
        return val
    }

    func setActive(_ active: Bool) {
        os_unfair_lock_lock(&lock)
        _active = active
        os_unfair_lock_unlock(&lock)
    }

    func setCaptureEnabled(_ enabled: Bool, intervalMs: Int = 5000) {
        os_unfair_lock_lock(&lock)
        _captureEnabled = enabled
        _captureIntervalMs = intervalMs
        if !enabled {
            _lastCaptureTime = 0
        }
        os_unfair_lock_unlock(&lock)
    }

    var isCaptureEnabled: Bool {
        os_unfair_lock_lock(&lock)
        let val = _captureEnabled
        os_unfair_lock_unlock(&lock)
        return val
    }

    func processFrame(_ pixelBuffer: CVPixelBuffer) -> CVPixelBuffer? {
        os_unfair_lock_lock(&lock)
        let isActiveNow = _active
        let shouldCapture = _captureEnabled
        let intervalMs = _captureIntervalMs
        let lastCapture = _lastCaptureTime

        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        guard width > 0, height > 0 else {
            os_unfair_lock_unlock(&lock)
            return pixelBuffer
        }

        var outputBuffer = pixelBuffer
        let rotation = Self.deviceRotation
        if isActiveNow, let pipeline = pipeline {
            let result = pipeline.process(pixelBuffer: pixelBuffer, metalCompatible: true, rotation: rotation, error: nil)
            outputBuffer = result?.toCVPixelBuffer() ?? pixelBuffer
        }

        os_unfair_lock_unlock(&lock)

        if shouldCapture {
            let nowMs = DispatchTime.now().uptimeNanoseconds / 1_000_000
            if lastCapture == 0 || (nowMs - lastCapture) >= UInt64(intervalMs) {
                os_unfair_lock_lock(&lock)
                _lastCaptureTime = nowMs
                os_unfair_lock_unlock(&lock)
                self.captureFrame(outputBuffer, width: width, height: height)
            }
        }

        return outputBuffer
    }

    /// Called under external synchronization (module's control queue).
    func updatePipeline(_ pipeline: Pipeline?) {
        os_unfair_lock_lock(&lock)
        defer { os_unfair_lock_unlock(&lock) }
        self.pipeline = pipeline
    }

    func teardown() {
        os_unfair_lock_lock(&lock)
        _active = false
        _captureEnabled = false
        defer { os_unfair_lock_unlock(&lock) }
        pipeline = nil
    }

    // MARK: - Frame Capture Helpers

    private func captureFrame(_ pixelBuffer: CVPixelBuffer, width: Int, height: Int) {
        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        let rotated = Self.rotateToDeviceOrientation(ciImage)
        let extent = rotated.extent

        guard let cgImage = ciContext.createCGImage(rotated, from: extent) else {
            return
        }

        let rotatedWidth = Int(extent.width)
        let rotatedHeight = Int(extent.height)

        DispatchQueue.global(qos: .utility).async { [weak self] in
            guard let self = self else { return }

            let uiImage = UIImage(cgImage: cgImage)
            guard let jpegData = uiImage.jpegData(compressionQuality: 0.8) else {
                return
            }

            let timestamp = Date().timeIntervalSince1970 * 1000
            let fileName = "frame_\(Int(timestamp)).jpg"
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("captured_frames", isDirectory: true)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let filePath = dir.appendingPathComponent(fileName)

            if let prev = self._lastCapturedFilePath {
                try? FileManager.default.removeItem(atPath: prev)
            }

            do {
                try jpegData.write(to: filePath)
                self._lastCapturedFilePath = filePath.path
                self.onFrameCaptured?(filePath.path, rotatedWidth, rotatedHeight, timestamp)
            } catch {
                NSLog("[VideoEffects] Failed to save captured frame: \(error)")
            }
        }
    }

    private static func rotateToDeviceOrientation(_ image: CIImage) -> CIImage {
        let deviceOrientation = UIDevice.current.orientation
        switch deviceOrientation {
        case .landscapeLeft:
            return image.oriented(.upMirrored)
        case .landscapeRight:
            return image.oriented(.downMirrored)
        default:
            return image.oriented(.leftMirrored)
        }
    }
}

// MARK: - VideoFrameProcessorDelegate bridge

/// ObjC-compatible wrapper that conforms to the fork's VideoFrameProcessorDelegate.
/// Delegates all frame processing to TsvbFrameProcessor.
@objc(TsvbVideoFrameProcessorBridge)
final class TsvbVideoFrameProcessorBridge: NSObject {

    private let processor: TsvbFrameProcessor

    init(processor: TsvbFrameProcessor) {
        self.processor = processor
        super.init()
    }

    /// Called by WebRTC's VideoEffectProcessor on the capture thread.
    @objc func capturer(_ capturer: LKRTCVideoCapturer, didCaptureVideoFrame frame: LKRTCVideoFrame) -> LKRTCVideoFrame {
        guard processor.isActive || processor.isCaptureEnabled else {
            return frame
        }

        guard let rtcBuffer = frame.buffer as? LKRTCCVPixelBuffer else {
            return frame
        }

        guard let processedBuffer = processor.processFrame(rtcBuffer.pixelBuffer) else {
            return frame
        }

        let newBuffer = LKRTCCVPixelBuffer(pixelBuffer: processedBuffer)
        return LKRTCVideoFrame(buffer: newBuffer, rotation: frame.rotation, timeStampNs: frame.timeStampNs)
    }
}

// MARK: - Main Module

public class VideoEffectsSdkReactNativeModule: Module {

    // MARK: - Properties

    private var sdkFactory: SDKFactory?
    private var pipeline: Pipeline?
    private var frameFactory: FrameFactory?

    private var frameProcessor: TsvbFrameProcessor?
    private var processorBridge: TsvbVideoFrameProcessorBridge?

    private var state: TsvbState = .uninitialized
    private var blurEnabled = false
    private var replaceBackgroundEnabled = false
    private var replacementController: (any ReplacementController)?

    /// The track ID the processor is currently attached to.
    private var attachedTrackId: String?

    /// Original background image — kept for re-apply after cleanup.
    private var originalBackgroundImage: UIImage?

    /// Segmentation preset — configurable from JS. Applied on next pipeline creation.
    private var currentSegmentationPreset: SegmentationPreset = .quality

    /// Serial queue for ALL state mutations and pipeline operations.
    private let controlQueue = DispatchQueue(label: "com.tsvb.control")

    // MARK: - Module Definition

    public func definition() -> ModuleDefinition {
        Name("VideoEffectsSdkReactNativeModule")

        Events("onFrameCaptured")

        AsyncFunction("initialize") { (customerID: String, trackId: String) -> [String: Any] in
            return await self.doInitialize(customerID: customerID, trackId: trackId)
        }

        AsyncFunction("enableBlurBackground") { (power: Double?, promise: Promise) in
            Task {
                let result = await self.doEnableBlur(power: Float(power ?? 0.5))
                if result["success"] as? Bool == true {
                    promise.resolve(result)
                } else {
                    promise.reject("EFFECTS_ERROR", result["error"] as? String ?? "Unknown error")
                }
            }
        }

        AsyncFunction("disableBlurBackground") { () -> [String: Any] in
            return await self.doDisableBlur()
        }

        AsyncFunction("enableReplaceBackground") { (assetSource: [String: Any]?, promise: Promise) in
            Task {
                let result = await self.doEnableReplace(assetSource: assetSource)
                if result["success"] as? Bool == true {
                    promise.resolve(result)
                } else {
                    promise.reject("EFFECTS_ERROR", result["error"] as? String ?? "Unknown error")
                }
            }
        }

        AsyncFunction("disableReplaceBackground") { () -> [String: Any] in
            return await self.doDisableReplace()
        }

        Function("setBlurPower") { (power: Double) in
            self.controlQueue.async {
                guard self.blurEnabled, let pipeline = self.pipeline else { return }
                pipeline.enableBlurBackground(power: Float(power))
            }
        }

        Function("isInitialized") {
            return self.state == .idle || self.state == .active
        }

        Function("isBlurEnabled") {
            return self.blurEnabled
        }

        Function("hasVirtualBackground") {
            return self.replaceBackgroundEnabled
        }

        Function("isEffectsUnavailable") {
            return false // iOS doesn't have fallback mode — effects always available when initialized
        }

        Function("cleanup") {
            self.doCleanup()
        }

        Function("setSegmentationPreset") { (preset: String) in
            let newPreset: SegmentationPreset
            switch preset {
            case "balanced": newPreset = .balanced
            case "speed": newPreset = .speed
            case "lightning": newPreset = .lightning
            default: newPreset = .quality
            }
            self.currentSegmentationPreset = newPreset

            self.controlQueue.async {
                guard let pipeline = self.pipeline,
                      let config = pipeline.copyConfiguration() else { return }
                config.segmentationPreset = newPreset
                pipeline.setConfiguration(config)
            }
        }

        Function("startFrameCapture") { (intervalMs: Int) in
            self.controlQueue.async {
                self.frameProcessor?.onFrameCaptured = { [weak self] filePath, width, height, timestamp in
                    self?.sendEvent("onFrameCaptured", [
                        "filePath": filePath,
                        "timestamp": timestamp,
                        "width": width,
                        "height": height,
                    ])
                }
                self.frameProcessor?.setCaptureEnabled(true, intervalMs: intervalMs)
            }
        }

        Function("stopFrameCapture") {
            self.controlQueue.async {
                self.frameProcessor?.setCaptureEnabled(false)
                Self.cleanupCapturedFrames()
            }
        }
    }

    // MARK: - Initialize

    private func doInitialize(customerID: String, trackId: String) async -> [String: Any] {
        return await onControlQueue { () async -> [String: Any] in
            // If already initialized, just re-attach to new track if needed
            if self.state == .idle || self.state == .active {
                if self.attachedTrackId != trackId {
                    let attachResult = self.attachProcessorToTrack(trackId)
                    return ["success": true, "status": "already_initialized", "attachResult": attachResult]
                }
                return ["success": true, "status": "already_initialized", "attachResult": "already_attached"]
            }

            guard self.state != .authenticating else {
                return ["success": false, "error": "Initialization already in progress"]
            }

            self.state = .authenticating

            do {
                let factory = TSVB.SDKFactory()
                self.sdkFactory = factory

                let authResult = try await factory.auth(customerID: customerID)

                guard authResult.status == .active else {
                    self.state = .error
                    return ["success": false, "error": self.authErrorMessage(authResult.status)]
                }

                guard let pipeline = factory.newPipeline() else {
                    self.state = .error
                    return ["success": false, "error": "Failed to create pipeline"]
                }
                self.pipeline = pipeline
                self.frameFactory = factory.newFrameFactory()

                if let config = pipeline.copyConfiguration() {
                    config.segmentationPreset = self.currentSegmentationPreset
                    config.isSegmentationOnNeuralEngineEnabled = true
                    config.backend = .GPU
                    pipeline.setConfiguration(config)
                }

                TsvbFrameProcessor.startObservingOrientation()
                let fp = TsvbFrameProcessor(pipeline: pipeline)
                self.frameProcessor = fp
                let bridge = TsvbVideoFrameProcessorBridge(processor: fp)
                self.processorBridge = bridge

                self.state = .idle

                // Attach to track
                let attachResult = self.attachProcessorToTrack(trackId)

                return ["success": true, "status": "active", "attachResult": attachResult]
            } catch {
                self.state = .error
                return ["success": false, "error": error.localizedDescription]
            }
        }
    }

    // MARK: - Effects Control

    private func doEnableBlur(power: Float) async -> [String: Any] {
        return await onControlQueue {
            guard self.state == .idle || self.state == .active,
                  let pipeline = self.pipeline else {
                return ["success": false, "error": "SDK not initialized"]
            }

            let result = pipeline.enableBlurBackground(power: power)
            guard result == .ok else {
                return ["success": false, "error": "Failed to enable blur, result: \(result)"]
            }

            pipeline.disableReplaceBackground()
            pipeline.disableDenoiseBackground()
            self.blurEnabled = true
            self.replaceBackgroundEnabled = false
            self.replacementController = nil
            self.state = .active
            self.frameProcessor?.setActive(true)

            return ["success": true]
        }
    }

    private func doDisableBlur() async -> [String: Any] {
        return await onControlQueue {
            guard let pipeline = self.pipeline else {
                return ["success": true]
            }

            pipeline.disableBlurBackground()
            self.blurEnabled = false

            if !self.replaceBackgroundEnabled {
                self.state = .idle
                self.frameProcessor?.setActive(false)
            }

            return ["success": true]
        }
    }

    private func doEnableReplace(assetSource: [String: Any]?) async -> [String: Any] {
        guard state == .idle || state == .active, let pipeline = pipeline else {
            return ["success": false, "error": "SDK not initialized"]
        }

        // Load image outside control queue (potentially slow I/O)
        var backgroundImage: UIImage? = nil

        if let source = assetSource, let uri = source["uri"] as? String {
            backgroundImage = await loadImage(uri: uri)
            if backgroundImage == nil {
                return ["success": false, "error": "Failed to load image from: \(uri)"]
            }
        }

        return await onControlQueue {
            // Re-check state inside controlQueue — cleanup may have run during image download
            guard self.state == .idle || self.state == .active, self.pipeline != nil else {
                return ["success": false, "error": "SDK was cleaned up during image load"]
            }

            var controller: (any ReplacementController)? = nil
            let result = pipeline.enableReplaceBackground(&controller)

            guard result == .ok, let ctrl = controller else {
                return ["success": false, "error": "Failed to enable replace background"]
            }

            self.replacementController = ctrl

            if let image = backgroundImage {
                self.originalBackgroundImage = image
                self.applyBackgroundImage(image, to: ctrl)
            }

            pipeline.disableBlurBackground()
            pipeline.disableDenoiseBackground()
            self.blurEnabled = false
            self.replaceBackgroundEnabled = true
            self.state = .active
            self.frameProcessor?.setActive(true)

            return ["success": true]
        }
    }

    private func doDisableReplace() async -> [String: Any] {
        return await onControlQueue {
            guard let pipeline = self.pipeline else {
                return ["success": true]
            }

            pipeline.disableReplaceBackground()
            self.replaceBackgroundEnabled = false
            self.replacementController = nil
            self.originalBackgroundImage = nil

            if !self.blurEnabled {
                self.state = .idle
                self.frameProcessor?.setActive(false)
            }

            return ["success": true]
        }
    }

    // MARK: - Cleanup

    private func doCleanup() {
        controlQueue.sync {
            self.frameProcessor?.teardown()
            self.detachProcessorFromTrack()
            Self.cleanupCapturedFrames()

            if let pipeline = self.pipeline {
                pipeline.disableBlurBackground()
                pipeline.disableReplaceBackground()
                pipeline.disableDenoiseBackground()
            }

            self.pipeline = nil
            self.frameFactory = nil
            self.sdkFactory = nil
            self.frameProcessor = nil
            self.processorBridge = nil
            self.blurEnabled = false
            self.replaceBackgroundEnabled = false
            self.replacementController = nil
            self.state = .uninitialized
        }
    }

    deinit {
        frameProcessor?.teardown()
        // Don't call detachProcessorFromTrack in deinit — ObjC runtime calls are unsafe here
        if let pipeline = pipeline {
            pipeline.disableBlurBackground()
            pipeline.disableReplaceBackground()
            pipeline.disableDenoiseBackground()
        }
    }

    // MARK: - Track Attachment (via fork's direct API)

    @discardableResult
    private func attachProcessorToTrack(_ trackId: String) -> String {
        if attachedTrackId != nil && attachedTrackId != trackId {
            detachProcessorFromTrack()
        }

        guard let bridge = processorBridge else {
            NSLog("[VideoEffects Native] processorBridge is nil")
            return "error:no_processor_bridge"
        }

        // Register with ProcessorProvider (static class — works in both bridged and bridgeless mode)
        if let providerClass = NSClassFromString("ProcessorProvider") as? NSObject.Type {
            let addSelector = NSSelectorFromString("addProcessor:forName:")
            if providerClass.responds(to: addSelector) {
                providerClass.perform(addSelector, with: bridge, with: "tsvb")
                attachedTrackId = trackId
                NSLog("[VideoEffects Native] Registered processor with ProcessorProvider, trackId: \(trackId)")
                return "registered:\(trackId)"
            } else {
                NSLog("[VideoEffects Native] ProcessorProvider does not respond to addProcessor:forName:")
                return "error:no_add_method"
            }
        } else {
            NSLog("[VideoEffects Native] ProcessorProvider class not found")
            return "error:no_processor_provider"
        }
    }

    private func detachProcessorFromTrack() {
        guard attachedTrackId != nil else { return }

        if let providerClass = NSClassFromString("ProcessorProvider") as? NSObject.Type {
            let removeSelector = NSSelectorFromString("removeProcessor:")
            if providerClass.responds(to: removeSelector) {
                providerClass.perform(removeSelector, with: "tsvb")
            }
        }

        attachedTrackId = nil
    }

    // MARK: - Helpers

    private func onControlQueue<T>(closure: @escaping () -> T) async -> T {
        return await withCheckedContinuation { continuation in
            controlQueue.async {
                continuation.resume(returning: closure())
            }
        }
    }

    private func applyBackgroundImage(_ image: UIImage, to controller: any ReplacementController) {
        guard let factory = self.frameFactory else { return }

        if let data = image.jpegData(compressionQuality: 0.9),
           let frame = factory.image(with: data) {
            controller.background = frame
        } else if let data = image.pngData(),
                  let frame = factory.image(with: data) {
            controller.background = frame
        }
    }

    private func onControlQueue<T>(closure: @escaping () async -> T) async -> T {
        return await withCheckedContinuation { continuation in
            self.controlQueue.async {
                Task {
                    let result = await closure()
                    continuation.resume(returning: result)
                }
            }
        }
    }

    private func loadImage(uri: String) async -> UIImage? {
        var image: UIImage?

        if uri.hasPrefix("http://") || uri.hasPrefix("https://"),
           let url = URL(string: uri) {
            do {
                let (data, _) = try await URLSession.shared.data(from: url)
                image = UIImage(data: data)
            } catch {
                return nil
            }
        } else if uri.hasPrefix("file://") {
            image = UIImage(contentsOfFile: String(uri.dropFirst(7)))
        } else {
            let name = ((uri as NSString).lastPathComponent as NSString).deletingPathExtension
            image = UIImage(named: name)
        }

        return image
    }


    private static func cleanupCapturedFrames() {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("captured_frames", isDirectory: true)
        guard let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return }
        for file in files {
            try? FileManager.default.removeItem(at: file)
        }
    }

    private func authErrorMessage(_ status: AuthStatus) -> String {
        switch status {
        case .expired: return "License expired"
        case .inactive: return "License is inactive"
        default: return "Unknown authorization error"
        }
    }
}
