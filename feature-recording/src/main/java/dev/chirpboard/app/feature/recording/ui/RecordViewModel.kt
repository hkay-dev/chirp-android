package dev.chirpboard.app.feature.recording.ui

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.feature.recording.service.RecordingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@androidx.compose.runtime.Stable
data class ActiveRecordingProfile(
    val id: UUID,
    val name: String,
    val icon: String? = null,
 )

/**
 * ViewModel for the full-screen RecordScreen.
 *
 * Manages recording state, profile handoff, and amplitude data for the recording interface.
 */
@HiltViewModel
class RecordViewModel
    @Inject
    constructor(
        private val recordingStateManager: RecordingStateManager,
        private val profileRepository: ProfileRepository,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val requestedProfileId: UUID? =
            savedStateHandle
                .get<String>("profileId")
                ?.takeIf(String::isNotBlank)
                ?.let(UUID::fromString)

        private val _activeProfile = MutableStateFlow<ActiveRecordingProfile?>(null)
        val activeProfile: StateFlow<ActiveRecordingProfile?> = _activeProfile.asStateFlow()

        private val _isProfileHandoffResolved = MutableStateFlow(requestedProfileId == null)
        val isProfileHandoffResolved: StateFlow<Boolean> = _isProfileHandoffResolved.asStateFlow()

        private val _entryMessage = MutableStateFlow<String?>(null)
        val entryMessage: StateFlow<String?> = _entryMessage.asStateFlow()

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

        init {
            if (requestedProfileId != null) {
                viewModelScope.launch {
                    val profile = profileRepository.getProfile(requestedProfileId)
                    _activeProfile.value =
                        profile?.let {
                            ActiveRecordingProfile(
                                id = it.id,
                                name = it.name,
                                icon = it.icon,
                            )
                        }
                    if (profile == null) {
                        _entryMessage.value = "Profile no longer exists. Using default recording settings."
                    }
                    _isProfileHandoffResolved.value = true
                }
            }
        }

        /** Start a new recording with the active session profile, if one was resolved. */
        fun startRecording(context: Context) {
            startRecording(context, activeProfile.value?.id)
        }

        internal fun startRecording(context: Context, profileId: UUID?) {
            RecordingService.startRecording(
                context = context,
                origin = RecordingOrigin.APP,
                profileId = profileId,
            )
        }

        /** Pause the current recording. */
        fun pauseRecording(context: Context) {
            RecordingService.pauseRecording(context)
        }

        /** Resume a paused recording. */
        fun resumeRecording(context: Context) {
            RecordingService.resumeRecording(context)
        }

        /** Stop the current recording and save it. */
        fun stopRecording(context: Context) {
            RecordingService.stopRecording(context)
        }

        /** Clear the last completed recording ID after navigation has been handled. */
        fun clearLastCompletedRecordingId() {
            recordingStateManager.clearLastCompletedRecordingId()
        }

        /** Cancel the current recording without saving. */
        fun cancelRecording(context: Context) {
            RecordingService.cancelRecording(context)
        }

        /** Restart the current recording with the active session profile, if one was resolved. */
        fun restartRecording(context: Context) {
            restartRecording(context, activeProfile.value?.id)
        }

        internal fun restartRecording(context: Context, profileId: UUID?) {
            RecordingService.restartRecording(
                context = context,
                origin = RecordingOrigin.APP,
                profileId = profileId,
            )
        }

        fun clearEntryMessage() {
            _entryMessage.value = null
        }
    }