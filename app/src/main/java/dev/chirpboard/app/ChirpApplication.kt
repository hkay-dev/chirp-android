package dev.chirpboard.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import dev.chirpboard.app.download.SpeechModelWarmupCoordinator
import dev.chirpboard.app.core.transcription.TranscriptionQueueLifecycle
import dev.chirpboard.app.feature.recording.session.RecordingStartupCoordinator
import dev.chirpboard.app.feature.widget.WidgetStateObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    lateinit var apiKeyMigration: Lazy<ApiKeyMigration>

    @Inject
    lateinit var speechModelWarmupCoordinator: Lazy<SpeechModelWarmupCoordinator>

    @Inject
    lateinit var recordingStartupCoordinator: Lazy<RecordingStartupCoordinator>

    @Inject
    lateinit var widgetStateObserver: WidgetStateObserver

    private val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default
    )
    
    override fun onCreate() {
        super.onCreate()

        DebugStrictMode.enableIfDebug(
            (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        )

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
     * LOAD-1 / KBD-1: the ~660MB Parakeet recognizer is kept warm while the keyboard is enabled
     * (it is never released on a single surface's start/teardown anymore). The ONLY production
     * path that frees it is genuine OS memory pressure: when the system asks the process to trim
     * at a level that signals real pressure ([shouldReleaseRecognizerOnTrim]), release the shared
     * singleton so this RAM-heavy process is a less attractive low-memory-killer target. The model
     * reloads lazily / eagerly on the next IME bind, so dictation correctness is preserved.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (shouldReleaseRecognizerOnTrim(level)) {
            releaseRecognizerForMemoryPressure(reason = "onTrimMemory(level=$level)")
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        releaseRecognizerForMemoryPressure(reason = "onLowMemory")
    }

    private fun releaseRecognizerForMemoryPressure(reason: String) {
        applicationScope.launch {
            try {
                if (RecognizerManager.isResident()) {
                    Log.i(TAG, "Releasing speech recognizer under memory pressure: $reason")
                    RecognizerManager.releaseRecognizer()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Failed to release recognizer under memory pressure", e)
            }
        }
    }

    companion object {
        private const val TAG = "ChirpApplication"

        /**
         * Whether an [onTrimMemory] level signals enough memory pressure to free the resident
         * speech recognizer.
         *
         * The `TRIM_MEMORY_*` constants are NOT a single ordered scale, so this is an explicit
         * allowlist, not a `>=` threshold. We release only on genuine pressure:
         *  - the foreground `TRIM_MEMORY_RUNNING_LOW`/`_CRITICAL` levels (the app is still running
         *    but the system is actively tight); and
         *  - `TRIM_MEMORY_COMPLETE` (the process is backgrounded and about to be killed — freeing
         *    660MB makes it a far less attractive low-memory-killer target).
         *
         * Deliberately EXCLUDED are the routine background-LRU levels `TRIM_MEMORY_UI_HIDDEN`,
         * `TRIM_MEMORY_BACKGROUND`, `TRIM_MEMORY_MODERATE` and the gentle `TRIM_MEMORY_RUNNING_MODERATE`:
         * the keyboard goes UI-hidden between dictations and apps are routinely background-LRU'd, so
         * trimming there would reintroduce the cold model reload the user complained about (LOAD-1).
         * Extracted as a pure function so the policy is unit-testable without an Application.
         */
        internal fun shouldReleaseRecognizerOnTrim(level: Int): Boolean =
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE

        /**
         * Delay before kicking off the deferrable startup recovery/janitorial work, to keep it
         * off the first-frame / keyboard-inflation critical path on a cold process start (the IME
         * shares this process). Short enough that recovery still happens promptly after launch.
         */
        private const val STARTUP_RECOVERY_DELAY_MS = 3_000L
    }
}
