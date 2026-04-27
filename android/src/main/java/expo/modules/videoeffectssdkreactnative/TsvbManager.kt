package expo.modules.videoeffectssdkreactnative

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import com.effectssdk.tsvb.Camera
import com.effectssdk.tsvb.EffectsSDK
import com.effectssdk.tsvb.EffectsSDKStatus
import com.effectssdk.tsvb.pipeline.CameraPipeline
import com.effectssdk.tsvb.pipeline.PipelineMode
import java.io.File
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manages the Effects SDK lifecycle and CameraPipeline.
 *
 * Threading: the SDK manages its own GL/EGL thread internally (since 2.14, via
 * `createCameraPipelineAsync`). [lock] only guards OUR mutable state (cameraPipeline ref,
 * dimensions, options cache, capturer ref) — pipeline method calls themselves run outside
 * the lock to avoid deadlocking with frame-listener callbacks that re-enter our state.
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
    // Read from the JS bridge thread (isEffectsUnavailable getter) and the WebRTC factory
    // thread (createCapturer); written inside synchronized(lock) on factory invocations.
    @Volatile
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

        // Re-apply background if dimensions changed (orientation change). Bitmap re-fit
        // happens on the executor; the actual setBackground call goes directly to the SDK
        // which dispatches internally to its own GL thread.
        if (changed && isReplaceBackgroundEnabled && originalBackgroundBitmap != null) {
            imageLoadExecutor.submit {
                val original = synchronized(lock) { originalBackgroundBitmap } ?: return@submit
                val fitted = centerCropAndResize(original, width, height)
                synchronized(lock) {
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
        val pipeline: CameraPipeline = synchronized(lock) {
            val p = cameraPipeline
            if (p == null) {
                callback(mapOf("success" to false, "error" to "Pipeline not created yet"))
                return
            }
            optionsCache.pipelineMode = PipelineMode.BLUR
            optionsCache.blurPower = power
            isBlurEnabled = true
            isReplaceBackgroundEnabled = false
            p
        }
        try {
            pipeline.setMode(PipelineMode.BLUR)
            pipeline.setBlurPower(power)
            callback(mapOf("success" to true))
        } catch (e: Exception) {
            callback(mapOf("success" to false, "error" to e.message.orEmpty()))
        }
    }

    fun disableBlurBackground(callback: (Map<String, Any>) -> Unit) {
        val pipeline: CameraPipeline = synchronized(lock) {
            val p = cameraPipeline
            if (p == null) {
                callback(mapOf("success" to true))
                return
            }
            optionsCache.pipelineMode = PipelineMode.NO_EFFECT
            isBlurEnabled = false
            isReplaceBackgroundEnabled = false
            p
        }
        try {
            pipeline.setMode(PipelineMode.NO_EFFECT)
            callback(mapOf("success" to true))
        } catch (e: Exception) {
            callback(mapOf("success" to false, "error" to e.message.orEmpty()))
        }
    }

    fun enableReplaceBackground(assetSource: Map<String, Any>?, callback: (Map<String, Any>) -> Unit) {
        val pipeline: CameraPipeline = synchronized(lock) {
            val p = cameraPipeline
            if (p == null) {
                callback(mapOf("success" to false, "error" to "Pipeline not created yet"))
                return
            }
            optionsCache.pipelineMode = PipelineMode.REPLACE
            isReplaceBackgroundEnabled = true
            isBlurEnabled = false
            p
        }
        try {
            pipeline.setMode(PipelineMode.REPLACE)
            callback(mapOf("success" to true))
        } catch (e: Exception) {
            callback(mapOf("success" to false, "error" to e.message.orEmpty()))
            return
        }

        // Background loading happens off the calling thread; the actual setBackground
        // call goes directly to the SDK which dispatches to its own GL thread.
        val uri = (assetSource?.get("uri") as? String) ?: return
        imageLoadExecutor.submit {
            try {
                val raw = loadBitmapFromUri(uri) ?: return@submit
                val targetW = if (captureWidth > 0) captureWidth else 720
                val targetH = if (captureHeight > 0) captureHeight else 1280
                val fitted = centerCropAndResize(raw, targetW, targetH)
                synchronized(lock) {
                    originalBackgroundBitmap = raw
                    optionsCache.backgroundBitmap?.recycle()
                    cameraPipeline?.setBackground(fitted)
                    optionsCache.backgroundBitmap = fitted
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load background image", e)
            }
        }
    }

    fun disableReplaceBackground(callback: (Map<String, Any>) -> Unit) {
        val pipeline: CameraPipeline = synchronized(lock) {
            val p = cameraPipeline
            if (p == null) {
                callback(mapOf("success" to true))
                return
            }
            optionsCache.pipelineMode = PipelineMode.NO_EFFECT
            isReplaceBackgroundEnabled = false
            p
        }
        try {
            pipeline.setMode(PipelineMode.NO_EFFECT)
            callback(mapOf("success" to true))
        } catch (e: Exception) {
            callback(mapOf("success" to false, "error" to e.message.orEmpty()))
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
     * Returns existing pipeline synchronously, or creates one ASYNCHRONOUSLY via
     * `factory.createCameraPipelineAsync` and delivers it via [onReady]. Returning
     * `null` from this function while invoking the callback later is intentional —
     * callers MUST handle the callback path.
     *
     * Why async + no GL-thread marshalling: TSVB SDK 2.14+ provides
     * `createCameraPipelineAsync` which runs all GL/Camera2 init on the SDK's own
     * dedicated thread (with its own EGL context current). The earlier sync API
     * required us to marshal onto WebRTC's `SurfaceTextureHelper.handler` to avoid
     * `EGL_BAD_DISPLAY`, but that thread carries WebRTC's shared EGL context — wrong
     * one for MediaPipe's needs. On a JOIN flow the WebRTC GL thread is also busy
     * rendering remote frames, which adds queueing latency and exposes timing windows
     * that silently break MediaPipe's frame-listener wiring (Pixel 10 / Android 16
     * symptom: `startPipeline()` returns success but listener never fires).
     *
     * Camera changes are NOT handled here — LiveKit dispatches them via the
     * `WebRTCModule.switchCamera` bridge → `TsvbCapturer.switchCamera` →
     * `manager.switchCamera` → `pipeline.switchCamera()` (in-place).
     */
    fun getOrCreatePipelineAsync(
        width: Int,
        height: Int,
        cameraName: String,
        onReady: (CameraPipeline?) -> Unit,
    ) {
        if (width <= 0 || height <= 0) {
            Log.e(TAG, "Invalid pipeline dimensions: ${width}x${height}")
            onReady(null)
            return
        }
        // Once we've fallen back this session, don't keep retrying SDK init on every
        // restart — the SDK already declared itself unavailable. Only a fresh cleanup()
        // can reset the fallback flag (clears tsvbCapturer reference).
        if (tsvbCapturer?.isUsingFallback == true) {
            Log.d(TAG, "Skipping pipeline create — capturer is already on fallback")
            onReady(null)
            return
        }
        // Fast path: existing pipeline can be reused without an async hop.
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
                onReady(existing)
                return
            }
        }

        try {
            Log.i(TAG, "Calling EffectsSDK.createSDKFactory()")
            val factory = EffectsSDK.createSDKFactory()
            val camera = detectCamera(cameraName)
            // Initial mode REPLACE (not NO_EFFECT) per EffectsSDK Flutter fork pattern —
            // NO_EFFECT may skip spinning up the segmentation worker chain that drives
            // the frame listener on some devices. Real options applied after pipeline ready.
            val initialMode = if (optionsCache.pipelineMode == PipelineMode.NO_EFFECT) {
                PipelineMode.REPLACE
            } else {
                optionsCache.pipelineMode
            }
            Log.i(TAG, "Calling factory.createCameraPipelineAsync(${width}x${height}, camera=$camera, initialMode=$initialMode)")
            factory.createCameraPipelineAsync(
                context,
                camera = camera,
                resolution = Size(width, height),
                mode = initialMode,
            ) { pipeline ->
                if (pipeline == null) {
                    Log.e(TAG, "createCameraPipelineAsync returned null pipeline")
                    onReady(null)
                    return@createCameraPipelineAsync
                }
                synchronized(lock) {
                    cameraPipeline = pipeline
                    pipelineWidth = width
                    pipelineHeight = height
                }
                // Apply options OUTSIDE the lock — SDK setters may dispatch internally,
                // and the SDK's frame listener may already start firing and re-enter
                // our lock via manager.setCaptureSize(). Holding the lock across SDK
                // calls would deadlock.
                applyCachedOptionsToPipeline(pipeline)
                Log.i(TAG, "Created new pipeline (async): ${width}x${height}, camera=$camera")
                onReady(pipeline)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create pipeline (async)", e)
            onReady(null)
        }
    }

    /**
     * Applies cached pipeline options. Called once after async pipeline creation —
     * the create call uses a default initial mode (REPLACE) so options must be
     * synced afterward, including the user's intended `pipelineMode`.
     */
    private fun applyCachedOptionsToPipeline(pipeline: CameraPipeline) {
        try {
            pipeline.setMode(optionsCache.pipelineMode)
            pipeline.setBlurPower(optionsCache.blurPower)
            pipeline.setColorCorrectionMode(optionsCache.colorCorrectionMode)
            pipeline.enableBeautification(optionsCache.isBeautificationEnabled)
            pipeline.setBeautificationPower(optionsCache.beautificationPower)
            optionsCache.backgroundBitmap?.let { pipeline.setBackground(it) }
            optionsCache.colorGradingReference?.let {
                pipeline.setColorGradingReferenceImage(it)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to apply cached options to pipeline", e)
        }
    }

    /** Switch camera using SDK's built-in method — no pipeline recreate. SDK dispatches internally. */
    fun switchCamera(cameraName: String) {
        synchronized(lock) {
            val pipeline = cameraPipeline ?: return
            val camera = detectCamera(cameraName)
            try {
                pipeline.switchCamera(camera)
                Log.d(TAG, "Pipeline switchCamera to: $camera")
            } catch (e: Throwable) {
                Log.e(TAG, "Pipeline switchCamera failed", e)
            }
        }
    }

    /**
     * Called when a TsvbCapturer stops or is disposed.
     * Detaches the frame listener so frames stop flowing to the dead capturer,
     * but keeps the pipeline alive (SDK manages its own GL thread internally).
     *
     * Why not stopPipeline(): MediaPipe's ExternalTextureConverter cannot
     * survive stopPipeline → startPipeline cycles on some drivers (SurfaceTexture
     * GL context becomes invalid, `attachToGLContext` crashes on second restart).
     * Pipeline is fully released via releasePipeline()/cleanup() only.
     */
    fun onCapturerStopped() {
        synchronized(lock) {
            try {
                cameraPipeline?.setOnFrameAvailableListener(null)
                Log.d(TAG, "Pipeline listener detached (capturer stopped)")
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to detach pipeline listener", e)
            }
        }
    }

    /** Release pipeline fully — only called from cleanup. SDK dispatches GL ops internally. */
    fun releasePipeline() {
        synchronized(lock) {
            try {
                if (isPipelineRunning) {
                    cameraPipeline?.stopPipeline()
                    isPipelineRunning = false
                }
                cameraPipeline?.release()
                Log.d(TAG, "Pipeline released")
            } catch (e: Throwable) {
                Log.e(TAG, "Pipeline release failed", e)
            } finally {
                cameraPipeline = null
            }
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
                    // Atomic swap-and-dispose under lock so a previous live capturer (e.g. mid
                    // camera switch flow) is properly torn down instead of being clobbered.
                    val previous = synchronized(lock) {
                        val prev = tsvbCapturer
                        tsvbCapturer = capturer
                        prev
                    }
                    previous?.dispose()
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

        // Release pipeline first — SDK manages its own GL thread internally so this is
        // safe to call from any thread regardless of capturer state.
        releasePipeline()

        synchronized(lock) {
            frameCaptureCallback = null
            frameCaptureIntervalMs = 0
            frameCaptureExecutor?.shutdownNow()
            frameCaptureExecutor = null
            cleanupCapturedFrames()
            unregisterCapturerFactory()
            tsvbCapturer?.dispose()
            tsvbCapturer = null
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
