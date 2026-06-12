package dev.chirpboard.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Singleton manager for SherpaRecognizer to ensure model is loaded once
 * and shared across keyboard service and voice recognition activity.
 *
 * Failed initialization attempts are not cached so callers can retry once
 * model files become available or a concurrent load finishes.
 *
 * Residency bookkeeping (PRF-2): the manager stamps [lastUsedAtMs] on every touch
 * (initialize, peek, lease begin/end) and counts in-flight transcriptions via usage
 * leases, so the idle-release policy ([RecognizerIdleReleasePolicy]) can free the
 * ~660MB model after a long unused period without ever pulling it out from under an
 * active caller.
 */
object RecognizerManager {
    private const val TAG = "RecognizerManager"

    @Volatile
    private var recognizer: SherpaRecognizer? = null

    private val mutex = Mutex()

    /** Wall-clock timestamp of the most recent recognizer touch. 0 = never used. */
    @Volatile
    private var lastUsedAtMillis: Long = 0L

    /** Number of in-flight transcriptions holding a usage lease. */
    private val activeLeases = AtomicInteger(0)

    /**
     * Residency observer for the idle-release policy. Invoked OUTSIDE the manager mutex:
     * - [ResidencyListener.onRecognizerResident] after a successful initialization;
     * - [ResidencyListener.onRecognizerReleased] after the recognizer is freed.
     */
    interface ResidencyListener {
        fun onRecognizerResident()

        fun onRecognizerReleased()
    }

    @Volatile
    private var residencyListener: ResidencyListener? = null

    fun setResidencyListener(listener: ResidencyListener?) {
        residencyListener = listener
    }

    fun peekReadyRecognizer(): SherpaRecognizer? =
        recognizer?.takeIf { it.isReady }?.also { touch() }

    /** True when the shared recognizer is currently resident in memory and ready (LOAD-1). */
    fun isResident(): Boolean = recognizer?.isReady == true

    /** Wall-clock time of the last recognizer touch; 0 when it has never been used. */
    fun lastUsedAtMs(): Long = lastUsedAtMillis

    /** Number of in-flight usage leases (transcriptions currently using the recognizer). */
    fun activeLeaseCount(): Int = activeLeases.get()

    /**
     * Runs [block] under a usage lease so the idle/pressure release paths treat the
     * recognizer as in-use for its whole duration. The recency stamp is refreshed both on
     * entry and AFTER completion, so a long chunked decode is never "idle-expired" mid-flight
     * and the idle countdown restarts from the moment the work finished.
     */
    suspend fun <T> withUsageLease(block: suspend () -> T): T {
        activeLeases.incrementAndGet()
        touch()
        try {
            return block()
        } finally {
            activeLeases.decrementAndGet()
            touch()
        }
    }

