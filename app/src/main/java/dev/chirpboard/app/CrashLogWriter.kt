package dev.chirpboard.app

import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ERR-21: minimal, local-only uncaught-exception safety net.
 *
 * This process hosts an IME, three capture surfaces, WorkManager pipelines and a large native
 * recognizer; without any crash record an uncaught exception simply vanishes. The writer appends
 * a timestamped stack trace to a small rotating set of files under `filesDir/crashlogs` and then
 * delegates to the previous handler, so the system's crash dialog / process-restart semantics are
 * completely unchanged. Nothing is ever uploaded; the logs contain only timestamps, thread names
 * and stack traces (no user content).
 */
class CrashLogWriter(
    private val logDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Installs this writer as the default uncaught exception handler, chaining to whatever
     * handler was previously installed (normally the system handler that shows the crash UI
     * and kills the process). Idempotent enough for a single Application.onCreate call site.
     */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Never let crash logging itself break crash handling.
            runCatching { writeCrashLog(thread, throwable) }
                .onFailure { Log.e(TAG, "Failed to write crash log", it) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Writes one crash record and prunes the directory to the newest [MAX_LOG_FILES] files.
     * Synchronous file IO is intentional: the process is about to die.
     */
    internal fun writeCrashLog(
        thread: Thread,
        throwable: Throwable,
    ) {
        if (!logDir.isDirectory && !logDir.mkdirs()) return
        val nowMs = clock()
        val stamp = SimpleDateFormat(FILE_STAMP_PATTERN, Locale.US).format(Date(nowMs))
        val file = File(logDir, "crash-$stamp-$nowMs.txt")
        PrintWriter(file.bufferedWriter()).use { writer ->
            writer.println("time: $stamp (epochMs=$nowMs)")
            writer.println("thread: ${thread.name}")
            throwable.printStackTrace(writer)
        }
        pruneOldLogs()
    }

    private fun pruneOldLogs() {
        val logs =
            logDir
                .listFiles { candidate -> candidate.isFile && candidate.name.startsWith("crash-") }
                ?.sortedByDescending { it.name }
                ?: return
        logs.drop(MAX_LOG_FILES).forEach { stale ->
            if (!stale.delete()) Log.w(TAG, "Could not prune crash log ${stale.name}")
        }
    }

    companion object {
        private const val TAG = "CrashLogWriter"
        private const val FILE_STAMP_PATTERN = "yyyyMMdd-HHmmss"

        /** Keep only the newest few crashes; this is a debugging breadcrumb, not telemetry. */
        internal const val MAX_LOG_FILES = 5

        /** Directory name under `filesDir` where crash records are kept. */
        const val LOG_DIR_NAME = "crashlogs"
    }
}
