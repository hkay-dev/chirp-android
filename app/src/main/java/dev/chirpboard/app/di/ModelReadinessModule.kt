package dev.chirpboard.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.modelreadiness.SpeechModelStore
import dev.chirpboard.app.download.ModelDownloader
import dev.chirpboard.app.download.ModelReadinessGate
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ModelReadinessModule {
    @Provides
    @Singleton
    fun provideModelDownloader(
        @ApplicationContext context: Context,
    ): ModelDownloader = ModelDownloader(context)

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
}
