package dev.chirpboard.app.di

import android.content.Context
import dev.chirpboard.app.StreamingSherpaRecognizerProvider
import dev.chirpboard.app.core.transcription.StreamingTranscriberProvider
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.download.ModelDownloader

internal object BuildTranscriberFactory {
    fun create(context: Context, downloader: ModelDownloader): TranscriberProvider =
        SherpaRecognizerProvider(context, downloader)

    fun createStreaming(context: Context): StreamingTranscriberProvider =
        StreamingSherpaRecognizerProvider(context)
}
