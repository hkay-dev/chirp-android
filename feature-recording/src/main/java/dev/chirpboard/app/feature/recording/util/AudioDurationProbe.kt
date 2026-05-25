package dev.chirpboard.app.feature.recording.util

import android.media.MediaMetadataRetriever
import java.io.File

fun probeDurationMs(file: File): Long =
    runCatching {
        MediaMetadataRetriever().useCompat { retriever ->
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }
    }.getOrNull()?.coerceAtLeast(0L) ?: 0L

fun probeDurationUs(file: File): Long = probeDurationMs(file) * 1_000L
