package dev.chirpboard.app.feature.recording.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.feature.recording.service.RecordingService
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel for the full-screen RecordScreen.
 *
 * Manages recording state and amplitude data for the recording interface.
 */
@HiltViewModel
class RecordViewModel
    @Inject
    constructor(

        private val recordingStateManager: RecordingStateManager,
    ) : ViewModel() {
        /** Current recording state */
        val recordingState: StateFlow<RecordingState> = recordingStateManager.state

        /** Buffer of amplitude samples for waveform display */
        val waveformBuffer = recordingStateManager.waveformBuffer
        
        /** Monotonic waveform sample count for smooth scrolling */
        val amplitudeSampleCount: StateFlow<Long> = recordingStateManager.amplitudeSampleCountFlow
        
        /** Current audio amplitude (0-1) */
        val currentAmplitude: StateFlow<Float> = recordingStateManager.amplitudeFlow

        /** ID of the last recording that completed successfully */
        val lastCompletedRecordingId: StateFlow<UUID?> = recordingStateManager.lastCompletedRecordingId

        /**
         * Start a new recording.
         *
         * @param profileId Optional profile ID for recording settings
         */
        fun startRecording(context: Context, profileId: UUID? = null) {
            RecordingService.startRecording(
                context = context,
                origin = RecordingOrigin.APP,
                profileId = profileId,
            )
        }

        /**
         * Pause the current recording.
         */
        fun pauseRecording(context: Context) {
            RecordingService.pauseRecording(context)
        }

        /**
         * Resume a paused recording.
         */
        fun resumeRecording(context: Context) {
            RecordingService.resumeRecording(context)
        }

        /**
         * Stop the current recording and save it.
         */
        fun stopRecording(context: Context) {
            RecordingService.stopRecording(context)
        }

        /**
         * Clear the last completed recording ID after navigation has been handled.
         */
        fun clearLastCompletedRecordingId() {
            recordingStateManager.clearLastCompletedRecordingId()
        }

        /**
         * Cancel the current recording without saving.
         * Releases MediaRecorder, deletes the audio file, no database entry.
         */
        fun cancelRecording(context: Context) {
            RecordingService.cancelRecording(context)
        }

        /**
         * Atomic restart: cancel current recording and immediately start a new one.
         * Handles cleanup and re-start within a single service call to avoid race conditions.
         */
        fun restartRecording(context: Context, profileId: UUID? = null) {
            RecordingService.restartRecording(
                context = context,
                origin = RecordingOrigin.APP,
                profileId = profileId,
            )
        }
    }
