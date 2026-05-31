package dev.chirpboard.app.feature.recording.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.data.repository.TagRepository
import dev.chirpboard.app.data.repository.unwrapRepositoryFlow
import dev.chirpboard.app.feature.recording.RecordingManager
import dev.chirpboard.app.feature.recording.session.RecordingRecoveryStore
import dev.chirpboard.app.feature.recording.session.SessionRecoveryResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
        private val recordingManager: RecordingManager,
        private val recordingStateManager: RecordingStateManager,
        private val profileRepository: ProfileRepository,
        private val tagRepository: TagRepository,
        private val recoveryStore: RecordingRecoveryStore,
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

        val recoverableSessions = recoveryStore.actionablePendingSessions

        val availableTags: StateFlow<List<Tag>> =
            tagRepository
                .getAllTags()
                .unwrapRepositoryFlow { _entryMessage.value = it }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        private val _selectedTagIds = MutableStateFlow<Set<UUID>>(emptySet())
        val selectedTagIds: StateFlow<Set<UUID>> = _selectedTagIds.asStateFlow()

        private var tagsInitializedForRecordingId: UUID? = null

        init {
            viewModelScope.launch {
                recoveryStore.refresh()
            }
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

            viewModelScope.launch {
                recordingState.collect { state ->
                    val recordingId = state.activeRecordingId
                    if (recordingId != null && recordingId != tagsInitializedForRecordingId) {
                        tagsInitializedForRecordingId = recordingId
                        initializeTagsForRecording(
                            recordingId = recordingId,
                        )
                    } else if (recordingId == null && state is RecordingState.Idle) {
                        tagsInitializedForRecordingId = null
                        _selectedTagIds.value = emptySet()
                    }
                }
            }
        }

        /** Start a new recording with the active session profile, if one was resolved. */
        fun startRecording() {
            startRecording(activeProfile.value?.id)
        }

        internal fun startRecording(profileId: UUID?) {
            recordingManager.startRecording(
                origin = RecordingOrigin.APP,
                profileId = profileId,
            )
        }

        /** Pause the current recording. */
        fun pauseRecording() {
            recordingManager.pauseRecording()
        }

        /** Resume a paused recording. */
        fun resumeRecording() {
            recordingManager.resumeRecording()
        }

        /** Stop the current recording and return the in-progress ID for immediate studio handoff. */
        fun stopRecordingWithHandoff(): UUID? {
            val recordingId = recordingState.value.activeRecordingId ?: return null
            recordingManager.stopRecording()
            return recordingId
        }

        fun canHandoffToStudio(): Boolean = recordingState.value.activeRecordingId != null

        /** Stop the current recording and save it. */
        fun stopRecording() {
            recordingManager.stopRecording()
        }

        /** Clear the last completed recording ID after navigation has been handled. */
        fun clearLastCompletedRecordingId() {
            recordingStateManager.clearLastCompletedRecordingId()
        }

        /** Cancel the current recording without saving. */
        fun cancelRecording() {
            recordingManager.cancelRecording()
        }

        /** Restart the current recording with the active session profile, if one was resolved. */
        fun restartRecording() {
            restartRecording(activeProfile.value?.id)
        }

        internal fun restartRecording(profileId: UUID?) {
            recordingManager.restartRecording(
                origin = RecordingOrigin.APP,
                profileId = profileId,
            )
        }

        fun clearEntryMessage() {
            _entryMessage.value = null
        }

        fun toggleTag(tagId: UUID) {
            val recordingId = recordingState.value.activeRecordingId ?: return
            viewModelScope.launch {
                if (tagId in _selectedTagIds.value) {
                    tagRepository.removeTagFromRecording(recordingId, tagId)
                    _selectedTagIds.update { it - tagId }
                } else {
                    tagRepository.addTagToRecording(recordingId, tagId)
                    _selectedTagIds.update { it + tagId }
                }
            }
        }

        fun createTagForRecording(name: String) {
            val recordingId = recordingState.value.activeRecordingId ?: return
            viewModelScope.launch {
                val tag = tagRepository.createTag(name.trim())
                tagRepository.addTagToRecording(recordingId, tag.id)
                _selectedTagIds.update { it + tag.id }
            }
        }

        fun recoverInterruptedSession(sessionId: UUID) {
            viewModelScope.launch {
                when (val result = recoveryStore.recoverSession(sessionId)) {
                    is SessionRecoveryResult.Recovered -> {
                        _entryMessage.value =
                            result.estimatedLostMinutes?.let { lostMinutes ->
                                "Recording recovered. Up to $lostMinutes minute(s) of recent audio may be missing."
                            } ?: "Recording recovered."
                    }
                    is SessionRecoveryResult.Failed -> {
                        _entryMessage.value = result.message
                    }
                    else -> Unit
                }
            }
        }

        fun discardInterruptedSession(sessionId: UUID) {
            viewModelScope.launch {
                recoveryStore.discardSession(sessionId)
            }
        }

        fun keepInterruptedSession(sessionId: UUID) {
            viewModelScope.launch {
                recoveryStore.keepSession(sessionId)
            }
        }

        fun deferInterruptedSession(sessionId: UUID) {
            recoveryStore.deferSession(sessionId)
        }

        private suspend fun initializeTagsForRecording(
            recordingId: UUID,
        ) {
            val existing = tagRepository.getTagsForRecordingList(recordingId)
            _selectedTagIds.value = existing.map { it.id }.toSet()
        }
    }
