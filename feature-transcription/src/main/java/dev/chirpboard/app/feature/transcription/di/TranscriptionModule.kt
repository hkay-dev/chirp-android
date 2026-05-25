package dev.chirpboard.app.feature.transcription.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.chirpboard.app.core.transcription.TranscriptionQueueLifecycle
import dev.chirpboard.app.core.transcription.TranscriptionRecovery
import dev.chirpboard.app.feature.transcription.TranscriptionQueueManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TranscriptionModule {
    @Binds
    @Singleton
    abstract fun bindTranscriptionRecovery(
        manager: TranscriptionQueueManager,
    ): TranscriptionRecovery

    @Binds
    @Singleton
    abstract fun bindTranscriptionQueueLifecycle(
        manager: TranscriptionQueueManager,
    ): TranscriptionQueueLifecycle
}
