package dev.chirpboard.app.feature.recording

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingServiceCommands
import dev.chirpboard.app.core.recording.RecordingStartResult
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level API for managing recordings.
 *
 * Use this from ViewModels instead of interacting with RecordingService directly.
 */
@Singleton
class RecordingManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val stateManager: RecordingStateManager,
    ) {
        /** Current recording state */
        val state: StateFlow<RecordingState> = stateManager.state

        /** Whether a recording can be started right now */
        val canStartRecording: Boolean
            get() = stateManager.canStartRecording()

        /** Current recording duration in milliseconds */
        val currentDurationMs: Long
            get() = stateManager.getCurrentDurationMs()

        /**
         * Start a new recording.
         *
         * @param origin Where the recording is being started from
         * @param profileId Optional profile to use for recording settings
         * @return Result indicating success or why recording couldn't start
         */
        fun startRecording(
            origin: RecordingOrigin = RecordingOrigin.APP,
            profileId: UUID? = null,
        ): RecordingStartResult {
            // Pre-check before starting service (for immediate UI feedback)
            if (!canStartRecording) {
                val currentOrigin = state.value.activeOrigin ?: RecordingOrigin.APP
                return RecordingStartResult.AlreadyRecording(currentOrigin)
            }

            // Start the service (it will do the actual atomic check)
            RecordingServiceCommands.startRecording(context, origin, profileId)
            return RecordingStartResult.Success
        }

        /**
         * Stop the current recording.
         */
        fun stopRecording() {
            RecordingServiceCommands.stopRecording(context)
        }

        fun pauseRecording() {
            RecordingServiceCommands.pauseRecording(context)
        }

        fun resumeRecording() {
            RecordingServiceCommands.resumeRecording(context)
        }

        fun cancelRecording() {
            RecordingServiceCommands.cancelRecording(context)
        }

        fun restartRecording(
            origin: RecordingOrigin = RecordingOrigin.APP,
            profileId: UUID? = null,
        ) {
            RecordingServiceCommands.restartRecording(context, origin, profileId)
        }

        /**
         * Toggle recording on/off.
         *
         * @param origin Where the recording is being toggled from
         * @param profileId Optional profile to use if starting
         * @return Result if starting or stopped
         */
        fun toggleRecording(
            origin: RecordingOrigin = RecordingOrigin.APP,
            profileId: UUID? = null,
        ): ToggleResult =
            if (state.value.isActive) {
                stopRecording()
                ToggleResult.Stopped
            } else {
                ToggleResult.Started(startRecording(origin, profileId))
            }

        /**
         * Clear any error state.
         */
        fun clearError() {
            stateManager.clearError()
        }
    }

sealed class ToggleResult {
    data class Started(
        val startResult: RecordingStartResult,
    ) : ToggleResult()

    object Stopped : ToggleResult()
}
