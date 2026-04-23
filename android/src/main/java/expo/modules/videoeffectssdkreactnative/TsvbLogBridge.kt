package expo.modules.videoeffectssdkreactnative

/**
 * One-way native -> JS log forwarder.
 *
 * The module sets [emitter] in OnCreate and toggles [jsListenerReady] via
 * OnStartObserving / OnStopObserving. Events emitted before both are true
 * (e.g. natively in module OnCreate, before JS has called addListener) would
 * be dropped by Expo's sendEvent — so they are buffered here and flushed
 * once both conditions are met. Without this, natively emitted diagnostics
 * during startup never reach JS/DataDog.
 */
object TsvbLogBridge {
    /** Prefix applied to every forwarded message so DataDog can filter on `message:tsvbNative.*`. */
    const val PREFIX = "tsvbNative."

    /** Cap to prevent unbounded memory if JS never subscribes. */
    private const val MAX_BUFFERED = 128

    enum class Level { INFO, WARN, ERROR }

    @Volatile
    private var emitter: ((Map<String, Any?>) -> Unit)? = null

    @Volatile
    private var jsListenerReady = false

    /** Events emitted before the bridge is fully wired (emitter + JS listener) are buffered here. */
    private val pending = ArrayDeque<Map<String, Any?>>()
    private val pendingLock = Any()

    fun setEmitter(emit: ((Map<String, Any?>) -> Unit)?) {
        emitter = emit
        flushIfReady()
    }

    fun setJsListenerReady(ready: Boolean) {
        jsListenerReady = ready
        if (ready) flushIfReady()
    }

    fun info(tag: String, message: String, context: Map<String, Any?> = emptyMap()) {
        enqueueOrEmit(Level.INFO, tag, message, context, null)
    }

    fun warn(tag: String, message: String, context: Map<String, Any?> = emptyMap()) {
        enqueueOrEmit(Level.WARN, tag, message, context, null)
    }

    fun error(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        context: Map<String, Any?> = emptyMap(),
    ) {
        enqueueOrEmit(Level.ERROR, tag, message, context, throwable)
    }

    private fun enqueueOrEmit(
        level: Level,
        tag: String,
        message: String,
        context: Map<String, Any?>,
        throwable: Throwable?,
    ) {
        val payload = buildPayload(level, tag, message, context, throwable)
        val activeEmitter = emitter
        if (activeEmitter != null && jsListenerReady) {
            dispatch(activeEmitter, payload)
            return
        }
        synchronized(pendingLock) {
            pending.addLast(payload)
            while (pending.size > MAX_BUFFERED) pending.removeFirst()
        }
    }

    private fun flushIfReady() {
        val activeEmitter = emitter ?: return
        if (!jsListenerReady) return
        val drained: List<Map<String, Any?>>
        synchronized(pendingLock) {
            if (pending.isEmpty()) return
            drained = pending.toList()
            pending.clear()
        }
        drained.forEach { dispatch(activeEmitter, it) }
    }

    private fun dispatch(
        emit: (Map<String, Any?>) -> Unit,
        payload: Map<String, Any?>,
    ) {
        try {
            emit(payload)
        } catch (_: Throwable) {
            // Don't let bridge errors crash native code
        }
    }

    private fun buildPayload(
        level: Level,
        tag: String,
        message: String,
        context: Map<String, Any?>,
        throwable: Throwable?,
    ): Map<String, Any?> {
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
        return payload
    }
}
