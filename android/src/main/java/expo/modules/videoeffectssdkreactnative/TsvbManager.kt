package expo.modules.videoeffectssdkreactnative

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import com.effectssdk.tsvb.Camera
import com.effectssdk.tsvb.EffectsSDK
import com.effectssdk.tsvb.EffectsSDKStatus
import com.effectssdk.tsvb.pipeline.CameraPipeline
import com.effectssdk.tsvb.pipeline.ColorCorrectionMode
import com.effectssdk.tsvb.pipeline.PipelineMode
import com.effectssdk.tsvb.pipeline.SegmentationMode
import java.io.File
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manages the Effects SDK lifecycle and CameraPipeline.
 * All pipeline mutations are synchronized via [lock].
 */
class TsvbManager(private val context: Context) {

    companion object {
        private const val TAG = "TsvbManager"
    }

    // State
    @Volatile var isInitialized = false
        private set
    @Volatile var isBlurEnabled = false
        private set
    @Volatile var isReplaceBackgroundEnabled = false
        private set

    /** True when CameraPipeline failed and capturer fell back to standard camera. Effects are unavailable for this session. */
    val isEffectsUnavailable: Boolean
        get() = tsvbCapturer?.isUsingFallback == true

    /** Whether the pipeline has been started and is currently running. */
    @Volatile var isPipelineRunning = false

    private val lock = Any()
    private var cameraPipeline: CameraPipeline? = null
    private var tsvbCapturer: TsvbCapturer? = null
    private val optionsCache = EffectsSdkOptionsCache()
    private var imageLoadExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Camera capture dimensions — set from actual frame output
    @Volatile var captureWidth = 0
        private set
    @Volatile var captureHeight = 0
        private set

    // Original background bitmap (before crop/resize) for re-apply on dimension change
    private var originalBackgroundBitmap: Bitmap? = null

    fun setCaptureSize(width: Int, height: Int) {
        val changed = captureWidth != width || captureHeight != height
        captureWidth = width
        captureHeight = height

        // Re-apply background if dimensions changed (orientation change)
        if (changed && isReplaceBackgroundEnabled && originalBackgroundBitmap != null) {
            imageLoadExecutor.submit {
                synchronized(lock) {
                    val original = originalBackgroundBitmap ?: return@submit
                    val fitted = centerCropAndResize(original, width, height)
                    optionsCache.backgroundBitmap?.recycle()
                    cameraPipeline?.setBackground(fitted)
                    optionsCache.backgroundBitmap = fitted
                }
            }
        }
    }

    // MARK: - Initialization

