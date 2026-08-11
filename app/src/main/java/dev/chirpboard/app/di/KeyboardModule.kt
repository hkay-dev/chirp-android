package dev.chirpboard.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chirpboard.app.SelectableLocalTranscriberProvider
import dev.chirpboard.app.SelectableStreamingTranscriberProvider
import dev.chirpboard.app.StreamingSherpaRecognizerProvider
import dev.chirpboard.app.core.transcription.StreamingTranscriberProvider
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.core.transcription.LocalSpeechModelActivator
import dev.chirpboard.app.core.transcription.LocalSpeechModelSelectionStore
import dev.chirpboard.app.download.ModelDownloader
import javax.inject.Singleton

/**
 * Hilt module that provides keyboard-related dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object KeyboardModule {
    @Provides
    @Singleton
    fun provideSelectableLocalTranscriberProvider(
        @ApplicationContext context: Context,
        modelDownloader: ModelDownloader,
        selectionStore: LocalSpeechModelSelectionStore,
    ): SelectableLocalTranscriberProvider =
        SelectableLocalTranscriberProvider(context, modelDownloader, selectionStore)

    @Provides
    @Singleton
    fun provideTranscriberProvider(
        provider: SelectableLocalTranscriberProvider,
    ): TranscriberProvider = provider

    @Provides
    @Singleton
    fun provideLocalSpeechModelActivator(
        provider: SelectableLocalTranscriberProvider,
    ): LocalSpeechModelActivator = provider

    @Provides
    @Singleton
    fun provideStreamingTranscriberProvider(
        @ApplicationContext context: Context,
        selectionStore: LocalSpeechModelSelectionStore,
    ): StreamingTranscriberProvider =
        SelectableStreamingTranscriberProvider(
            selectionStore = selectionStore,
            sherpa = StreamingSherpaRecognizerProvider(context),
        )
}