    suspend fun initializeRecognizer(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            val success =
                mutex.withLock {
                    recognizer?.takeIf { it.isReady }?.let {
                        touch()
                        return@withLock true
                    }

                    recognizer?.let { stale ->
                        Log.d(TAG, "Discarding stale recognizer before re-initialization")
                        stale.release()
                        recognizer = null
                    }

                    Log.d(TAG, "Creating SherpaRecognizer singleton...")
                    val rec = SherpaRecognizer(context.applicationContext)
                    val initialized = rec.initialize()
                    if (initialized) {
                        recognizer = rec
                        touch()
                        Log.d(TAG, "Recognizer initialized successfully")
                    } else {
                        rec.release()
                        Log.e(TAG, "Failed to initialize recognizer")
                    }
                    initialized
                }
            if (success) {
                residencyListener?.onRecognizerResident()
            }
            success
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
     *  - an explicit user action (delete model / "free memory"); this path is deliberately
     *    UNGATED because the user asked for it.
     *
     * The OS-pressure and idle-timeout paths must instead go through [releaseIfUnused], which
     * refuses to free the model while any capture/transcription surface could need it
     * synchronously. The model reloads lazily (or eagerly on the next IME bind) afterward, so
     * correctness is preserved; this only trades steady-state RAM for warmth.
     */
    suspend fun releaseRecognizer() {
        val released =
            mutex.withLock {
                if (recognizer == null) {
                    return@withLock false
                }
                Log.d(TAG, "Releasing SherpaRecognizer singleton from memory (explicit free / memory pressure)...")
                recognizer?.release()
                recognizer = null
                true
            }
        if (released) {
            residencyListener?.onRecognizerReleased()
        }
    }

    /**
     * Conditionally frees the recognizer for the idle-timeout and memory-pressure paths.
     *
     * Reliability contract (sacred): the model is NEVER freed while it could be needed
     * synchronously. The decision is evaluated under the manager mutex (so it cannot race a
     * concurrent [withUsageLease] acquisition or initialization) via [idleReleaseDecision]:
     *  - not resident -> nothing to do;
     *  - any in-flight usage lease -> keep (a transcription is mid-decode);
     *  - [isExternallyBusy] (global recording state non-idle, transcription queue active) -> keep;
     *  - used within [minIdleMs] -> keep (pass 0 for the pressure path, which only needs
     *    "not busy", not "long idle").
     *
     * @return the decision that was taken; [IdleReleaseDecision.RELEASE] means the model was freed.
     */
    suspend fun releaseIfUnused(
        minIdleMs: Long,
        nowMs: Long,
        isExternallyBusy: suspend () -> Boolean,
    ): IdleReleaseDecision {
        val decision =
            mutex.withLock {
                val verdict =
                    idleReleaseDecision(
                        isResident = recognizer != null,
                        activeLeases = activeLeases.get(),
                        lastUsedAtMs = lastUsedAtMillis,
                        nowMs = nowMs,
                        minIdleMs = minIdleMs,
                        isExternallyBusy = isExternallyBusy(),
                    )
                if (verdict == IdleReleaseDecision.RELEASE) {
                    Log.i(TAG, "Releasing SherpaRecognizer singleton (idle/pressure, unused)")
                    recognizer?.release()
                    recognizer = null
                }
                verdict
            }
        if (decision == IdleReleaseDecision.RELEASE) {
            residencyListener?.onRecognizerReleased()
        }
        return decision
    }

    private fun touch() {
        lastUsedAtMillis = System.currentTimeMillis()
    }

    /** Test-only: reset usage bookkeeping so JVM tests are order-independent. */
    internal fun resetUsageStateForTest() {
        lastUsedAtMillis = 0L
        activeLeases.set(0)
        residencyListener = null
    }
}

/** Outcome of a conditional (idle/pressure) release attempt. */
enum class IdleReleaseDecision {
    /** The recognizer was (or may be) freed. */
    RELEASE,

    /** Nothing resident; no-op. */
    NOT_RESIDENT,

    /** An in-flight transcription holds a usage lease. */
    IN_USE,

    /** A capture or queued transcription surface is active. */
    EXTERNALLY_BUSY,

    /** The recognizer was used more recently than the idle cutoff. */
    RECENTLY_USED,
}

/**
 * Pure gating matrix for the idle/pressure release. Extracted so the reliability-sensitive
 * policy ("never release while any capture/transcription surface could need the model
 * synchronously") is exhaustively unit-testable without a real recognizer.
 */
internal fun idleReleaseDecision(
    isResident: Boolean,
    activeLeases: Int,
    lastUsedAtMs: Long,
    nowMs: Long,
    minIdleMs: Long,
    isExternallyBusy: Boolean,
): IdleReleaseDecision =
    when {
        !isResident -> IdleReleaseDecision.NOT_RESIDENT
        activeLeases > 0 -> IdleReleaseDecision.IN_USE
        isExternallyBusy -> IdleReleaseDecision.EXTERNALLY_BUSY
        nowMs - lastUsedAtMs < minIdleMs -> IdleReleaseDecision.RECENTLY_USED
        else -> IdleReleaseDecision.RELEASE
    }