    fun initialize(customerID: String, trackId: String, callback: (Map<String, Any>) -> Unit) {
        synchronized(lock) {
            if (isInitialized) {
                callback(mapOf("success" to true, "status" to "already_initialized"))
                return
            }
        }

        try {
            EffectsSDK.initialize(context, customerID) { status ->
                when (status) {
                    EffectsSDKStatus.ACTIVE -> {
                        val factoryRegistered: Boolean
                        synchronized(lock) {
                            isInitialized = true
                            factoryRegistered = registerCapturerFactory()
                        }
                        Log.d(TAG, "Effects SDK initialized successfully, factory=$factoryRegistered")
                        callback(mapOf(
                            "success" to true,
                            "status" to "active",
                            "capturerFactoryRegistered" to factoryRegistered,
                        ))
                    }
                    else -> {
                        Log.e(TAG, "Effects SDK initialization failed: $status")
                        callback(mapOf("success" to false, "error" to "SDK status: $status"))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Effects SDK initialization error", e)
            callback(mapOf("success" to false, "error" to e.message.orEmpty()))
        }
    }

    // MARK: - Effects Control

    fun enableBlurBackground(power: Float, callback: (Map<String, Any>) -> Unit) {
        synchronized(lock) {
            val pipeline = cameraPipeline
            if (pipeline == null) {
                callback(mapOf("success" to false, "error" to "Pipeline not created yet"))
                return
            }

            try {
                pipeline.setMode(PipelineMode.BLUR)
                pipeline.setBlurPower(power)
                optionsCache.pipelineMode = PipelineMode.BLUR
                optionsCache.blurPower = power
                isBlurEnabled = true
                isReplaceBackgroundEnabled = false
                callback(mapOf("success" to true))
            } catch (e: Exception) {
                callback(mapOf("success" to false, "error" to e.message.orEmpty()))
            }
        }
    }

    fun disableBlurBackground(callback: (Map<String, Any>) -> Unit) {
        synchronized(lock) {
            val pipeline = cameraPipeline
            if (pipeline == null) {
                callback(mapOf("success" to true))
                return
            }

            try {
                pipeline.setMode(PipelineMode.NO_EFFECT)
                optionsCache.pipelineMode = PipelineMode.NO_EFFECT
                isBlurEnabled = false
                isReplaceBackgroundEnabled = false
                callback(mapOf("success" to true))
            } catch (e: Exception) {
                callback(mapOf("success" to false, "error" to e.message.orEmpty()))
            }
        }
    }

    fun enableReplaceBackground(assetSource: Map<String, Any>?, callback: (Map<String, Any>) -> Unit) {
        synchronized(lock) {
            val pipeline = cameraPipeline
            if (pipeline == null) {
                callback(mapOf("success" to false, "error" to "Pipeline not created yet"))
                return
            }

            try {
                pipeline.setMode(PipelineMode.REPLACE)
                optionsCache.pipelineMode = PipelineMode.REPLACE

                // Load background image if provided
                if (assetSource != null) {
                    val uri = assetSource["uri"] as? String
                    if (uri != null) {
                        imageLoadExecutor.submit {
                            try {
                                val raw = loadBitmapFromUri(uri)
                                if (raw != null) {
                                    val targetW = if (captureWidth > 0) captureWidth else 720
                                    val targetH = if (captureHeight > 0) captureHeight else 1280
                                    val fitted = centerCropAndResize(raw, targetW, targetH)
                                    synchronized(lock) {
                                        originalBackgroundBitmap = raw  // keep original for re-apply on rotation
                                        optionsCache.backgroundBitmap?.recycle()
                                        cameraPipeline?.setBackground(fitted)
                                        optionsCache.backgroundBitmap = fitted
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to load background image", e)
                            }
                        }
                    }
                }

                isReplaceBackgroundEnabled = true
                isBlurEnabled = false
                callback(mapOf("success" to true))
            } catch (e: Exception) {
                callback(mapOf("success" to false, "error" to e.message.orEmpty()))
            }
        }
    }

    fun disableReplaceBackground(callback: (Map<String, Any>) -> Unit) {
        synchronized(lock) {
            val pipeline = cameraPipeline
            if (pipeline == null) {
                callback(mapOf("success" to true))
                return
            }

            try {
                pipeline.setMode(PipelineMode.NO_EFFECT)
                optionsCache.pipelineMode = PipelineMode.NO_EFFECT
                isReplaceBackgroundEnabled = false
                callback(mapOf("success" to true))
            } catch (e: Exception) {
                callback(mapOf("success" to false, "error" to e.message.orEmpty()))
            }
        }
    }

    // MARK: - Pipeline Lifecycle (called by TsvbCapturer)
    //
    // Key design: ONE CameraPipeline per session. Created on first startCapture,
    // reused across stop/start cycles. Only released on dispose/cleanup.
    // This avoids SIGSEGV crashes from rapid pipeline create/destroy cycles
    // (SDK's ExternalTexture GL thread doesn't survive rapid recreation).

    // Last dimensions used to create pipeline — for detecting resolution changes
    private var pipelineWidth = 0
    private var pipelineHeight = 0

    /**
     * Returns existing pipeline or creates a new one if none exists.
     * Camera changes are NOT handled here — LiveKit dispatches them via the
     * `WebRTCModule.switchCamera` bridge → `TsvbCapturer.switchCamera` →
     * `manager.switchCamera` → `pipeline.switchCamera()` (in-place). Doing
     * release+create here AND letting the bridge run in-place on the fresh
     * pipeline produces GL thread chaos and `attachToGLContext` crashes.
     */
    fun getOrCreatePipeline(width: Int, height: Int, cameraName: String): CameraPipeline? {
        if (width <= 0 || height <= 0) {
            Log.e(TAG, "Invalid pipeline dimensions: ${width}x${height}")
            return null
        }
        synchronized(lock) {
            val existing = cameraPipeline
            if (existing != null) {
                if (width != pipelineWidth || height != pipelineHeight) {
                    existing.setResolution(Size(width, height))
                    pipelineWidth = width
                    pipelineHeight = height
                    Log.d(TAG, "Reusing pipeline, updated resolution to ${width}x${height}")
                } else {
                    Log.d(TAG, "Reusing existing pipeline")
                }
                return existing
            }

            try {
                val factory = EffectsSDK.createSDKFactory()
                val camera = detectCamera(cameraName)
                val emptyBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                val pipeline = factory.createCameraPipeline(
                    context,
                    optionsCache.pipelineMode,
                    SegmentationMode.AUTO,
                    optionsCache.colorCorrectionMode,
                    optionsCache.backgroundBitmap ?: emptyBitmap,
                    optionsCache.colorGradingReference ?: emptyBitmap,
                    0, // segmentationGap
                    0, // faceDetectionGap
                    optionsCache.blurPower,
                    optionsCache.beautificationPower,
                    optionsCache.isBeautificationEnabled,
                    null, // FPSListener
                    null, // OrientationChangeListener
                    Size(width, height),
                    camera
                )
                cameraPipeline = pipeline
                pipelineWidth = width
                pipelineHeight = height
                Log.d(TAG, "Created new pipeline: ${width}x${height}, camera=$camera")
                return pipeline
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create pipeline", e)
                return null
            }
        }
    }

    /** Switch camera using SDK's built-in method — no pipeline recreate. */
    fun switchCamera(cameraName: String) {
        synchronized(lock) {
            val pipeline = cameraPipeline ?: return
            val camera = detectCamera(cameraName)
            pipeline.switchCamera(camera)
            Log.d(TAG, "Pipeline switchCamera to: $camera")
        }
    }

    /**
     * Called when a TsvbCapturer stops or is disposed.
     * Detaches the frame listener so frames stop flowing to the dead capturer,
     * but keeps the pipeline (and its GL thread) alive.
     *
     * Why not stopPipeline(): MediaPipe's ExternalTextureConverter cannot
     * survive stopPipeline → startPipeline cycles — the SurfaceTexture's GL
     * context becomes invalid and `attachToGLContext` crashes on the second
     * restart (FATAL EXCEPTION in GlThread). Keeping the GL thread alive and
     * only swapping the listener avoids that. Pipeline is fully released
     * via releasePipeline()/cleanup() only.
     */
    fun onCapturerStopped() {
        synchronized(lock) {
            cameraPipeline?.setOnFrameAvailableListener(null)
            Log.d(TAG, "Pipeline listener detached (capturer stopped)")
        }
    }

    /** Release pipeline fully — only called from cleanup. */
    fun releasePipeline() {
        synchronized(lock) {
            if (isPipelineRunning) {
                cameraPipeline?.stopPipeline()
                isPipelineRunning = false
            }
            cameraPipeline?.release()
            cameraPipeline = null
            Log.d(TAG, "Pipeline released")
        }
    }

    // MARK: - Capturer Registration (via reflection to avoid compile-time dependency on fork)

    private fun registerCapturerFactory(): Boolean {
        try {
            val providerClass = Class.forName("com.oney.WebRTCModule.videoEffects.CapturerProvider")
            val factoryInterface = Class.forName("com.oney.WebRTCModule.videoEffects.CapturerFactoryInterface")

            // Create a dynamic proxy implementing CapturerFactoryInterface
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                factoryInterface.classLoader,
                arrayOf(factoryInterface)
            ) { _, method, args ->
                if (method.name == "createCapturer" && args != null && args.size == 3) {
                    val cameraName = args[0] as String
                    @Suppress("UNCHECKED_CAST")
                    val eventsHandler = args[1] as org.webrtc.CameraVideoCapturer.CameraEventsHandler
                    val enumerator = args[2] as org.webrtc.CameraEnumerator
                    Log.d(TAG, "CapturerProvider creating TsvbCapturer for: $cameraName")
                    val capturer = TsvbCapturer(cameraName, eventsHandler, enumerator, this)
                    tsvbCapturer = capturer
                    applyFrameCaptureState(capturer)
                    capturer
                } else {
                    null
                }
            }

            val setFactoryMethod = providerClass.getMethod("setFactory", factoryInterface)
            setFactoryMethod.invoke(null, proxy)
            Log.d(TAG, "CapturerProvider factory registered")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register capturer factory — react-native-webrtc fork may not have CapturerProvider", e)
            return false
        }
    }

    private fun unregisterCapturerFactory() {
        try {
            val providerClass = Class.forName("com.oney.WebRTCModule.videoEffects.CapturerProvider")
            val removeMethod = providerClass.getMethod("removeFactory")
            removeMethod.invoke(null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister capturer factory", e)
        }
    }

    // MARK: - Frame Capture

    private var frameCaptureIntervalMs: Long = 0
    private var frameCaptureCallback: ((String, Int, Int, Double) -> Unit)? = null
    private var frameCaptureExecutor: ExecutorService? = null

    fun startFrameCapture(intervalMs: Long, onCaptured: (String, Int, Int, Double) -> Unit) {
        synchronized(lock) {
            frameCaptureIntervalMs = intervalMs
            frameCaptureCallback = onCaptured
            if (frameCaptureExecutor == null) {
                frameCaptureExecutor = Executors.newSingleThreadExecutor()
            }
            applyFrameCaptureState(tsvbCapturer)
        }
    }

    fun stopFrameCapture() {
        synchronized(lock) {
            tsvbCapturer?.stopFrameCapture()
            frameCaptureCallback = null
            frameCaptureIntervalMs = 0
            frameCaptureExecutor?.shutdownNow()
            frameCaptureExecutor = null
            cleanupCapturedFrames()
        }
    }

    internal fun applyFrameCaptureState(capturer: TsvbCapturer?) {
        val callback = frameCaptureCallback ?: return
        val executor = frameCaptureExecutor ?: return
        capturer?.onFrameCaptured = { filePath, width, height, timestamp ->
            callback(filePath, width, height, timestamp)
        }
        capturer?.startFrameCapture(frameCaptureIntervalMs, executor)
    }

    private fun cleanupCapturedFrames() {
        try {
            val dir = File(context.cacheDir, "captured_frames")
            if (dir.exists()) {
                dir.listFiles()?.forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup captured frames", e)
        }
    }

    // MARK: - Cleanup

    fun cleanup() {
        imageLoadExecutor.shutdownNow()
        imageLoadExecutor = Executors.newSingleThreadExecutor()
        synchronized(lock) {
            frameCaptureCallback = null
            frameCaptureIntervalMs = 0
            frameCaptureExecutor?.shutdownNow()
            frameCaptureExecutor = null
            cleanupCapturedFrames()
            unregisterCapturerFactory()
            tsvbCapturer?.dispose()
            tsvbCapturer = null
            releasePipeline()
            isInitialized = false
            isBlurEnabled = false
            isReplaceBackgroundEnabled = false
            originalBackgroundBitmap = null
            optionsCache.reset()
        }
    }

    // MARK: - Helpers

    private fun detectCamera(deviceName: String): Camera {
        return try {
            if (deviceName.contains("front", ignoreCase = true) || deviceName == "1") {
                Camera.FRONT
            } else {
                Camera.BACK
            }
        } catch (e: Exception) {
            Camera.FRONT
        }
    }

    private fun loadBitmapFromUri(uri: String): Bitmap? {
        return try {
            if (uri.startsWith("http://") || uri.startsWith("https://")) {
                val connection = URL(uri).openConnection()
                connection.connect()
                val inputStream = connection.getInputStream()
                android.graphics.BitmapFactory.decodeStream(inputStream)
            } else if (uri.startsWith("file://")) {
                val path = uri.removePrefix("file://")
                android.graphics.BitmapFactory.decodeFile(path)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap from: $uri", e)
            null
        }
    }

    private fun centerCropAndResize(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val targetRatio = targetWidth.toFloat() / targetHeight.toFloat()
        val imageRatio = bitmap.width.toFloat() / bitmap.height.toFloat()

        // Center-crop to target aspect ratio
        val cropWidth: Int
        val cropHeight: Int
        if (imageRatio > targetRatio) {
            cropHeight = bitmap.height
            cropWidth = (bitmap.height * targetRatio).toInt()
        } else {
            cropWidth = bitmap.width
            cropHeight = (bitmap.width / targetRatio).toInt()
        }

        val xOffset = (bitmap.width - cropWidth) / 2
        val yOffset = (bitmap.height - cropHeight) / 2
        val cropped = Bitmap.createBitmap(bitmap, xOffset, yOffset, cropWidth, cropHeight)

        // Scale to exact target dimensions
        val scaled = Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
        if (scaled !== cropped) cropped.recycle()
        return scaled
    }
}
