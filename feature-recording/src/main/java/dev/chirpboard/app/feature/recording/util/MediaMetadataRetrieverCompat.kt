package dev.chirpboard.app.feature.recording.util

import android.media.MediaMetadataRetriever

inline fun <T> MediaMetadataRetriever.useCompat(block: (MediaMetadataRetriever) -> T): T {
    return try {
        block(this)
    } finally {
        release()
    }
}
