package dev.chirpboard.app.di

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chirpboard.app.RecognizerManager
import dev.chirpboard.app.SelectableLocalTranscriberProvider
import dev.chirpboard.app.SelectableStreamingTranscriberProvider
import dev.chirpboard.app.SherpaRecognizer
import dev.chirpboard.app.StreamingSherpaRecognizerProvider
import dev.chirpboard.app.core.transcription.StreamingTranscriberProvider
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import dev.chirpboard.app.core.transcription.LocalSpeechModelActivator
import dev.chirpboard.app.core.transcription.LocalSpeechModelSelectionStore
import dev.chirpboard.app.core.modelreadiness.LocalRecognizerWarmWindow
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
    fun provideLocalRecognizerWarmWindow(): LocalRecognizerWarmWindow =
        object : LocalRecognizerWarmWindow {
            override fun onImeVisibilityChanged(visible: Boolean) = Unit
        }

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

/**
 * Implementation of TranscriberProvider that wraps SherpaRecognizer.
 */
class SherpaRecognizerProvider(
    private val context: Context,
    private val downloader: ModelDownloader,
) : TranscriberProvider {
    private var recognizer: SherpaRecognizer? = null

    override fun isReady(): Boolean =
        recognizer?.isReady == true || RecognizerManager.peekReadyRecognizer() != null

    override fun isModelDownloaded(): Boolean = downloader.isModelDownloaded()

    override suspend fun initialize(): Boolean {
        val started = SystemClock.elapsedRealtime()
        val success = RecognizerManager.initializeRecognizer(context.applicationContext)
        Log.i(
            "SherpaRecognizerProvider",
            "benchmark backend=sherpa-parakeet-600m-int8 phase=load elapsedMs=${SystemClock.elapsedRealtime() - started} success=$success",
        )
        recognizer =
            if (success) {
                RecognizerManager.peekReadyRecognizer()
            } else {
                null
            }
        return success
    }

    /**
     * Transcribes under a [RecognizerManager] usage lease so pressure and model-switch release
     * paths treat the recognizer as in use for the whole decode.
     *
     * If confirmed pressure freed the shared recognizer, re-warm it here instead of failing a
     * dictation whose complete audio has already been saved.
     */
    override suspend fun transcribe(
        samples: FloatArray,
        sampleRate: Int,
    ): TranscriptionOutcome =
        RecognizerManager.withUsageLease {
            // Resolve the native instance only after the lease is visible to releaseIfUnused.
            // A pressure release that won the mutex first may have freed the cached reference;
            // in that case this safely re-warms under the already-active lease.
            val activeRecognizer =
                readyRecognizer()
                    ?: rewarmedRecognizer()
                    ?: return@withUsageLease TranscriptionOutcome.ModelUnavailable("Recognizer is not initialized")
            val started = SystemClock.elapsedRealtime()
            val outcome = activeRecognizer.transcribeOutcome(samples, sampleRate)
            val elapsed = SystemClock.elapsedRealtime() - started
            val audioMs = if (sampleRate > 0) samples.size * 1_000L / sampleRate else 0L
            val rtf = if (audioMs > 0) elapsed.toDouble() / audioMs else 0.0
            Log.i(
                "SherpaRecognizerProvider",
                "benchmark backend=sherpa-parakeet-600m-int8 phase=decode audioMs=$audioMs elapsedMs=$elapsed rtf=$rtf",
            )
            outcome
        }

    /**
     * Prefers an instance that is still loaded: a cached reference whose native recognizer has
     * been released reports `isReady == false` and must not shadow a freshly re-initialized
     * shared singleton.
     */
    private fun readyRecognizer(): SherpaRecognizer? =
        recognizer?.takeIf { it.isReady }
            ?: RecognizerManager.peekReadyRecognizer()?.also { recognizer = it }

    private suspend fun rewarmedRecognizer(): SherpaRecognizer? {
        if (!downloader.isModelDownloaded()) return null
        if (!RecognizerManager.initializeRecognizer(context.applicationContext)) return null
        return RecognizerManager.peekReadyRecognizer()?.also { recognizer = it }
    }

    /**
     * Frees the shared recognizer from memory. LOAD-1 / KBD-1: this releases the process-global
     * [RecognizerManager] singleton shared by the keyboard and the recognition Activity, so it must
     * only be reached from an explicit model switch or delete-model intent, never from a single
     * surface's start or teardown. Confirmed pressure uses the separate residency policy.
     */
    override suspend fun release() {
        recognizer = null
        RecognizerManager.releaseForModelSwitchIfUnused()
    }

}
