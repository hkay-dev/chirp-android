package dev.chirpboard.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Singleton manager for SherpaRecognizer to ensure model is loaded once
 * and shared across keyboard service and voice recognition activity.
 *
 * Failed initialization attempts are not cached so callers can retry once
 * model files become available or a concurrent load finishes.
 */
object RecognizerManager {
    private const val TAG = "RecognizerManager"

    @Volatile
    private var recognizer: SherpaRecognizer? = null

    private val mutex = Mutex()

    fun peekReadyRecognizer(): SherpaRecognizer? = recognizer?.takeIf { it.isReady }

    /** True when the shared recognizer is currently resident in memory and ready (LOAD-1). */
    fun isResident(): Boolean = recognizer?.isReady == true

    suspend fun initializeRecognizer(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                recognizer?.takeIf { it.isReady }?.let { return@withContext true }

                recognizer?.let { stale ->
                    Log.d(TAG, "Discarding stale recognizer before re-initialization")
                    stale.release()
                    recognizer = null
                }

                Log.d(TAG, "Creating SherpaRecognizer singleton...")
                val rec = SherpaRecognizer(context.applicationContext)
                val success = rec.initialize()
                if (success) {
                    recognizer = rec
                    Log.d(TAG, "Recognizer initialized successfully")
                } else {
                    rec.release()
                    Log.e(TAG, "Failed to initialize recognizer")
                }
                success
            }
        }

    /**
     * Frees the shared recognizer (~660MB Parakeet model) from memory.
     *
     * LOAD-1 / KBD-1: this MUST NOT be called from a single surface's start/teardown (a keyboard
     * dictation, a recording start, an Activity finish). Doing so forced the next keyboard
     * dictation to cold-reload the model — the user's "it loads the model again" complaint. The
     * recognizer is a process-global singleton shared by the keyboard IME and the recognition
     * Activity; it is meant to stay warm while the keyboard is enabled.
     *
     * Call this ONLY for a genuine "free model memory" intent:
     *  - the OS reporting real memory pressure (ComponentCallbacks2.onTrimMemory at one of the
     *    levels allowlisted by [ChirpApplication.shouldReleaseRecognizerOnTrim], or onLowMemory),
     *    wired in [ChirpApplication]; or
     *  - an explicit user action (delete model / "free memory").
     *
     * The model reloads lazily (or eagerly on the next IME bind) afterward, so correctness is
     * preserved; this only trades steady-state RAM for warmth.
     */
    suspend fun releaseRecognizer() {
        mutex.withLock {
            if (recognizer == null) {
                return
            }
            Log.d(TAG, "Releasing SherpaRecognizer singleton from memory (explicit free / memory pressure)...")
            recognizer?.release()
            recognizer = null
        }
    }
}
