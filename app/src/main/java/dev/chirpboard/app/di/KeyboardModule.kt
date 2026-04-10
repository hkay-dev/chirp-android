package dev.chirpboard.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chirpboard.app.RecognizerManager
import dev.chirpboard.app.SherpaRecognizer
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
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
    fun provideTranscriberProvider(
        @ApplicationContext context: Context,
    ): TranscriberProvider = SherpaRecognizerProvider(context)
}

/**
 * Implementation of TranscriberProvider that wraps SherpaRecognizer.
 */
class SherpaRecognizerProvider(
    private val context: Context,
) : TranscriberProvider {
    private var recognizer: SherpaRecognizer? = null
    private val downloader = ModelDownloader(context)

    override fun isReady(): Boolean = recognizer?.isReady == true

    override fun isModelDownloaded(): Boolean = downloader.isModelDownloaded()

    override suspend fun initialize(): Boolean {
        if (recognizer == null) {
            recognizer = RecognizerManager.getRecognizer(context)
        }
        return recognizer?.isReady == true
    }

    override suspend fun transcribe(
        samples: FloatArray,
        sampleRate: Int,
    ): TranscriptionOutcome {
        val activeRecognizer = recognizer
        return activeRecognizer?.transcribeOutcome(samples, sampleRate)
            ?: TranscriptionOutcome.ModelUnavailable("Recognizer is not initialized")
    }
    override suspend fun release() {
        recognizer?.release()
        recognizer = null
        RecognizerManager.releaseRecognizer()
    }

}
