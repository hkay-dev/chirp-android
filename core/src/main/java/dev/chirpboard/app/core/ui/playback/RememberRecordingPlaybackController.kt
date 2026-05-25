package dev.chirpboard.app.core.ui.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.android.EntryPointAccessors
import dev.chirpboard.app.core.audio.RecordingPlaybackController
import dev.chirpboard.app.core.di.RecordingPlaybackEntryPoint

@Composable
fun rememberRecordingPlaybackController(): RecordingPlaybackController {
    val context = LocalContext.current.applicationContext
    return remember {
        EntryPointAccessors.fromApplication(context, RecordingPlaybackEntryPoint::class.java)
            .recordingPlaybackController()
    }
}
