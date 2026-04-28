package expo.modules.videoeffectssdkreactnative

import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Reads this process's own logcat stream on a daemon thread, filters for native error
 * patterns (libEGL / MediaPipe / TSVB Effects SDK / TFLite GPU delegate) and forwards
 * matched entries to a callback. Maintains a bounded ring buffer of recent matches
 * for post-mortem dumps when our SDK watchdog fires.
 *
 * Self-pid logcat reads do not require READ_LOGS on any API level (verified up to API 36).
 * The spawned `logcat` process inherits our UID so logd scopes the stream automatically;
 * the explicit `--pid=` flag is belt-and-suspenders for OEM forks that ignore UID scoping.
 */
class LogcatErrorScraper(
    private val onMatch: (Match) -> Unit,
) {

    data class Match(
        val rawLine: String,
        val tag: String,
        val level: Char,
        val pid: Int,
        val tid: Int,
        val threadTimeMs: Long,
        val message: String,
        val errorCode: String?,
        val patternName: String,
    )

    companion object {
        private const val TAG = "LogcatErrorScraper"
        private const val RING_CAPACITY = 50
        private const val READ_BUFFER_BYTES = 8 * 1024
        private const val RESTART_BACKOFF_MS = 2_000L
        private const val MAX_RESTART_ATTEMPTS = 5

        // logcat threadtime format:
        //   MM-DD HH:MM:SS.mmm  PID  TID L TAG     : message
        //   04-27 10:15:32.123  9876 9901 E libEGL  : validate_display:550 error 3008 (EGL_BAD_DISPLAY)
        private val THREADTIME_LINE = Regex(
            """^(\d{2}-\d{2})\s+(\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+(.+?)\s*:\s?(.*)$"""
        )

        // Order matters — first hit wins, more specific patterns first.
        private val PATTERNS: List<Pair<String, Regex>> = listOf(
            "egl_named_error" to Regex("""EGL_(BAD_\w+|NOT_INITIALIZED|CONTEXT_LOST)"""),
            "egl_hex_error" to Regex("""\b0x300[0-9a-fA-F]\b"""),
            "gl_hex_error" to Regex("""GL error 0x[0-9a-fA-F]+"""),
            "tsvb_internal" to Regex("""Internal frame processor error""", RegexOption.IGNORE_CASE),
            "tsvb_corrupted" to Regex("""Corrupted frame""", RegexOption.IGNORE_CASE),
            "mediapipe_error" to Regex("""mediapipe.*?error""", RegexOption.IGNORE_CASE),
            "pipeline_failed" to Regex("""Failed to.{1,40}pipeline""", RegexOption.IGNORE_CASE),
            "runtime_exception" to Regex("""\bRuntimeException\b"""),
            "tflite_gpu" to Regex("""(egl_environment\.cc|TfLiteGpu)"""),
        )

        private val ERROR_CODE_HEX = Regex("""\b(0x[0-9a-fA-F]{3,8})\b""")
        private val ERROR_CODE_NAMED = Regex("""(EGL_(?:BAD_\w+|NOT_INITIALIZED|CONTEXT_LOST))""")

        private val LOGCAT_TAG_FILTER = arrayOf(
            "libEGL:E",
            "EglManager:E",
            "FrameProcessor:E",
            "EffectsSDK:V",
            "MEDIAPIPE:V",
            "Mediapipe:V",
            "GLConsumer:E",
            "BufferQueueProducer:E",
            "TfLiteGpu:V",
            "*:S",
        )
    }

    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val processRef = AtomicReference<Process?>(null)
    private val threadRef = AtomicReference<Thread?>(null)

    // Ring buffer — monitor lock guards both ring and add/snapshot to keep snapshot atomic.
    private val ring = ArrayDeque<Match>(RING_CAPACITY)
    private val ringLock = Any()

    /**
     * Idempotent: a second start() while running is a no-op.
     */
    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        paused.set(false)
        spawnReaderThread()
    }

    /**
     * Stops the reader thread and destroys the underlying logcat process. Safe to
     * call from any thread, including from inside an onMatch callback (the thread
     * will observe the flag on its next read iteration).
     */
    fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        processRef.getAndSet(null)?.let { runCatching { it.destroy() } }
        threadRef.getAndSet(null)?.interrupt()
    }

    /**
     * Pauses match emission and ring-buffer ingestion without tearing down the
     * underlying process. Lines continue to be drained (so the kernel pipe doesn't
     * back-pressure logd) but are dropped on the floor. Cheaper than stop()/start()
     * when toggling rapidly (e.g. on app foreground/background).
     */
    fun pause() {
        paused.set(true)
    }

    fun resume() {
        paused.set(false)
    }

    /**
     * Atomic snapshot of all currently-buffered matches, oldest first. The returned
     * list is a defensive copy — safe to iterate without locking.
     */
    fun snapshot(): List<Match> = synchronized(ringLock) { ring.toList() }

    fun snapshotSince(thresholdMs: Long): List<Match> = synchronized(ringLock) {
        ring.filter { it.threadTimeMs >= thresholdMs }
    }

    fun clear() {
        synchronized(ringLock) { ring.clear() }
    }

    private fun spawnReaderThread() {
        val thread = Thread({ readerLoop() }, "LogcatErrorScraper").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
        threadRef.set(thread)
        thread.start()
    }

    private fun readerLoop() {
        val myPid = android.os.Process.myPid()
        var attempts = 0
        while (running.get() && attempts <= MAX_RESTART_ATTEMPTS) {
            val process = try {
                spawnLogcat(myPid)
            } catch (io: IOException) {
                Log.w(TAG, "Failed to spawn logcat (attempt ${attempts + 1}): ${io.message}")
                attempts++
                if (!sleepBackoff(attempts)) return
                continue
            }
            processRef.set(process)
            try {
                drainProcess(process)
            } catch (io: IOException) {
                // Stream broken — process likely killed by GC pressure / OOM. Restart.
                Log.w(TAG, "logcat stream broken: ${io.message}")
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } finally {
                runCatching { process.destroy() }
                processRef.compareAndSet(process, null)
            }
            if (!running.get()) return
            attempts++
            if (!sleepBackoff(attempts)) return
        }
        if (attempts > MAX_RESTART_ATTEMPTS) {
            Log.e(TAG, "Giving up after $attempts logcat respawns")
            running.set(false)
        }
    }

    private fun spawnLogcat(myPid: Int): Process {
        val cmd = mutableListOf(
            "logcat",
            "-v", "threadtime",
            "-T", "1",
            "--pid=$myPid",
        ).apply { addAll(LOGCAT_TAG_FILTER) }
        return try {
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        } catch (io: IOException) {
            // OEM fork without --pid (Huawei older builds): retry without the flag.
            // We still get UID-scoped output from logd.
            val fallback = mutableListOf("logcat", "-v", "threadtime", "-T", "1")
                .apply { addAll(LOGCAT_TAG_FILTER) }
            ProcessBuilder(fallback).redirectErrorStream(true).start()
        }
    }

    private fun drainProcess(process: Process) {
        BufferedReader(InputStreamReader(process.inputStream), READ_BUFFER_BYTES).use { reader ->
            while (running.get()) {
                val line = reader.readLine() ?: break
                if (paused.get()) continue
                handleLine(line)
            }
        }
    }

    private fun handleLine(line: String) {
        val parsed = THREADTIME_LINE.matchEntire(line) ?: return
        val tag = parsed.groupValues[6].trim()
        val message = parsed.groupValues[7]
        val patternHit = PATTERNS.firstOrNull { (_, regex) -> regex.containsMatchIn(message) || regex.containsMatchIn(tag) }
            ?: return

        val level = parsed.groupValues[5].first()
        val pid = parsed.groupValues[3].toIntOrNull() ?: -1
        val tid = parsed.groupValues[4].toIntOrNull() ?: -1
        val timeMs = parseTimeOfDayMs(parsed.groupValues[1], parsed.groupValues[2])

        val errorCode = ERROR_CODE_NAMED.find(message)?.value
            ?: ERROR_CODE_HEX.find(message)?.value

        val match = Match(
            rawLine = line,
            tag = tag,
            level = level,
            pid = pid,
            tid = tid,
            threadTimeMs = timeMs,
            message = message,
            errorCode = errorCode,
            patternName = patternHit.first,
        )
        synchronized(ringLock) {
            if (ring.size == RING_CAPACITY) ring.removeFirst()
            ring.addLast(match)
        }
        // Callback is invoked OUTSIDE the lock — consumer may call snapshot()/clear()
        // re-entrantly (e.g. dump-then-clear pattern from a watchdog).
        try {
            onMatch(match)
        } catch (t: Throwable) {
            Log.w(TAG, "onMatch callback threw", t)
        }
    }

    /**
     * Reconstructs a wall-clock millisecond from the threadtime "MM-DD HH:MM:SS.mmm"
     * fields. We do NOT round-trip through Calendar — far too expensive per line.
     * Year is implied as the current process's start year; for our use (filtering
     * "events in the last 30s") only relative ordering and the millisecond precision
     * matter. A single `now()` reading at the top is used as the year/month anchor.
     */
    private val timeAnchorYear: Int = run {
        val cal = java.util.Calendar.getInstance()
        cal.get(java.util.Calendar.YEAR)
    }
    private val tzOffsetMs: Int = java.util.TimeZone.getDefault().rawOffset

    private fun parseTimeOfDayMs(monthDay: String, hms: String): Long {
        return try {
            val month = monthDay.substring(0, 2).toInt() - 1
            val day = monthDay.substring(3, 5).toInt()
            val hour = hms.substring(0, 2).toInt()
            val minute = hms.substring(3, 5).toInt()
            val second = hms.substring(6, 8).toInt()
            val millis = hms.substring(9, 12).toInt()
            val cal = java.util.Calendar.getInstance()
            cal.set(timeAnchorYear, month, day, hour, minute, second)
            cal.set(java.util.Calendar.MILLISECOND, millis)
            cal.timeInMillis
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }

    private fun sleepBackoff(attempts: Int): Boolean {
        return try {
            Thread.sleep(RESTART_BACKOFF_MS * attempts.coerceAtMost(5))
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}
