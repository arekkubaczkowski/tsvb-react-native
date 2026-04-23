package expo.modules.videoeffectssdkreactnative

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.effectssdk.tsvb.pipeline.OnFrameAvailableListener
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.CapturerObserver
import org.webrtc.NV21Buffer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService

/**
 * Custom VideoCapturer that uses Effects SDK's CameraPipeline.
 * CameraPipeline owns the camera and delivers processed Bitmap frames
 * via OnFrameAvailableListener.
 *
 * Threading:
 * - [capturerObserver] is set once during initialize() and never changes
 * - Pipeline callbacks arrive on the SDK's internal thread
 * - [isPipelineActive] is volatile for cross-thread visibility
 * - NV21 buffer is pre-allocated and reused (same resolution = same size)
 */
class TsvbCapturer(
    private var device: String,
    private val eventsHandler: CameraVideoCapturer.CameraEventsHandler,
    private val enumerator: CameraEnumerator,
    private val manager: TsvbManager
) : CameraVideoCapturer {

    companion object {
        private const val TAG = "TsvbCapturer"
    }

    private var capturerObserver: CapturerObserver? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var context: Context? = null

    @Volatile
    private var isPipelineActive = false

    // Fallback: standard camera capturer used if Effects SDK pipeline fails
    private var fallbackCapturer: CameraVideoCapturer? = null
    @Volatile
    var isUsingFallback = false
        private set

    private var currentWidth = 1280
    private var currentHeight = 720
    private var currentFps = 30

    // Pre-allocated buffers for frame conversion (reused across frames)
    private var nv21Buffer: ByteArray? = null
    private var argbBuffer: IntArray? = null
    private var nv21Width = 0
    private var nv21Height = 0

    // Frame dropping: skip frame if previous is still being processed
    @Volatile
    private var isProcessingFrame = false

    // One-time log when first frame arrives — diagnoses pipeline-started-but-stuck cases
    @Volatile
    private var hasLoggedFirstFrame = false

    // Frame capture
    @Volatile
    var isFrameCaptureEnabled = false
        private set
    @Volatile
    private var captureIntervalMs: Long = 5000
    @Volatile
    private var lastCaptureTimeMs: Long = 0
    var onFrameCaptured: ((filePath: String, width: Int, height: Int, timestamp: Double) -> Unit)? = null
    private var captureExecutor: ExecutorService? = null
    @Volatile
    private var lastCapturedFilePath: String? = null

    fun startFrameCapture(intervalMs: Long, executor: ExecutorService) {
        captureIntervalMs = intervalMs
        lastCaptureTimeMs = 0
        captureExecutor = executor
        isFrameCaptureEnabled = true
    }

    fun stopFrameCapture() {
        isFrameCaptureEnabled = false
        captureExecutor = null
    }

    // Frame listener for CameraPipeline output
    private val frameListener = OnFrameAvailableListener { bitmap, timestamp ->
        if (!isPipelineActive) return@OnFrameAvailableListener
        val observer = capturerObserver ?: return@OnFrameAvailableListener

        if (!hasLoggedFirstFrame) {
            hasLoggedFirstFrame = true
            Log.i(TAG, "First frame received: ${bitmap.width}x${bitmap.height}")
            TsvbLogBridge.info(
                TAG,
                "First frame received",
                mapOf("width" to bitmap.width, "height" to bitmap.height),
            )
        }

        // Drop frame if previous conversion is still in progress (prevents backpressure lag)
        if (isProcessingFrame) return@OnFrameAvailableListener
        isProcessingFrame = true

        try {
            val width = bitmap.width
            val height = bitmap.height

            // Report actual output dimensions to manager (for background image sizing)
            if (width != manager.captureWidth || height != manager.captureHeight) {
                manager.setCaptureSize(width, height)
                Log.d(TAG, "Capture size updated: ${width}x${height}")
            }

            // Periodic frame capture
            if (isFrameCaptureEnabled) {
                val nowMs = System.currentTimeMillis()
                if (lastCaptureTimeMs == 0L || (nowMs - lastCaptureTimeMs) >= captureIntervalMs) {
                    lastCaptureTimeMs = nowMs
                    saveBitmapAsJpeg(bitmap, width, height)
                }
            }

            val flip = isFrontFacing()

            val nv21 = getNv21Buffer(width, height)
            val argb = getArgbBuffer(width, height)
            bitmap.getPixels(argb, 0, width, 0, 0, width, height)
            argbToNv21(argb, nv21, width, height, flip)

            val buffer = NV21Buffer(nv21, width, height, null)
            val frame = VideoFrame(buffer, 0, timestamp * 1_000_000)
            observer.onFrameCaptured(frame)
            frame.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
        } finally {
            isProcessingFrame = false
        }
    }

    private fun saveBitmapAsJpeg(bitmap: Bitmap, width: Int, height: Int) {
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
        val executor = captureExecutor ?: return
        executor.submit {
            try {
                lastCapturedFilePath?.let { File(it).delete() }

                val timestamp = System.currentTimeMillis().toDouble()
                val dir = File(context?.cacheDir, "captured_frames")
                dir.mkdirs()
                val file = File(dir, "frame_${timestamp.toLong()}.jpg")
                FileOutputStream(file).use { out ->
                    copy.compress(Bitmap.CompressFormat.JPEG, 80, out)
                }
                copy.recycle()
                lastCapturedFilePath = file.absolutePath
                onFrameCaptured?.invoke(file.absolutePath, width, height, timestamp)
            } catch (e: Exception) {
                copy.recycle()
                Log.e(TAG, "Failed to save captured frame", e)
            }
        }
    }

    // MARK: - CameraVideoCapturer implementation

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        context: Context?,
        observer: CapturerObserver?
    ) {
        this.surfaceTextureHelper = surfaceTextureHelper
        this.context = context
        this.capturerObserver = observer
        Log.d(TAG, "Initialized with device: $device")
    }

    override fun startCapture(width: Int, height: Int, fps: Int) {
        currentWidth = width
        currentHeight = height
        currentFps = fps

        // Clean up previous fallback if any
        stopFallbackCapturer()

        Log.d(TAG, "startCapture: ${width}x${height}@${fps}fps, device=$device")
        TsvbLogBridge.info(
            TAG,
            "startCapture",
            mapOf("width" to width, "height" to height, "fps" to fps, "device" to device),
        )

        val pipeline = manager.getOrCreatePipeline(width, height, device)
        if (pipeline != null) {
            pipeline.setOnFrameAvailableListener(frameListener)
            // Only call startPipeline on first creation — pipeline stays running across stop/start
            if (!manager.isPipelineRunning) {
                try {
                    Log.i(TAG, "Calling pipeline.startPipeline()")
                    TsvbLogBridge.info(TAG, "Calling pipeline.startPipeline()")
                    pipeline.startPipeline()
                    manager.isPipelineRunning = true
                    Log.i(TAG, "Pipeline started (first time)")
                    TsvbLogBridge.info(TAG, "Pipeline started (first time)")
                } catch (e: Throwable) {
                    Log.e(TAG, "pipeline.startPipeline() threw — releasing and falling back", e)
                    TsvbLogBridge.error(
                        TAG,
                        "pipeline.startPipeline() threw — releasing and falling back",
                        e,
                    )
                    pipeline.setOnFrameAvailableListener(null)
                    manager.releasePipeline()
                    isPipelineActive = false
                    isUsingFallback = true
                    startFallbackCapturer(width, height, fps)
                    return
                }
            } else {
                Log.d(TAG, "Pipeline already running, reattached listener")
            }
            isPipelineActive = true
            isUsingFallback = false
            eventsHandler.onCameraOpening(device)
            Log.i(TAG, "onCameraOpening dispatched to LiveKit")
            TsvbLogBridge.info(TAG, "onCameraOpening dispatched to LiveKit")
        } else {
            Log.e(TAG, "Effects SDK pipeline failed — falling back to standard camera")
            TsvbLogBridge.warn(TAG, "Effects SDK pipeline failed — falling back to standard camera")
            isUsingFallback = true
            startFallbackCapturer(width, height, fps)
        }
    }

    override fun stopCapture() {
        Log.d(TAG, "stopCapture")
        isPipelineActive = false
        manager.onCapturerStopped()
        stopFallbackCapturer()
    }

    override fun changeCaptureFormat(width: Int, height: Int, fps: Int) {
        Log.d(TAG, "changeCaptureFormat: ${width}x${height}@${fps}fps")
        stopCapture()
        startCapture(width, height, fps)
    }

    override fun dispose() {
        Log.d(TAG, "dispose")
        isPipelineActive = false
        manager.onCapturerStopped()
        fallbackCapturer?.dispose()
        fallbackCapturer = null
        capturerObserver = null
        surfaceTextureHelper = null
        context = null
    }

    override fun isScreencast(): Boolean = false

    // MARK: - Camera switching

    override fun switchCamera(handler: CameraVideoCapturer.CameraSwitchHandler?) {
        val deviceNames = enumerator.getDeviceNames()
        val currentIsFront = isFrontFacing()

        for (name in deviceNames) {
            try {
                val nameIsFront = enumerator.isFrontFacing(name)
                if (nameIsFront != currentIsFront) {
                    switchCamera(handler, name)
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking device $name", e)
            }
        }

        handler?.onCameraSwitchError("No opposite camera found")
    }

    override fun switchCamera(handler: CameraVideoCapturer.CameraSwitchHandler?, deviceName: String?) {
        if (deviceName == null) {
            handler?.onCameraSwitchError("Device name is null")
            return
        }

        // Skip no-op switch to same device
        if (deviceName == device) {
            Log.d(TAG, "switchCamera skipped — already on device: $deviceName")
            handler?.onCameraSwitchDone(isFrontFacing())
            return
        }

        Log.d(TAG, "switchCamera to: $deviceName")

        // If using fallback, delegate switch to fallback capturer
        if (isUsingFallback && fallbackCapturer != null) {
            fallbackCapturer?.switchCamera(handler, deviceName)
            device = deviceName
            return
        }

        device = deviceName
        manager.switchCamera(deviceName)
        handler?.onCameraSwitchDone(isFrontFacing())
        Log.d(TAG, "Camera switched to: $deviceName")
    }

    fun getCurrentDevice(): String = device

    // MARK: - Fallback

    private fun stopFallbackCapturer() {
        fallbackCapturer?.stopCapture()
        fallbackCapturer?.dispose()
        fallbackCapturer = null
    }

    /**
     * If Effects SDK pipeline fails to create, fall back to standard camera capturer.
     * User gets camera without effects — better than black screen.
     */
    private fun startFallbackCapturer(width: Int, height: Int, fps: Int) {
        try {
            val capturer = enumerator.createCapturer(device, eventsHandler)
            if (capturer != null && surfaceTextureHelper != null && context != null) {
                capturer.initialize(surfaceTextureHelper, context, capturerObserver)
                capturer.startCapture(width, height, fps)
                fallbackCapturer = capturer
                Log.w(TAG, "Fallback capturer started — camera works without effects")
                TsvbLogBridge.warn(
                    TAG,
                    "Fallback capturer started — camera works without effects",
                )
            } else {
                Log.e(TAG, "Fallback capturer creation failed — camera unavailable")
                TsvbLogBridge.error(TAG, "Fallback capturer creation failed — camera unavailable")
                eventsHandler.onCameraError("Both Effects SDK and fallback camera failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback capturer exception", e)
            TsvbLogBridge.error(TAG, "Fallback capturer exception", e)
            eventsHandler.onCameraError("Fallback camera failed: ${e.message}")
        }
    }

    // MARK: - Helpers

    private fun isFrontFacing(): Boolean {
        return try {
            enumerator.isFrontFacing(device)
        } catch (e: Exception) {
            device.contains("front", ignoreCase = true) || device == "1"
        }
    }

    /**
     * Returns a reusable NV21 byte array for the given dimensions.
     */
    private fun getNv21Buffer(width: Int, height: Int): ByteArray {
        if (nv21Buffer == null || nv21Width != width || nv21Height != height) {
            val size = width * height + 2 * (width / 2) * (height / 2)
            nv21Buffer = ByteArray(size)
            nv21Width = width
            nv21Height = height
        }
        return nv21Buffer!!
    }

    /**
     * Returns a reusable ARGB int array for the given dimensions.
     */
    private fun getArgbBuffer(width: Int, height: Int): IntArray {
        val needed = width * height
        if (argbBuffer == null || argbBuffer!!.size < needed) {
            argbBuffer = IntArray(needed)
        }
        return argbBuffer!!
    }

    /**
     * Converts pre-extracted ARGB pixels to NV21 (YUV420SP) format.
     * Handles horizontal flip inline (no separate Bitmap allocation).
     */
    private fun argbToNv21(argb: IntArray, nv21: ByteArray, width: Int, height: Int, flipH: Boolean) {
        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize

        for (j in 0 until height) {
            for (i in 0 until width) {
                val srcX = if (flipH) (width - 1 - i) else i
                val pixel = argb[j * width + srcX]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                nv21[yIndex++] = y.coerceIn(0, 255).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    nv21[uvIndex++] = v.coerceIn(0, 255).toByte()
                    nv21[uvIndex++] = u.coerceIn(0, 255).toByte()
                }
            }
        }
    }
}
