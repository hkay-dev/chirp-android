package dev.chirpboard.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chirpboard.app.core.modelreadiness.SpeechModelDownloadGateway
import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.modelreadiness.SpeechModelStore
import dev.chirpboard.app.core.transcription.LocalSpeechModelSelectionStore
import dev.chirpboard.app.download.ModelDownloadWorkGateway
import dev.chirpboard.app.download.ModelDownloader
import dev.chirpboard.app.download.ModelReadinessGate
import dev.chirpboard.app.model.LocalSpeechModelPreferences
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ModelReadinessModule {
    @Provides
    @Singleton
    fun provideModelDownloader(
        @ApplicationContext context: Context,
        modelSelectionStore: LocalSpeechModelSelectionStore,
    ): ModelDownloader = ModelDownloader(context, modelSelectionStore = modelSelectionStore)

    @Provides
    @Singleton
    fun provideLocalSpeechModelSelectionStore(
        preferences: LocalSpeechModelPreferences,
    ): LocalSpeechModelSelectionStore = preferences

    @Provides
    @Singleton
    fun provideSpeechModelStore(
        modelDownloader: ModelDownloader,
    ): SpeechModelStore = modelDownloader

    @Provides
    @Singleton
    fun provideModelReadinessGate(
        speechModelStore: SpeechModelStore,
    ): ModelReadinessGate = ModelReadinessGate(speechModelStore)

    @Provides
    @Singleton
    fun provideSpeechModelReadinessGate(
        gate: ModelReadinessGate,
    ): SpeechModelReadinessGate = gate

    @Provides
    @Singleton
    fun provideSpeechModelDownloadGateway(
        gateway: ModelDownloadWorkGateway,
    ): SpeechModelDownloadGateway = gateway
}
