package dev.chirpboard.app.core.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.chirpboard.app.core.playback.RecordingPlaybackController

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RecordingPlaybackEntryPoint {
    fun recordingPlaybackController(): RecordingPlaybackController
}
