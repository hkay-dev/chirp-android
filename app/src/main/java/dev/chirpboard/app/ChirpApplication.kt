package dev.chirpboard.app

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.modelreadiness.VerificationTrigger
import dev.chirpboard.app.core.transcription.TranscriptionQueueLifecycle
import dev.chirpboard.app.feature.recording.session.RecordingStartupCoordinator
import dev.chirpboard.app.feature.widget.WidgetStateObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ChirpApplication : Application(), Configuration.Provider {
    
    @Inject
    lateinit var workerFactory: HiltWorkerFactory
    
    @Inject
    lateinit var transcriptionQueueLifecycle: TranscriptionQueueLifecycle
    
    @Inject
    lateinit var apiKeyMigration: ApiKeyMigration

    @Inject
    lateinit var modelReadinessGate: SpeechModelReadinessGate
    
    @Inject
    lateinit var recordingStartupCoordinator: RecordingStartupCoordinator

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
            val result = apiKeyMigration.migrate()
            Log.d(TAG, "API key migration result: $result")
        }
        
        applicationScope.launch {
            try {
                transcriptionQueueLifecycle.processPendingOnStartup()
                transcriptionQueueLifecycle.startContinuousReconciliation(applicationScope)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Failed to recover transcriptions on startup", e)
            }
        }

        applicationScope.launch {
            try {
                modelReadinessGate.warmupIfNeeded(VerificationTrigger.APP_STARTUP)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Failed to warm model readiness on startup", e)
            }
        }

        applicationScope.launch {
            try {
                recordingStartupCoordinator.onAppStart()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Failed recording startup coordinator", e)
            }
        }

    }
    
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
    
    companion object {
        private const val TAG = "ChirpApplication"
    }
}
