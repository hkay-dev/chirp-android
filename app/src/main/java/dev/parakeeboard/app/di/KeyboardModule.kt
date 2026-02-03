package dev.parakeeboard.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.parakeeboard.app.RecognizerManager
import dev.parakeeboard.app.SherpaRecognizer
import dev.parakeeboard.app.download.ModelDownloader
import dev.parakeeboard.app.core.transcription.TranscriberProvider
import dev.parakeeboard.app.feature.keyboard.service.RecognizerProvider
import javax.inject.Singleton

/**
 * Hilt module that provides keyboard-related dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object KeyboardModule {
    
    @Provides
    @Singleton
    fun provideRecognizerProvider(
        @ApplicationContext context: Context
    ): RecognizerProvider {
        return SherpaRecognizerProvider(context)
    }
    
    @Provides
    @Singleton
    fun provideTranscriberProvider(
        @ApplicationContext context: Context
    ): TranscriberProvider {
        return SherpaRecognizerProvider(context)
    }
}

/**
 * Implementation of RecognizerProvider and TranscriberProvider that wraps SherpaRecognizer.
 */
class SherpaRecognizerProvider(
    private val context: Context
) : RecognizerProvider, TranscriberProvider {
    
    private var recognizer: SherpaRecognizer? = null
    private val downloader = ModelDownloader(context)
    
    override fun isReady(): Boolean {
        return recognizer?.isReady == true
    }
    
    override fun isModelDownloaded(): Boolean {
        return downloader.isModelDownloaded()
    }
    
    override suspend fun initialize(): Boolean {
        if (recognizer == null) {
            recognizer = RecognizerManager.getRecognizer(context)
        }
        return recognizer?.isReady == true
    }
    
    override suspend fun transcribe(samples: FloatArray): String {
        return recognizer?.transcribe(samples) ?: ""
    }
    
    override suspend fun transcribe(samples: FloatArray, sampleRate: Int): String {
        return recognizer?.transcribe(samples, sampleRate) ?: ""
    }
}
