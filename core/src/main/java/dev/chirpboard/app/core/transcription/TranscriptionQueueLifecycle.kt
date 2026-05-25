package dev.chirpboard.app.core.transcription

import kotlinx.coroutines.CoroutineScope

interface TranscriptionQueueLifecycle {
    suspend fun processPendingOnStartup()

    fun startContinuousReconciliation(scope: CoroutineScope)
}
