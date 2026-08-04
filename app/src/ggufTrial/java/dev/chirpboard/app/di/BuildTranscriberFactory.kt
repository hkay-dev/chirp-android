package dev.chirpboard.app.di

import android.content.Context
import dev.chirpboard.app.GgufTrialRecognizerProvider
import dev.chirpboard.app.core.transcription.StreamingTranscriberProvider
import dev.chirpboard.app.core.transcription.StreamingTranscriptionSession
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.download.ModelDownloader

internal object BuildTranscriberFactory {
    fun create(context: Context, downloader: ModelDownloader): TranscriberProvider =
        GgufTrialRecognizerProvider(context, downloader)

    fun createStreaming(@Suppress("UNUSED_PARAMETER") context: Context): StreamingTranscriberProvider =
        object : StreamingTranscriberProvider {
            override suspend fun prepare(): Boolean = false

            override suspend fun openSession(sampleRate: Int): StreamingTranscriptionSession? = null
        }
}
