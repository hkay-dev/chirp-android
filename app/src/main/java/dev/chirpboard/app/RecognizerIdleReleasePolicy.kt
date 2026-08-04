package dev.chirpboard.app

import android.util.Log
import androidx.annotation.VisibleForTesting
import dev.chirpboard.app.core.modelreadiness.LocalRecognizerWarmWindow
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRF-1 / PRF-2 / REL-09: usage-bound residency policy for the ~660MB Parakeet recognizer.
 *
 * Background: the old "memory-pressure-only" release listened exclusively for
 * `TRIM_MEMORY_RUNNING_LOW/_CRITICAL/_COMPLETE`, which Android stopped delivering at API 34 —
 * on this Android 16-only app the pressure valve was dead code and the model stayed resident
 * until process death. This policy replaces it with two supported mechanisms:
 *
 * 1. IME warm window: the model stays resident for the whole visible IME session. Once hidden,
 *    it stays warm for [IDLE_RELEASE_CUTOFF_MS] after both the hide and the most recent use, then
 *    releases. A quick app or field switch therefore remains instant without pinning ~700MB all
 *    day.
 * 2. Pressure assist: [releaseNowIfUnused] is invoked from [ChirpApplication]'s trim hooks
 *    (delivered levels + `ActivityManager.getMemoryInfo().lowMemory`) to free the model
 *    immediately when the system reports genuine pressure — but only when unused.
 *
 * Reliability contract (sacred): the model is NEVER released while any capture/transcription
 * surface could need it synchronously. Releases are refused while:
 *  - the global recording state is non-idle ([RecordingStateManager] is the single lock every
 *    capture surface — app, keyboard dictation, widget, recognition dialog — must acquire);
 *  - the transcription queue has live work (recordings in PENDING_TRANSCRIPTION or
 *    TRANSCRIBING; enhancement-only work does not need the recognizer);
 *  - an in-flight transcription holds a usage lease (checked inside [RecognizerManager]);
 *  - (idle path only) the recognizer was used within the cutoff window.
 * As defense in depth, `SherpaRecognizerProvider.transcribe` re-warms the model on demand, so
 * even a release that slips past a surface about to transcribe degrades to a masked re-load,
 * never to lost speech.
 */
