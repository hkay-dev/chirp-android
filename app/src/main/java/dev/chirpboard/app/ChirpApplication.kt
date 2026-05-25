package dev.chirpboard.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.chirpboard.app.core.modelreadiness.VerificationTrigger
import dev.chirpboard.app.core.transcription.TranscriptionQueueLifecycle
import dev.chirpboard.app.download.ModelReadinessGate
import dev.chirpboard.app.feature.recording.cleanup.OrphanedAudioCleaner
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
    lateinit var modelReadinessGate: ModelReadinessGate
    
    @Inject
    lateinit var orphanedAudioCleaner: OrphanedAudioCleaner

    @Inject
    lateinit var widgetStateObserver: WidgetStateObserver

    private val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default
    )
    
    override fun onCreate() {
        super.onCreate()

        widgetStateObserver.startObserving()
        
        // Migrate API key from plaintext to encrypted storage
        applicationScope.launch {
            val result = apiKeyMigration.migrate()
            Log.d(TAG, "API key migration result: $result")
        }
        
        // Recover any stuck transcriptions from previous session
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
        // Clean up any orphaned audio files from previous aborted sessions
        applicationScope.launch {
            try {
                orphanedAudioCleaner.cleanOrphanedFiles()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Failed to clean orphaned audio files", e)
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
