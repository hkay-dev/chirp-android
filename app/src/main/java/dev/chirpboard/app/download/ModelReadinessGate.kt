package dev.chirpboard.app.download

import android.util.Log
import androidx.annotation.VisibleForTesting
import dev.chirpboard.app.core.modelreadiness.ModelReadinessEvaluation
import dev.chirpboard.app.core.modelreadiness.ModelReadinessState
import dev.chirpboard.app.core.modelreadiness.ModelReadinessUnavailableReason
import dev.chirpboard.app.core.modelreadiness.ModelReadinessVerificationSource
import dev.chirpboard.app.core.modelreadiness.SpeechModelStore
import dev.chirpboard.app.core.modelreadiness.ModelReadyResult
import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.modelreadiness.VerificationTrigger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ModelReadinessGate(
    private val speechModelStore: SpeechModelStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val gateScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : SpeechModelReadinessGate {
    private val inFlightMutex = Mutex()
    private var inFlightVerification: Deferred<ModelReadyResult>? = null

    private val _state = MutableStateFlow<ModelReadinessState>(ModelReadinessState.Unknown)
    override val state: StateFlow<ModelReadinessState> = _state.asStateFlow()

    override fun warmupIfNeeded(trigger: VerificationTrigger) {
        if (_state.value !is ModelReadinessState.Unknown) {
            return
        }
        gateScope.launch {
            ensureReady(trigger)
        }
    }

    override suspend fun ensureReady(trigger: VerificationTrigger): ModelReadyResult {
        val current = _state.value
        if (current is ModelReadinessState.Ready) {
            return ModelReadyResult.Ready(current.source)
        }

        val verification =
            inFlightMutex.withLock {
                val active = inFlightVerification
                if (active != null && active.isActive) {
                    active
                } else {
                    val startedAt = now()
                    _state.value = ModelReadinessState.Checking(trigger, startedAt)
                    gateScope
                        .async(ioDispatcher) {
                            verifyAndUpdateState(trigger)
                        }.also { inFlightVerification = it }
                }
            }

        return try {
            verification.await()
        } finally {
            inFlightMutex.withLock {
                if (inFlightVerification === verification) {
                    inFlightVerification = null
                }
            }
        }
    }

    private suspend fun verifyAndUpdateState(trigger: VerificationTrigger): ModelReadyResult {
        val startedNs = System.nanoTime()
        return try {
            val evaluation = speechModelStore.evaluateReadiness()
            val durationMs = (System.nanoTime() - startedNs) / 1_000_000
            if (evaluation.isReady) {
                val source =
                    evaluation.verificationSource
                        ?: ModelReadinessVerificationSource.CHECKSUM_VERIFICATION
                _state.value =
                    ModelReadinessState.Ready(
                        verifiedAtEpochMs = now(),
                        source = source,
                    )
                logDebug("Readiness verification passed in ${durationMs}ms (trigger=$trigger, source=$source)")
                ModelReadyResult.Ready(source)
            } else {
                val reason =
                    evaluation.unavailableReason
                        ?: ModelReadinessUnavailableReason.MISSING_MODEL_FILES
                _state.value = ModelReadinessState.Unavailable(reason)
                logWarn("Readiness unavailable in ${durationMs}ms (trigger=$trigger, reason=$reason)")
                ModelReadyResult.Unavailable(reason)
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            val durationMs = (System.nanoTime() - startedNs) / 1_000_000
            val message = error.message ?: "Unknown readiness verification error"
            _state.value = ModelReadinessState.Error(message)
            logError("Readiness verification failed in ${durationMs}ms (trigger=$trigger)", error)
            ModelReadyResult.Error(message)
        }
    }

    private fun logDebug(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    private fun logWarn(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    private fun logError(
        message: String,
        error: Throwable,
    ) {
        runCatching { Log.e(TAG, message, error) }
    }

    @VisibleForTesting
    fun cancelScopeForTest() {
        gateScope.cancel()
    }

    companion object {
        private const val TAG = "ModelReadinessGate"
    }
}
