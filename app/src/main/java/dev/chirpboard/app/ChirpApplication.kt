package dev.chirpboard.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.chirpboard.app.download.ModelReadinessGate
import dev.chirpboard.app.download.VerificationTrigger
import dev.chirpboard.app.feature.transcription.TranscriptionQueueManager
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
    lateinit var transcriptionQueueManager: TranscriptionQueueManager
    
    @Inject
    lateinit var apiKeyMigration: ApiKeyMigration

    @Inject
    lateinit var modelReadinessGate: ModelReadinessGate
    
    private val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default
    )
    
    override fun onCreate() {
        super.onCreate()
        
        // Migrate API key from plaintext to encrypted storage
        applicationScope.launch {
            val result = apiKeyMigration.migrate()
            Log.d(TAG, "API key migration result: $result")
        }
        
        // Recover any stuck transcriptions from previous session
        applicationScope.launch {
            try {
                transcriptionQueueManager.processPendingOnStartup()
                transcriptionQueueManager.startContinuousReconciliation(applicationScope)
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
    }
    
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
    
    companion object {
        private const val TAG = "ChirpApplication"
    }
}
