package dev.chirpboard.app

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import dev.chirpboard.app.download.SpeechModelWarmupCoordinator
import dev.chirpboard.app.core.transcription.KeyboardDictationHandoff
import dev.chirpboard.app.core.reliability.DictationReliabilityMetrics
import dev.chirpboard.app.core.transcription.InlineCapturePersistence
import dev.chirpboard.app.core.transcription.TranscriptionQueueLifecycle
import dev.chirpboard.app.feature.recording.session.RecordingStartupCoordinator
import dev.chirpboard.app.feature.transcription.TerminalRecordingNotificationDelivery
import dev.chirpboard.app.feature.widget.WidgetStateObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class ChirpApplication : Application(), Configuration.Provider {
    
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // The reliability/janitorial coordinators are injected as dagger.Lazy so Hilt member
    // injection inside super.onCreate() constructs nothing heavier than the worker factory.
    // Eagerly constructing them on the main thread at process start was the regression behind
    // START-4: ApiKeyMigration pulls in LlmPreferences, whose construction performed Android
    // Keystore + EncryptedSharedPreferences IO (100-500 ms) on the main thread. The IME shares
    // this process, so every cold keyboard show paid that cost before the first frame. Each
    // Lazy is now resolved (and thus constructed) on a background dispatcher, off the critical
    // onCreate path that races first-frame / keyboard inflation.
    @Inject
    lateinit var transcriptionQueueLifecycle: Lazy<TranscriptionQueueLifecycle>

    @Inject
    lateinit var keyboardDictationHandoff: Lazy<KeyboardDictationHandoff>

    @Inject
    lateinit var inlineCapturePersistence: Lazy<InlineCapturePersistence>

    @Inject
    lateinit var terminalRecordingNotificationDelivery: Lazy<TerminalRecordingNotificationDelivery>

    @Inject
    lateinit var apiKeyMigration: Lazy<ApiKeyMigration>

    @Inject
    lateinit var speechModelWarmupCoordinator: Lazy<SpeechModelWarmupCoordinator>

    @Inject
    lateinit var recordingStartupCoordinator: Lazy<RecordingStartupCoordinator>

    @Inject
    lateinit var widgetStateObserver: WidgetStateObserver

    @Inject
    lateinit var recognizerIdleReleasePolicy: RecognizerIdleReleasePolicy

    private val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default
    )

    override fun onCreate() {
        super.onCreate()

        // ERR-21: local-only crash breadcrumbs (rotating stack-trace files; never uploaded).
        // Installed first so a crash anywhere in the startup path below is still recorded.
        CrashLogWriter(File(filesDir, CrashLogWriter.LOG_DIR_NAME)).install()
        DictationReliabilityMetrics.initialize(this)

        DebugStrictMode.enableIfDebug(
            (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        )

        // PRF-2: arm the usage-bound idle-release policy for the resident recognizer.
        recognizerIdleReleasePolicy.start()

        widgetStateObserver.startObserving()

        applicationScope.launch {
            val result = apiKeyMigration.get().migrate()
            Log.d(TAG, "API key migration result: $result")
        }

        // START-2: the recovery/janitorial machinery (queue reconciliation kickoff, model-warmup
        // candidate detection, journal pruning + recursive orphan sweep) does not need to complete
        // before the keyboard renders, yet it shares this process with the IME — so on a
        // keyboard-only cold start it was racing view inflation for CPU and disk IO. Defer it past
        // the first-frame / keyboard-inflation window before kicking it off. The recovery
        // guarantees are unchanged: every coordinator still runs on each process start, just a
        // moment after the latency-sensitive window rather than synchronously inside it.
        applicationScope.launch {
            delay(STARTUP_RECOVERY_DELAY_MS)

            // Replay the file-to-Room handoff before either queue or orphan cleanup observes
            // the recordings directory. This keeps an interrupted raw PCM move visible.
            try {
                val recoveredHandoffs = keyboardDictationHandoff.get().recoverPendingHandoffs()
                if (recoveredHandoffs > 0) {
                    Log.i(TAG, "Recovered $recoveredHandoffs interrupted keyboard handoff(s)")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Failed to recover keyboard handoffs on startup", e)
            }

            try {
                val recoveredCheckpoints = inlineCapturePersistence.get().recoverCheckpoints()
                if (recoveredCheckpoints > 0) {
                    Log.i(TAG, "Recovered $recoveredCheckpoints interrupted keyboard checkpoint(s)")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Failed to recover keyboard checkpoints on startup", e)
            }

            launch {
                try {
                    val lifecycle = transcriptionQueueLifecycle.get()
                    lifecycle.processPendingOnStartup()
                    lifecycle.startContinuousReconciliation(applicationScope)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "Failed to recover transcriptions on startup", e)
                }
            }

            launch {
                try {
                    val delivered = terminalRecordingNotificationDelivery.get().recoverPendingNotifications()
                    if (delivered > 0) {
                        Log.i(TAG, "Delivered $delivered pending transcription notification(s)")
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "Failed to recover transcription notifications on startup", e)
                }
            }

            launch {
                try {
                    speechModelWarmupCoordinator.get().warmupOnAppStartupIfCandidate()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "Failed to evaluate speech model warmup candidates on startup", e)
                }
            }

            launch {
                try {
                    recordingStartupCoordinator.get().onAppStart()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "Failed recording startup coordinator", e)
                }
            }
        }
    }
    
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * LOAD-1 / KBD-1 / PRF-1 / REL-09: the ~660MB Parakeet recognizer is kept warm while the
     * keyboard is enabled (it is never released on a single surface's start/teardown). It is
     * freed only by genuine memory pressure or by the ~30-minute idle timeout
     * ([RecognizerIdleReleasePolicy]); both paths refuse to release while any capture or
     * transcription surface could need the model synchronously.
     *
     * Pressure signalling, Android 16 reality: the legacy `RUNNING_LOW`/`RUNNING_CRITICAL`/
     * `COMPLETE` levels (and `onLowMemory`) are no longer delivered since API 34 — the system
     * only sends `TRIM_MEMORY_UI_HIDDEN` and `TRIM_MEMORY_BACKGROUND`, which fire on routine
     * keyboard hides / backgrounding and are NOT by themselves a pressure signal (releasing on
     * them unconditionally would reintroduce the LOAD-1 cold-reload regression). So on those
     * delivered levels we poll `ActivityManager.getMemoryInfo()` and release only when the
     * system reports it is genuinely low on memory. The model reloads lazily / eagerly on the
     * next IME bind, so dictation correctness is preserved.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (trimMemoryAction(level)) {
            TrimMemoryAction.RELEASE_IF_UNUSED ->
                releaseRecognizerForMemoryPressure(reason = "onTrimMemory(level=$level)")

            TrimMemoryAction.RELEASE_IF_SYSTEM_LOW ->
                releaseRecognizerForMemoryPressure(
                    reason = "onTrimMemory(level=$level)+lowMemory",
                    requireSystemLowMemory = true,
                )

            TrimMemoryAction.KEEP -> Unit
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // Equivalent to TRIM_MEMORY_COMPLETE and equally undelivered since API 34; kept wired
        // as a free best-effort valve in case an OEM build still emits it.
        releaseRecognizerForMemoryPressure(reason = "onLowMemory")
    }

    private fun releaseRecognizerForMemoryPressure(
        reason: String,
        requireSystemLowMemory: Boolean = false,
    ) {
        // Trim callbacks are only delivered after onCreate, but stay defensive about the
        // lateinit policy in case an OEM delivers one mid-injection.
        if (!::recognizerIdleReleasePolicy.isInitialized) return
        // Fast path: nothing resident, nothing to do (avoids the getMemoryInfo binder call on
        // every routine keyboard hide while the model is cold).
        if (!RecognizerManager.isResident()) return
        applicationScope.launch {
            try {
                if (requireSystemLowMemory && !isSystemLowOnMemory()) return@launch
                Log.i(TAG, "Releasing speech recognizer under memory pressure: $reason")
                recognizerIdleReleasePolicy.releaseNowIfUnused(reason)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Failed to release recognizer under memory pressure", e)
            }
        }
    }

    /** PRF-1: real pressure check for the delivered-but-routine trim levels. */
    private fun isSystemLowOnMemory(): Boolean {
        val activityManager = getSystemService(ActivityManager::class.java) ?: return false
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.lowMemory
    }

    /** How [onTrimMemory] treats a given trim level with respect to the resident recognizer. */
    internal enum class TrimMemoryAction {
        /** Genuine-pressure legacy levels: release immediately (if not in use). */
        RELEASE_IF_UNUSED,

        /** Routine delivered levels: release only if `getMemoryInfo()` reports low memory. */
        RELEASE_IF_SYSTEM_LOW,

        /** Keep the model warm. */
        KEEP,
    }

    companion object {
        private const val TAG = "ChirpApplication"

        /**
         * Maps an [onTrimMemory] level to the recognizer residency action.
         *
         * The `TRIM_MEMORY_*` constants are NOT a single ordered scale, so this is an explicit
         * mapping, not a `>=` threshold:
         *  - `RUNNING_LOW`/`RUNNING_CRITICAL`/`COMPLETE` mean genuine pressure. They are
         *    deprecated and undelivered since API 34 (this app is Android 16-only), but they are
         *    kept as a zero-cost best-effort valve and release immediately when seen.
         *  - `UI_HIDDEN`/`BACKGROUND` are the ONLY levels Android 14+ actually delivers. They
         *    fire constantly (the keyboard goes UI-hidden between dictations), so they release
         *    only when an explicit [ActivityManager.getMemoryInfo] poll confirms the system is
         *    genuinely low — never unconditionally (that would reintroduce LOAD-1).
         *  - Everything else (`RUNNING_MODERATE`, `MODERATE`) keeps the model warm.
         * Extracted as a pure function so the policy is unit-testable without an Application.
         */
        @Suppress("DEPRECATION") // Legacy levels retained as a documented best-effort valve.
        internal fun trimMemoryAction(level: Int): TrimMemoryAction =
            when (level) {
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
                ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
                -> TrimMemoryAction.RELEASE_IF_UNUSED

                ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
                ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
                -> TrimMemoryAction.RELEASE_IF_SYSTEM_LOW

                else -> TrimMemoryAction.KEEP
            }

        /**
         * Delay before kicking off the deferrable startup recovery/janitorial work, to keep it
         * off the first-frame / keyboard-inflation critical path on a cold process start (the IME
         * shares this process). Short enough that recovery still happens promptly after launch.
         */
        private const val STARTUP_RECOVERY_DELAY_MS = 3_000L
    }
}
