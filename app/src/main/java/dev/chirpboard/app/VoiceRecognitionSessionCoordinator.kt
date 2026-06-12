package dev.chirpboard.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serializes the start/stop/cancel lifecycle of system speech-recognition capture sessions.
 *
 * Every start request issues a new session generation on the caller thread; stop and
 * cancel requests capture the generation they were issued for and only act on that
 * exact session. A single [Mutex] guards every state transition, so a stop or cancel
 * that arrives while a start is still in flight waits until the start either fully
 * started the recorder (and then stops it cleanly) or failed (and then the stop is a
 * stale no-op, because the failed start already emitted its terminal error).
 *
 * Every error path — including cancellation of an in-flight start — releases the
 * capture gate and leaves the recorder stopped.
 */
internal class VoiceRecognitionSessionCoordinator(
    private val scope: CoroutineScope,
    private val captureGate: VoiceRecognitionCaptureGate,
    private val recorder: RecorderControl,
    private val audioPathLabel: String = DEFAULT_AUDIO_PATH_LABEL,
    /**
     * Dispatcher for the blocking recorder teardown ([RecorderControl.stop]/[RecorderControl.cancel]).
     * The service drives this coordinator from a `Dispatchers.Main` scope, but
     * `recorder.stop()` does an AudioRecord stop/release (binder transactions) plus a multi-MB
     * `samples.copyOf` for long dictations, and `recorder.cancel()` does stop/release + a file
     * delete — none of which belong on the IME/service main thread (PERF-5). The teardown is
     * hopped here while the lifecycle mutex stays held, so serialization, generation, and gate
     * semantics are unchanged; only the thread the syscalls run on moves. Defaults to
     * [Dispatchers.IO]; tests inject the test scheduler's dispatcher.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Minimal recorder surface the coordinator drives; implemented over [dev.chirpboard.app.core.audio.recorder.VoiceRecorder]. */
    internal interface RecorderControl {
        /** Apply per-session settings (microphone gain, etc.) before the recorder starts. */
        suspend fun prepare()

        suspend fun start(): Boolean

        fun stop(): FloatArray

        /** Stop immediately and discard any captured audio. Must leave the recorder stopped. */
        fun cancel()

        suspend fun collectSamples()

        suspend fun streamRms(onRms: (Float) -> Unit)
    }

    internal sealed interface StartResult {
        data object Started : StartResult

        /** A newer start superseded this one before it ran; the session was already abandoned. */
        data object Superseded : StartResult

        data class Busy(val sourceLabel: String) : StartResult

        data class Failed(val cause: Throwable?) : StartResult
    }

    internal sealed interface StopResult {
        /** The stop did not match the active session; its terminal callback was already delivered. */
        data object Stale : StopResult

        class Captured(val samples: FloatArray) : StopResult

        data class Failed(val cause: Throwable?) : StopResult
    }

    private val lifecycleMutex = Mutex()
    private var issuedGeneration = 0
    private var activeGeneration: Int? = null
    private var recordingJob: Job? = null
    private var rmsJob: Job? = null

    /**
     * Generations whose own client issued a cancel. Mutated and read only on the
     * service main thread (like [issueGeneration]); generations never repeat, so a
     * mark that is never consumed can't misclassify a later session.
     */
    private val cancelRequestedGenerations = mutableSetOf<Int>()

    /**
     * Issue the generation token for a new start request.
     * Must be called on the same thread that issues [currentGeneration] (the service main thread).
     */
    fun issueGeneration(): Int = ++issuedGeneration

    /** Generation token of the most recently issued start; capture it when a stop/cancel arrives. */
    fun currentGeneration(): Int = issuedGeneration

    /**
     * Records that [generation]'s own client requested cancellation. Call synchronously
     * when the cancel arrives (service main thread), before any queued start coroutine
     * for that generation can resolve as superseded.
     */
    fun markCancelRequested(generation: Int) {
        cancelRequestedGenerations.add(generation)
    }

    /**
     * True when [generation]'s own client cancelled it; consumes the mark.
     * Must be called on the service main thread.
     */
    fun consumeCancelRequest(generation: Int): Boolean = cancelRequestedGenerations.remove(generation)

    suspend fun start(
        generation: Int,
        onReadyForSpeech: () -> Unit,
        onBeginningOfSpeech: () -> Unit,
        onRms: (Float) -> Unit,
    ): StartResult =
        lifecycleMutex.withLock {
            startLocked(generation, onReadyForSpeech, onBeginningOfSpeech, onRms)
        }

    suspend fun stop(
        generation: Int,
        onEndOfSpeech: () -> Unit,
    ): StopResult =
        lifecycleMutex.withLock {
            stopLocked(generation, onEndOfSpeech)
        }

    /** @return true when an active session matching [generation] was cancelled. */
    suspend fun cancel(generation: Int): Boolean =
        lifecycleMutex.withLock {
            cancelLocked(generation)
        }

    /**
     * Synchronous cleanup for service destruction. Leaves the recorder stopped and the gate released.
     */
    fun shutdown() {
        activeGeneration = null
        cancelSessionJobs()
        if (captureGate.isHeld()) {
            recorder.cancel()
            captureGate.releaseCompleted()
        }
    }

    private suspend fun startLocked(
        generation: Int,
        onReadyForSpeech: () -> Unit,
        onBeginningOfSpeech: () -> Unit,
        onRms: (Float) -> Unit,
    ): StartResult {
        if (generation != issuedGeneration) {
            // A newer start was issued before this one ran. The framework only does this
            // after abandoning the old session (e.g. via cancel), so no callback is owed.
            return StartResult.Superseded
        }
        if (activeGeneration != null) {
            return StartResult.Busy(SELF_BUSY_LABEL)
        }

        return try {
            when (val gateResult = captureGate.tryAcquire()) {
                VoiceRecognitionCaptureGateResult.Acquired -> Unit
                is VoiceRecognitionCaptureGateResult.Busy ->
                    return StartResult.Busy(gateResult.sourceLabel)
            }

            onReadyForSpeech()
            recorder.prepare()

            if (!recorder.start()) {
                captureGate.releaseError("Failed to start voice recognition")
                return StartResult.Failed(null)
            }
            captureGate.onRecorderStarted(audioPathLabel)

            onBeginningOfSpeech()

            recordingJob = scope.launch { recorder.collectSamples() }
            rmsJob = scope.launch { recorder.streamRms(onRms) }
            activeGeneration = generation
            StartResult.Started
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A cancelled start must leave nothing held. Service teardown already
            // cleans up via shutdown(), but a caller that cancels its start job
            // through any other route must not leak the gate or a hot microphone.
            // The whole cleanup runs under NonCancellable so that resuming from the
            // off-main teardown hop does not re-throw the pending cancellation before
            // the gate is released (which would leak the capture gate).
            cancelSessionJobs()
            withContext(ioDispatcher + NonCancellable) {
                recorder.cancel()
                captureGate.releaseCompleted()
            }
            throw e
        } catch (e: Exception) {
            cancelSessionJobs()
            cancelRecorderOffMain()
            captureGate.releaseError("Failed to start voice recognition", e)
            StartResult.Failed(e)
        }
    }

    private suspend fun stopLocked(
        generation: Int,
        onEndOfSpeech: () -> Unit,
    ): StopResult {
        if (activeGeneration != generation) {
            return StopResult.Stale
        }
        activeGeneration = null

        return try {
            cancelSessionJobs()
            // AudioRecord teardown + the samples.copyOf for long dictations hops off the
            // service main thread; the mutex stays held across the suspend, so this stop is
            // still fully serialized against any concurrent start/cancel.
            val samples = stopRecorderOffMain()
            captureGate.releaseCompleted()
            onEndOfSpeech()
            StopResult.Captured(samples)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            cancelRecorderOffMain()
            captureGate.releaseError("Failed to stop voice recognition", e)
            StopResult.Failed(e)
        }
    }

    private suspend fun cancelLocked(generation: Int): Boolean {
        if (activeGeneration != generation) {
            return false
        }
        activeGeneration = null
        cancelSessionJobs()
        cancelRecorderOffMain()
        captureGate.releaseCompleted()
        return true
    }

    /**
     * Runs the blocking [RecorderControl.stop] on [ioDispatcher]. Wrapped in [NonCancellable] so a
     * job cancellation racing the stop cannot abort the recorder teardown half-done and leak a hot
     * microphone / unflushed file — the recorder must always end up stopped once we have committed
     * to stopping it.
     */
    private suspend fun stopRecorderOffMain(): FloatArray =
        withContext(ioDispatcher + NonCancellable) {
            recorder.stop()
        }

    /** Runs the blocking [RecorderControl.cancel] on [ioDispatcher]; see [stopRecorderOffMain] for the NonCancellable rationale. */
    private suspend fun cancelRecorderOffMain() {
        withContext(ioDispatcher + NonCancellable) {
            recorder.cancel()
        }
    }

    private fun cancelSessionJobs() {
        rmsJob?.cancel()
        rmsJob = null
        recordingJob?.cancel()
        recordingJob = null
    }

    private companion object {
        private const val DEFAULT_AUDIO_PATH_LABEL = "voice_recognition_service_temp_recording"
        private const val SELF_BUSY_LABEL = "voice recognition"
    }
}