@Singleton
class RecognizerIdleReleasePolicy
    @Inject
    constructor(
        private val recordingStateManager: RecordingStateManager,
        private val recordingRepository: dagger.Lazy<RecordingRepository>,
    ) : RecognizerManager.ResidencyListener, LocalRecognizerWarmWindow {
        @VisibleForTesting
        internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        @VisibleForTesting
        internal var clock: () -> Long = System::currentTimeMillis

        @VisibleForTesting
        internal var idleCutoffMs: Long = IDLE_RELEASE_CUTOFF_MS

        @VisibleForTesting
        internal var busyRecheckMs: Long = BUSY_RECHECK_MS

        private val timerLock = Any()
        private var idleTimer: Job? = null
        private var imeVisible = false
        private var lastImeHiddenAtMs = 0L

        /**
         * Registers for residency callbacks and arms the timer if a recognizer is already
         * resident. Cheap; safe to call from Application.onCreate on the main thread.
         */
        fun start() {
            RecognizerManager.setResidencyListener(this)
            synchronized(timerLock) {
                if (lastImeHiddenAtMs == 0L) lastImeHiddenAtMs = clock()
                rearmIdleTimerLocked()
            }
        }

        override fun onRecognizerResident() {
            synchronized(timerLock) {
                rearmIdleTimerLocked()
            }
        }

        override fun onRecognizerReleased() {
            synchronized(timerLock) {
                idleTimer?.cancel()
                idleTimer = null
            }
        }

        /** Keeps the recognizer resident while the IME is visible and starts a bounded grace window on hide. */
        override fun onImeVisibilityChanged(visible: Boolean) {
            synchronized(timerLock) {
                if (imeVisible == visible) return
                imeVisible = visible
                if (!visible) lastImeHiddenAtMs = clock()
                rearmIdleTimerLocked()
            }
        }

        /**
         * Immediately frees the recognizer if (and only if) it is not in use — the
         * memory-pressure entry point. Returns the gating decision for logging.
         */
        suspend fun releaseNowIfUnused(reason: String): IdleReleaseDecision {
            val decision =
                RecognizerManager.releaseIfUnused(
                    minIdleMs = 0L,
                    nowMs = clock(),
                    isExternallyBusy = { isExternallyBusy(protectVisibleIme = false) },
                )
            Log.i(TAG, "Pressure release ($reason) -> $decision")
            return decision
        }

        /**
         * Single re-arming timer: while the recognizer is resident it sleeps until the
         * recency stamp could have expired, then attempts a gated release. Usage does not
         * reschedule the timer (uses only bump the recency stamp); the loop re-computes the
         * remaining window on each wake, so it fires only a handful of times per warm period
         * and runs nothing at all when the model is not resident.
         */
        private suspend fun idleTimerLoop() {
            while (RecognizerManager.isResident()) {
                val warmWindowStart = maxOf(RecognizerManager.lastUsedAtMs(), lastImeHiddenAtMs)
                val remainingMs = warmWindowStart + idleCutoffMs - clock()
                if (remainingMs > 0) {
                    delay(remainingMs)
                    continue
                }
                val decision =
                    RecognizerManager.releaseIfUnused(
                        minIdleMs = idleCutoffMs,
                        nowMs = clock(),
                        isExternallyBusy = { isExternallyBusy(protectVisibleIme = true) },
                    )
                when (decision) {
                    IdleReleaseDecision.RELEASE -> {
                        Log.i(TAG, "Released idle recognizer after ${idleCutoffMs / MS_PER_MINUTE} min unused")
                        return
                    }

                    IdleReleaseDecision.NOT_RESIDENT -> return

                    IdleReleaseDecision.IN_USE,
                    IdleReleaseDecision.EXTERNALLY_BUSY,
                    IdleReleaseDecision.RECENTLY_USED,
                    -> {
                        Log.d(TAG, "Idle release deferred: $decision")
                        delay(busyRecheckMs)
                    }
                }
            }
        }

        private fun rearmIdleTimerLocked() {
            idleTimer?.cancel()
            idleTimer = null
            if (!imeVisible && RecognizerManager.isResident()) {
                idleTimer = scope.launch { idleTimerLoop() }
            }
        }

        /**
         * True while any surface outside [RecognizerManager]'s own lease accounting still
         * needs the model: an active capture (every capture surface holds the global
         * recording lock) or live transcription-queue work. Repository read failures count
         * as busy — when the queue state cannot be verified, keeping the model warm is the
         * safe direction.
         */
        @VisibleForTesting
        internal suspend fun isExternallyBusy(protectVisibleIme: Boolean = true): Boolean {
            if (protectVisibleIme && synchronized(timerLock) { imeVisible }) return true
            if (recordingStateManager.state.value !is RecordingState.Idle) return true
            return hasLiveTranscriptionWork()
        }

        private suspend fun hasLiveTranscriptionWork(): Boolean {
            val repository = recordingRepository.get()
            val transcribing = repository.getRecordingsByStatus(RecordingStatus.TRANSCRIBING).first()
            if (transcribing.errorMessage != null || transcribing.value.isNotEmpty()) return true
            val pending = repository.getRecordingsByStatus(RecordingStatus.PENDING_TRANSCRIPTION).first()
            return pending.errorMessage != null || pending.value.isNotEmpty()
        }

        companion object {
            private const val TAG = "RecognizerIdlePolicy"
            private const val MS_PER_MINUTE = 60_000L

            /** Fast field/app switches stay warm; long inactive periods return the model RAM. */
            const val IDLE_RELEASE_CUTOFF_MS = 5 * MS_PER_MINUTE

            /**
             * Re-check cadence once the idle window has expired but a gate is holding the
             * release (e.g. an hours-long recording in progress). Only runs in that state.
             */
            private const val BUSY_RECHECK_MS = 30_000L
        }
    }
