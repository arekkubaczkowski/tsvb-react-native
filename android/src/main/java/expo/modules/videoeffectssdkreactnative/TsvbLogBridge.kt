package expo.modules.videoeffectssdkreactnative

/**
 * One-way native -> JS log forwarder.
 *
 * The module sets [emitter] in OnCreate; native classes call info/warn/error here
 * and the payload travels to JS as an `onTsvbLog` event. JS forwards it to its
 * own logger (DataDog in alpha/staging/prod, console in dev). Without this,
 * native logs only show up in adb logcat, which we can't access on remote
 * devices like alpha testers.
 */
object TsvbLogBridge {
    /** Prefix applied to every forwarded message so DataDog can filter on `message:tsvbNative.*`. */
    const val PREFIX = "tsvbNative."

    enum class Level { INFO, WARN, ERROR }

    @Volatile
    private var emitter: ((Map<String, Any?>) -> Unit)? = null

    fun setEmitter(emit: ((Map<String, Any?>) -> Unit)?) {
        emitter = emit
    }

    fun info(tag: String, message: String, context: Map<String, Any?> = emptyMap()) {
        emit(Level.INFO, tag, message, context, null)
    }

    fun warn(tag: String, message: String, context: Map<String, Any?> = emptyMap()) {
        emit(Level.WARN, tag, message, context, null)
    }

    fun error(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        context: Map<String, Any?> = emptyMap(),
    ) {
        emit(Level.ERROR, tag, message, context, throwable)
    }

    private fun emit(
        level: Level,
        tag: String,
        message: String,
        context: Map<String, Any?>,
        throwable: Throwable?,
    ) {
        val emit = emitter ?: return
        val payload = mutableMapOf<String, Any?>(
            "level" to level.name.lowercase(),
            "tag" to tag,
            "message" to "$PREFIX$message",
            "context" to context,
        )
        if (throwable != null) {
            payload["error"] = mapOf(
                "name" to (throwable.javaClass.simpleName ?: "Throwable"),
                "message" to (throwable.message ?: ""),
                "stack" to throwable.stackTraceToString(),
            )
        }
        try {
            emit(payload)
        } catch (_: Throwable) {
            // Don't let bridge errors crash native code
        }
    }
}
