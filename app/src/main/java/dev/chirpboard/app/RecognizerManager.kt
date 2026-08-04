package dev.chirpboard.app

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-wide owner of the heavyweight Sherpa recognizer.
 *
 * Once loaded, the recognizer stays resident until confirmed system pressure, an explicit model
 * switch or deletion, or process death. There is deliberately no idle timer or recency API.
 */
object RecognizerManager {
    private const val TAG = "RecognizerManager"

    @Volatile
    private var recognizer: SherpaRecognizer? = null
    private val mutex = Mutex()
    private val activeLeases = AtomicInteger(0)

    fun peekReadyRecognizer(): SherpaRecognizer? = recognizer?.takeIf { it.isReady }

    fun isResident(): Boolean = recognizer?.isReady == true

    fun activeLeaseCount(): Int = activeLeases.get()

    suspend fun <T> withUsageLease(block: suspend () -> T): T {
        mutex.withLock { activeLeases.incrementAndGet() }
        return try {
            block()
        } finally {
            mutex.withLock { activeLeases.decrementAndGet() }
        }
    }
    suspend fun initializeRecognizer(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                recognizer?.takeIf { it.isReady }?.let { return@withLock true }
                recognizer?.let { stale ->
                    stale.release()
                    recognizer = null
                }

                val candidate = SherpaRecognizer(context.applicationContext)
                val initialized = candidate.initialize()
                if (initialized) {
                    recognizer = candidate
                    Log.d(TAG, "Recognizer initialized successfully")
                } else {
                    candidate.release()
                    Log.e(TAG, "Failed to initialize recognizer")
                }
                initialized
            }
        }

    /** Explicit deletion path. Callers must already have stopped work that needs the model. */
    suspend fun releaseRecognizer() {
        mutex.withLock {
            recognizer?.release()
            recognizer = null
        }
    }

    suspend fun releaseForPressureIfUnused(
        isExternallyBusy: suspend () -> Boolean,
    ): RecognizerReleaseDecision = releaseIfUnused(isExternallyBusy)

    suspend fun releaseForModelSwitchIfUnused(): RecognizerReleaseDecision =
        releaseIfUnused { false }

    private suspend fun releaseIfUnused(
        isExternallyBusy: suspend () -> Boolean,
    ): RecognizerReleaseDecision =
        mutex.withLock {
            val decision =
                recognizerReleaseDecision(
                    isResident = recognizer != null,
                    activeLeases = activeLeases.get(),
                    isExternallyBusy = isExternallyBusy(),
                )
            if (decision == RecognizerReleaseDecision.RELEASE) {
                recognizer?.release()
                recognizer = null
            }
            decision
        }

    @VisibleForTesting
    internal fun installRecognizerForTest(instance: SherpaRecognizer?) {
        recognizer = instance
    }

    internal fun resetUsageStateForTest() {
        recognizer = null
        activeLeases.set(0)
    }
}

enum class RecognizerReleaseDecision {
    RELEASE,
    NOT_RESIDENT,
    IN_USE,
    EXTERNALLY_BUSY,
}

internal fun recognizerReleaseDecision(
    isResident: Boolean,
    activeLeases: Int,
    isExternallyBusy: Boolean,
): RecognizerReleaseDecision =
    when {
        !isResident -> RecognizerReleaseDecision.NOT_RESIDENT
        activeLeases > 0 -> RecognizerReleaseDecision.IN_USE
        isExternallyBusy -> RecognizerReleaseDecision.EXTERNALLY_BUSY
        else -> RecognizerReleaseDecision.RELEASE
    }
