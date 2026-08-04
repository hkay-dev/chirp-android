package dev.chirpboard.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chirpboard.app.RecognizerManager
import dev.chirpboard.app.SherpaRecognizer
import dev.chirpboard.app.StreamingSherpaRecognizerProvider
import dev.chirpboard.app.core.transcription.StreamingTranscriberProvider
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
        modelDownloader: ModelDownloader,
    ): TranscriberProvider = SherpaRecognizerProvider(context, modelDownloader)

    @Provides
    @Singleton
    fun provideStreamingTranscriberProvider(
        @ApplicationContext context: Context,
    ): StreamingTranscriberProvider = StreamingSherpaRecognizerProvider(context)
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
        val success = RecognizerManager.initializeRecognizer(context.applicationContext)
        recognizer =
            if (success) {
                RecognizerManager.peekReadyRecognizer()
            } else {
                null
            }
        return success
    }

    /**
     * Transcribes under a [RecognizerManager] usage lease so the idle/pressure release paths
     * (PRF-1/PRF-2) treat the recognizer as in-use for the whole decode and refresh its
     * recency stamp when the work completes.
     *
     * Defense in depth for the idle release: if the shared recognizer was freed since this
     * surface last initialized (e.g. the keyboard sat open past the idle cutoff and no IME
     * re-bind re-warmed it), re-warm it here instead of failing the dictation — a 10-30s
     * masked model load is always better than returning ModelUnavailable for speech the user
     * already produced. Only attempted when the model files are actually present.
     */
    override suspend fun transcribe(
        samples: FloatArray,
        sampleRate: Int,
    ): TranscriptionOutcome {
        val activeRecognizer =
            readyRecognizer()
                ?: rewarmedRecognizer()
                ?: return TranscriptionOutcome.ModelUnavailable("Recognizer is not initialized")
        return RecognizerManager.withUsageLease {
            activeRecognizer.transcribeOutcome(samples, sampleRate)
        }
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
     * only be reached from an explicit user "free model memory" / delete-model intent — never from
     * a single surface's start/teardown, which would force the next keyboard dictation to
     * cold-reload the model. The OS-pressure and idle-timeout paths do NOT come through here; they
     * use the gated `RecognizerManager.releaseIfUnused` via `RecognizerIdleReleasePolicy`.
     */
    override suspend fun release() {
        recognizer = null
        RecognizerManager.releaseRecognizer()
    }

}
