package dev.chirpboard.app.feature.recording

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.core.recording.KeyboardPendingStopStore
import dev.chirpboard.app.core.recording.KeyboardRecordingStopBridge
import dev.chirpboard.app.core.recording.RecordingActiveStopCommands
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
        private val keyboardStopBridge: KeyboardRecordingStopBridge,
        private val pendingStopStore: KeyboardPendingStopStore,
    ) {
        /** Current recording state */
        val state: StateFlow<RecordingState> = stateManager.state

        /** Whether a recording can be started right now */
        val canStartRecording: Boolean
            get() = stateManager.canStartRecording()

        /** True when the main app holds an active capture session that can be resumed on RecordScreen. */
        val hasActiveAppCapture: Boolean
            get() =
                when (val current = state.value) {
                    is RecordingState.Starting,
                    is RecordingState.Recording,
                    is RecordingState.Paused,
                    -> current.activeOrigin == RecordingOrigin.APP

                    else -> false
                }

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
        suspend fun stopRecording(onKeyboardStopQueued: (() -> Unit)? = null) {
            RecordingActiveStopCommands.stopActiveRecording(
                context = context,
                recordingStateManager = stateManager,
                keyboardStopBridge = keyboardStopBridge,
                pendingStopStore = pendingStopStore,
                requesterOrigin = RecordingOrigin.APP,
                onKeyboardStopQueued = onKeyboardStopQueued,
            )
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
        suspend fun toggleRecording(
            origin: RecordingOrigin = RecordingOrigin.APP,
            profileId: UUID? = null,
            onKeyboardStopQueued: (() -> Unit)? = null,
        ): ToggleResult =
            if (state.value.isActive) {
                stopRecording(onKeyboardStopQueued)
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
