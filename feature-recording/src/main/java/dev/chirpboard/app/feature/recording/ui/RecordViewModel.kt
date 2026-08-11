package dev.chirpboard.app.feature.recording.ui

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.feature.recording.R
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.TagRepository
import dev.chirpboard.app.data.repository.unwrapRepositoryFlow
import dev.chirpboard.app.feature.recording.RecordingManager
import dev.chirpboard.app.feature.recording.service.RecordingAutoStopEvent
import dev.chirpboard.app.feature.recording.service.RecordingServiceEvents
import dev.chirpboard.app.feature.recording.session.RecordingRecoveryStore
import dev.chirpboard.app.feature.recording.session.SessionRecoveryResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
        // I18N-08: snackbar/banner copy comes from resources.
        @ApplicationContext private val appContext: Context,
        private val recordingManager: RecordingManager,
        private val recordingStateManager: RecordingStateManager,
        private val profileRepository: ProfileRepository,
        private val tagRepository: TagRepository,
        private val recordingRepository: RecordingRepository,
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

            /** In-progress note draft, mirrored so rotation/process death never lose typed text. */
            const val KEY_NOTE_DRAFT = "noteDraft"

            /**
             * Saved-state Bundles share a ~1MB binder budget; a pathologically long note is not
             * mirrored (the debounced DB write-through still preserves it) rather than risking a
             * TransactionTooLargeException on every lifecycle save.
             */
            const val MAX_SAVED_NOTE_CHARS = 20_000

            /**
             * Debounce for the live note write-through. Short enough that Browse Home / an
             * unexpected stop rarely outruns it, long enough not to write on every keystroke.
             */
            const val NOTE_FLUSH_DEBOUNCE_MS = 600L
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

        /**
         * AUD-02/AUD-05/ERR-14/MIC-010: live-session advisory (focus pause, device change on
         * resume, silenced mic, low storage)
         * rendered as an inline banner on the record screen — the in-app twin of the
         * notification's transient status line. Session-scoped: the service clears the
         * underlying flags when the session ends.
         */
        val sessionAdvisory: StateFlow<RecordingSessionAdvisory?> =
            combine(
                serviceEvents.autoPauseReason,
                serviceEvents.silenceDetected,
                serviceEvents.storageLow,
                serviceEvents.deviceChangedOnResume,
            ) { autoPauseReason, silenceDetected, storageLow, deviceChange ->
                resolveSessionAdvisory(
                    autoPauseReason = autoPauseReason,
                    silenceDetected = silenceDetected,
                    storageLow = storageLow,
                    deviceChangedOnResume = deviceChange != null,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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

        /**
         * Live note draft for the active recording session ("describe it while you make it").
         * Restored from SavedStateHandle so rotation/process death keep the typed text, and
         * written through to the recording row with a short debounce so Browse Home + return
         * (a brand-new ViewModel) and every stop path see the note without touching the
         * stop/finalize pipeline.
         */
        private val _noteDraft = MutableStateFlow(savedStateHandle.get<String>(KEY_NOTE_DRAFT).orEmpty())
        val noteDraft: StateFlow<String> = _noteDraft.asStateFlow()

        /** Row the current note draft belongs to; cleared when the session ends or is discarded. */
        private var noteRecordingId: UUID? = null
        private var noteFlushJob: Job? = null

        /**
         * True while the draft holds a user edit that has not been confirmed on the recording
         * row. Set on every [updateNoteDraft], cleared only after a persist succeeds with the
         * draft unchanged (or when the draft is explicitly discarded/flushed detached). This is
         * deliberately NOT derived from [noteFlushJob]'s liveness: androidx cancels
         * [viewModelScope] (and the pending flush with it) BEFORE [onCleared] runs, so job
         * liveness is always false by the time the rescue path needs the answer.
         */
        private var noteDraftDirty = false

        init {
            // lastCompletedRecordingId is process-wide state that only this screen consumes. A
            // completion published while no Record screen existed (recording stopped from the
            // notification after navigating away, a shortcut session, a handoff) would otherwise
            // sit in the flow and bounce the next Record entry straight into that old recording's
            // Studio. A fresh ViewModel means a fresh screen entry, so drop anything stale first;
            // completions from this session are published after this and still navigate.
            recordingStateManager.clearLastCompletedRecordingId()
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
                        _entryMessage.value = appContext.getString(R.string.rec_msg_profile_missing)
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
                        initializeNoteForRecording(recordingId)
                    } else if (recordingId == null && state is RecordingState.Idle) {
                        tagsInitializedForRecordingId = null
                        _selectedTagIds.value = emptySet()
                        finishNoteSession()
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
            flushNoteDraftForStop(recordingId)
            viewModelScope.launch {
                recordingManager.stopRecording()
            }
            return recordingId
        }

        fun canHandoffToStudio(): Boolean = recordingState.value.activeRecordingId != null

        /** Stop the current recording and save it. */
        fun stopRecording() {
            recordingState.value.activeRecordingId?.let(::flushNoteDraftForStop)
            viewModelScope.launch {
                recordingManager.stopRecording()
            }
        }

        /** Clear the last completed recording ID after navigation has been handled. */
        fun clearLastCompletedRecordingId() {
            recordingStateManager.clearLastCompletedRecordingId()
        }

        /** Cancel the current recording without saving. A discard also drops the note draft. */
        fun cancelRecording() {
            discardNoteDraft()
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
                _entryMessage.value = appContext.getString(R.string.rec_msg_stop_in_progress)
                return
            }
            // Start over discards the current capture, and the note draft with it; the fresh
            // session starts with a clean note (mirroring the tag reset on session change).
            discardNoteDraft()
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
                    _entryMessage.value = appContext.getString(R.string.rec_msg_tag_update_failed)
                }
            }
        }

        fun createTagForRecording(
            name: String,
            color: String? = null,
        ) {
            val recordingId = recordingState.value.activeRecordingId ?: return
            viewModelScope.launch {
                try {
                    val tag = tagRepository.createTag(name.trim(), color)
                    tagRepository.addTagToRecording(recordingId, tag.id)
                    _selectedTagIds.update { it + tag.id }
                } catch (e: SQLiteException) {
                    Log.e(TAG, "Tag creation failed for recording $recordingId", e)
                    _entryMessage.value = appContext.getString(R.string.rec_msg_tag_add_failed)
                }
            }
        }

        /**
         * Updates the live note draft. The text lands in three places, in increasing durability:
         * the StateFlow (UI), SavedStateHandle (rotation/process death), and — debounced — the
         * recording row itself, so the draft survives Browse Home + return and is already on the
         * row for every stop path. The write touches only the notes column; the stop/finalize
         * pipeline is never involved.
         */
        fun updateNoteDraft(text: String) {
            setNoteDraftState(text)
            val recordingId = recordingState.value.activeRecordingId ?: return
            noteRecordingId = recordingId
            noteDraftDirty = true
            noteFlushJob?.cancel()
            noteFlushJob =
                viewModelScope.launch {
                    delay(NOTE_FLUSH_DEBOUNCE_MS)
                    persistNote(recordingId, _noteDraft.value)
                }
        }

        private fun setNoteDraftState(text: String) {
            _noteDraft.value = text
            savedStateHandle[KEY_NOTE_DRAFT] = text.takeIf { it.length <= MAX_SAVED_NOTE_CHARS }
        }

        /**
         * Adopts a note already persisted on the row (Browse Home + return creates a fresh
         * ViewModel; keep-session recovery resumes an older row). A live in-memory draft wins
         * over the persisted copy — it is never older.
         */
        private suspend fun initializeNoteForRecording(recordingId: UUID) {
            noteRecordingId = recordingId
            val persisted = recordingRepository.getNotes(recordingId)
            if (!persisted.isNullOrBlank() && _noteDraft.value.isBlank()) {
                setNoteDraftState(persisted)
            }
        }

        /**
         * Session ended through any path (user stop, auto-stop, notification stop): flush the
         * draft onto the row one final time, then reset for the next session. After a discard
         * the row is gone, so the flush is a harmless no-op UPDATE.
         */
        private fun finishNoteSession() {
            val recordingId = noteRecordingId ?: return
            noteRecordingId = null
            flushNoteDraftIfMeaningful(recordingId)
            setNoteDraftState("")
        }

        /** Explicit discard (cancel / start over): drop the draft without persisting it. */
        private fun discardNoteDraft() {
            noteFlushJob?.cancel()
            noteRecordingId = null
            noteDraftDirty = false
            setNoteDraftState("")
        }

        /**
         * Stop requested: persist the draft immediately and non-cancellably. This is a separate
         * post-persist step on the already-existing row — deliberately decoupled from the
         * stop/finalize reliability pipeline, which only ever touches its own columns.
         */
        private fun flushNoteDraftForStop(recordingId: UUID) {
            noteRecordingId = recordingId
            flushNoteDraftIfMeaningful(recordingId)
        }

        /**
         * Persists the draft unless it is blank with no edit pending. The blank+untouched case
         * is skipped both to avoid a pointless write on every stop and because a blank draft
         * that merely has not HYDRATED yet (stop racing [initializeNoteForRecording]) must never
         * wipe a note already on the row. A blank draft from an actual user clear leaves
         * [noteDraftDirty] set until the clear is confirmed persisted, so the clear still lands.
         * The dirty flag is reset here because the detached persist is NonCancellable — it
         * completes even if the ViewModel is cleared right after — and a stale flag must not
         * let a later [onCleared] wipe a not-yet-hydrated row with a blank draft.
         */
        private fun flushNoteDraftIfMeaningful(recordingId: UUID) {
            val hasPendingEdit = noteDraftDirty
            noteFlushJob?.cancel()
            noteDraftDirty = false
            val draft = _noteDraft.value
            if (draft.isNotBlank() || hasPendingEdit) {
                persistNoteDetached(recordingId, draft)
            }
        }

        /**
         * Persists the note on a NonCancellable job so neither navigation (ViewModel clear) nor
         * the in-flight stop can drop a captured description.
         */
        private fun persistNoteDetached(
            recordingId: UUID,
            draft: String,
        ) {
            viewModelScope.launch(NonCancellable) {
                persistNote(recordingId, draft)
            }
        }

        private suspend fun persistNote(
            recordingId: UUID,
            draft: String,
        ) {
            try {
                recordingRepository.updateNotes(recordingId, draft)
                // Only confirm the exact draft that was written; an edit made while the write
                // was in flight must keep the dirty flag for the next flush/rescue.
                if (_noteDraft.value == draft) {
                    noteDraftDirty = false
                }
            } catch (e: SQLiteException) {
                // ERR-18-style guard: a full disk or a row deleted mid-write must not crash
                // the bare launch; surface it like the tag failures.
                Log.e(TAG, "Note save failed for recording $recordingId", e)
                _entryMessage.value = appContext.getString(R.string.rec_msg_note_save_failed)
            }
        }

        override fun onCleared() {
            // Browse Home (or any navigation) inside the debounce window: androidx cancels
            // viewModelScope — killing the pending debounced flush — BEFORE this runs, so the
            // flush job can never be observed alive here. The dirty flag is the durable record
            // of an unconfirmed edit; persist it detached ([persistNoteDetached] parents the
            // write to NonCancellable, not the dead scope, so it still executes).
            val recordingId = noteRecordingId
            if (recordingId != null && noteDraftDirty) {
                noteDraftDirty = false
                persistNoteDetached(recordingId, _noteDraft.value)
            }
            super.onCleared()
        }

        fun recoverInterruptedSession(sessionId: UUID) {
            viewModelScope.launch {
                when (val result = recoveryStore.recoverSession(sessionId)) {
                    is SessionRecoveryResult.Recovered -> {
                        _entryMessage.value =
                            result.estimatedLostMinutes?.let { lostMinutes ->
                                appContext.resources.getQuantityString(
                                    R.plurals.rec_msg_recovered_with_loss,
                                    lostMinutes,
                                    lostMinutes,
                                )
                            } ?: appContext.getString(R.string.rec_msg_recovered)
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
