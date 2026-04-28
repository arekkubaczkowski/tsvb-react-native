package expo.modules.videoeffectssdkreactnative

import android.content.Context
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.Promise

class VideoEffectsSdkReactNativeModule : Module() {

    private val tsvbManager by lazy {
        TsvbManager(context)
    }

    private val context: Context
        get() = appContext.reactContext ?: throw IllegalStateException("React context not available")

    private var logcatScraper: LogcatErrorScraper? = null

    override fun definition() = ModuleDefinition {
        Name("VideoEffectsSdkReactNativeModule")

        Events("onFrameCaptured", "videoEffects.nativeError")

        AsyncFunction("initialize") { customerID: String, trackId: String, promise: Promise ->
            tsvbManager.initialize(customerID, trackId) { result ->
                promise.resolve(result)
            }
        }

        AsyncFunction("enableBlurBackground") { power: Double?, promise: Promise ->
            val blurPower = power ?: 0.5
            tsvbManager.enableBlurBackground(blurPower.toFloat()) { result ->
                if (result["success"] == true) {
                    promise.resolve(result)
                } else {
                    promise.reject("EFFECTS_ERROR", result["error"] as? String ?: "Unknown error", null)
                }
            }
        }

        AsyncFunction("disableBlurBackground") { promise: Promise ->
            tsvbManager.disableBlurBackground { result ->
                promise.resolve(result)
            }
        }

        AsyncFunction("enableReplaceBackground") { assetSource: Map<String, Any>?, promise: Promise ->
            tsvbManager.enableReplaceBackground(assetSource) { result ->
                if (result["success"] == true) {
                    promise.resolve(result)
                } else {
                    promise.reject("EFFECTS_ERROR", result["error"] as? String ?: "Unknown error", null)
                }
            }
        }

        AsyncFunction("disableReplaceBackground") { promise: Promise ->
            tsvbManager.disableReplaceBackground { result ->
                promise.resolve(result)
            }
        }

        Function("isBlurEnabled") {
            tsvbManager.isBlurEnabled
        }

        Function("hasVirtualBackground") {
            tsvbManager.isReplaceBackgroundEnabled
        }

        Function("isInitialized") {
            tsvbManager.isInitialized
        }

        Function("isEffectsUnavailable") {
            tsvbManager.isEffectsUnavailable
        }

        Function("cleanup") {
            tsvbManager.cleanup()
        }

        Function("setSegmentationPreset") { _: String ->
            // No-op on Android — SDK uses SegmentationMode (orientation-based), not quality presets
        }

        Function("startFrameCapture") { intervalMs: Int ->
            tsvbManager.startFrameCapture(intervalMs.toLong()) { filePath, width, height, timestamp ->
                sendEvent("onFrameCaptured", mapOf(
                    "filePath" to filePath,
                    "timestamp" to timestamp,
                    "width" to width,
                    "height" to height,
                ))
            }
        }

        Function("stopFrameCapture") {
            tsvbManager.stopFrameCapture()
        }

        Function("startNativeErrorScraper") {
            if (logcatScraper == null) {
                val scraper = LogcatErrorScraper { match ->
                    sendEvent("videoEffects.nativeError", mapOf(
                        "rawLine" to match.rawLine,
                        "tag" to match.tag,
                        "level" to match.level.toString(),
                        "code" to (match.errorCode ?: ""),
                        "pattern" to match.patternName,
                        "pid" to match.pid,
                        "tid" to match.tid,
                        "timestampMs" to match.threadTimeMs,
                        "message" to match.message,
                    ))
                }
                logcatScraper = scraper
                tsvbManager.attachLogcatScraper(scraper)
                scraper.start()
            } else {
                logcatScraper?.resume()
            }
        }

        Function("stopNativeErrorScraper") {
            logcatScraper?.stop()
            logcatScraper = null
            tsvbManager.attachLogcatScraper(null)
        }

        Function("pauseNativeErrorScraper") {
            logcatScraper?.pause()
        }

        Function("resumeNativeErrorScraper") {
            logcatScraper?.resume()
        }

        Function("getNativeErrorRingBuffer") {
            logcatScraper?.snapshot()?.map { match ->
                mapOf(
                    "rawLine" to match.rawLine,
                    "tag" to match.tag,
                    "level" to match.level.toString(),
                    "code" to (match.errorCode ?: ""),
                    "pattern" to match.patternName,
                    "pid" to match.pid,
                    "tid" to match.tid,
                    "timestampMs" to match.threadTimeMs,
                    "message" to match.message,
                )
            } ?: emptyList<Map<String, Any>>()
        }
    }
}
