package dev.chirpboard.app.feature.recording.ui

import android.database.sqlite.SQLiteException
import android.util.Log
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
import dev.chirpboard.app.feature.recording.service.RecordingAutoStopEvent
import dev.chirpboard.app.feature.recording.service.RecordingServiceEvents
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
        private val serviceEvents: RecordingServiceEvents,
        private val savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private companion object {
            const val TAG = "RecordViewModel"

            /**
             * LIF-02: latches once the screen's autoStart nav-argument has been acted on, in
             * SavedStateHandle so a process-death restoration of the back stack can never
             * re-fire an unattended microphone start.
             */
            const val KEY_AUTO_START_CONSUMED = "autoStartConsumed"
        }

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

        /**
         * ERR-13/ERR-14: why the service ended a recording on its own (storage critical,
         * permanent focus loss, mic disconnect, capture death). Auto-stops finish through
         * the normal save path, so they never appear as [RecordingState.Error]; this is
         * the only in-app channel for the reason. Surfaced as a snackbar by the screen,
         * which acknowledges it via [consumeAutoStopEvent].
         */
        val autoStopEvent: StateFlow<RecordingAutoStopEvent?> = serviceEvents.autoStopEvent

        /** Acknowledges a surfaced auto-stop so no screen shows it again. */
        fun consumeAutoStopEvent() {
            serviceEvents.clearAutoStopEvent()
        }

        /** Buffer of amplitude samples for waveform display */
        val waveformBuffer = recordingStateManager.waveformBuffer

        /** Monotonic waveform sample count for smooth scrolling */
        val amplitudeSampleCount: StateFlow<Long> = recordingStateManager.amplitudeSampleCountFlow

        /** Current audio amplitude (0-1) */
        val currentAmplitude: StateFlow<Float> = recordingStateManager.amplitudeFlow

        /** ID of the last recording that completed successfully */
        val lastCompletedRecordingId: StateFlow<UUID?> = recordingStateManager.lastCompletedRecordingId

        val recoverableSessions = recoveryStore.actionablePendingSessions

        /**
         * LIF-02: [recoverableSessions] starts as an empty list until the async refresh in init
         * completes, so an auto-start decision taken before this flag flips could race ahead of a
         * pending recovery prompt. The screen gates auto-start on it.
         */
        private val _isRecoverableSessionsRefreshed = MutableStateFlow(false)
        val isRecoverableSessionsRefreshed: StateFlow<Boolean> = _isRecoverableSessionsRefreshed.asStateFlow()

        /** LIF-02: true once this screen entry's autoStart argument has been acted on. */
        val isAutoStartConsumed: StateFlow<Boolean> =
            savedStateHandle.getStateFlow(KEY_AUTO_START_CONSUMED, false)

        /** LIF-02: record the auto-start decision so restoration never repeats it. */
        fun consumeAutoStart() {
            savedStateHandle[KEY_AUTO_START_CONSUMED] = true
        }

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
                try {
                    recoveryStore.refresh()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A failed refresh must not block the screen (or crash the bare launch);
                    // the recovery banner simply stays absent until the next refresh.
                    Log.e(TAG, "Recovery store refresh failed", e)
                } finally {
                    _isRecoverableSessionsRefreshed.value = true
                }
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
            val current = recordingState.value
            if (current.activeOrigin != RecordingOrigin.APP) {
                return null
            }
            val recordingId = current.activeRecordingId ?: return null
            viewModelScope.launch {
                recordingManager.stopRecording()
            }
            return recordingId
        }

        fun canHandoffToStudio(): Boolean = recordingState.value.activeRecordingId != null

        /** Stop the current recording and save it. */
        fun stopRecording() {
            viewModelScope.launch {
                recordingManager.stopRecording()
            }
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
            if (recordingState.value is RecordingState.Stopping) {
                // An in-flight stop owns the shared stop gate, so the service would
                // refuse this restart with only a silent low-importance notification.
                // Surface the refusal in-screen instead of dispatching a doomed command.
                _entryMessage.value =
                    "Recording is already being saved. Start over isn't available right now."
                return
            }
            recordingManager.restartRecording(
                origin = RecordingOrigin.APP,
                profileId = profileId,
            )
        }

        fun clearEntryMessage() {
            _entryMessage.value = null
        }

        /**
         * Clear a surfaced [RecordingState.Error] after the screen has shown it (ERR-7/ERR-13:
         * the record screen now renders service-reported stop/failure reasons via snackbar).
         */
        fun clearRecordingError() {
            recordingManager.clearError()
        }

        fun toggleTag(tagId: UUID) {
            val recordingId = recordingState.value.activeRecordingId ?: return
            viewModelScope.launch {
                // OR IGNORE does not cover FOREIGN KEY violations: if the recording row
                // vanished between the UI action and the insert (auto-stop discard,
                // rescue-path delete, finalize race) the insert throws
                // SQLiteConstraintException, which would crash the process from this
                // bare launch. Surface it instead.
                try {
                    if (tagId in _selectedTagIds.value) {
                        tagRepository.removeTagFromRecording(recordingId, tagId)
                        _selectedTagIds.update { it - tagId }
                    } else {
                        tagRepository.addTagToRecording(recordingId, tagId)
                        _selectedTagIds.update { it + tagId }
                    }
                } catch (e: SQLiteException) {
                    Log.e(TAG, "Tag toggle failed for recording $recordingId", e)
                    _entryMessage.value = "Couldn't update tags. The recording may no longer exist."
                }
            }
        }

        fun createTagForRecording(name: String) {
            val recordingId = recordingState.value.activeRecordingId ?: return
            viewModelScope.launch {
                try {
                    val tag = tagRepository.createTag(name.trim())
                    tagRepository.addTagToRecording(recordingId, tag.id)
                    _selectedTagIds.update { it + tag.id }
                } catch (e: SQLiteException) {
                    Log.e(TAG, "Tag creation failed for recording $recordingId", e)
                    _entryMessage.value = "Couldn't add the tag. The recording may no longer exist."
                }
            }
        }

        fun recoverInterruptedSession(sessionId: UUID) {
            viewModelScope.launch {
                when (val result = recoveryStore.recoverSession(sessionId)) {
                    is SessionRecoveryResult.Recovered -> {
                        _entryMessage.value =
                            result.estimatedLostMinutes?.let { lostMinutes ->
                                val unit = if (lostMinutes == 1) "minute" else "minutes"
                                "Recording recovered. Up to $lostMinutes $unit of recent audio may be missing."
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
                val result = recoveryStore.discardSession(sessionId)
                if (result is SessionRecoveryResult.Failed) {
                    // Surface the refusal (e.g. the finalize worker still owns the
                    // session) instead of letting the card silently disappear.
                    _entryMessage.value = result.message
                }
            }
        }

        fun keepInterruptedSession(sessionId: UUID) {
            viewModelScope.launch {
                val result = recoveryStore.keepSession(sessionId)
                if (result is SessionRecoveryResult.Failed) {
                    _entryMessage.value = result.message
                }
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
