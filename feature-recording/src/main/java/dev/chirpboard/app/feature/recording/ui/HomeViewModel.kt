package dev.chirpboard.app.feature.recording.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.core.ui.R as CoreUiR
import dev.chirpboard.app.feature.recording.R
import dev.chirpboard.app.core.audio.RecordingOutputFormat
import dev.chirpboard.app.core.di.DefaultDispatcher
import dev.chirpboard.app.core.llm.GOOGLE_CLOUD_VERTEX_PROVIDER_ID
import dev.chirpboard.app.core.llm.RecordingTextEnhancementContext
import dev.chirpboard.app.core.llm.RecordingTextEnhancementPort
import dev.chirpboard.app.core.playback.RecordingPlaybackController
import dev.chirpboard.app.core.playback.RecordingPlaybackState
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingStartResult
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.transcription.ManualRecoveryResult
import dev.chirpboard.app.core.transcription.TranscriptionEngine
import dev.chirpboard.app.core.transcription.TranscriptionRecovery
import dev.chirpboard.app.core.transcription.toUserMessage
import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.model.RecordingLibraryStats
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.model.TranscriptPreview
import dev.chirpboard.app.data.dao.RecordingDao
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.TagRepository
import dev.chirpboard.app.data.repository.unwrapRepositoryFlow
import dev.chirpboard.app.feature.recording.RecordingManager
import dev.chirpboard.app.feature.recording.importing.AudioImportOrchestrator
import dev.chirpboard.app.feature.recording.importing.AudioImportResult
import dev.chirpboard.app.feature.recording.service.RecordingAutoStopEvent
import dev.chirpboard.app.feature.recording.service.RecordingServiceEvents
import dev.chirpboard.app.feature.recording.session.RecoverableRecordingSession
import dev.chirpboard.app.feature.recording.session.RecordingRecoveryStore
import dev.chirpboard.app.feature.recording.session.SessionRecoveryResult
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * UI model for a recording with all its associated data pre-loaded.
 */
@androidx.compose.runtime.Stable
data class RecordingDisplayItem(
    val recording: Recording,
    val tags: ImmutableList<Tag> = persistentListOf(),
    val summary: String? = null,
    val profileName: String? = null,
    val profileIcon: String? = null,
    val isLiveCapture: Boolean = false,
) {
    val id get() = recording.id
    val title get() = recording.title
    val audioPath get() = recording.audioPath
    val status get() = recording.status
    val source get() = recording.source
    val profileId get() = recording.profileId
    val createdAtMs get() = recording.createdAt.time
    val durationMs get() = recording.durationMs
    val errorMessage get() = recording.errorMessage
    val isAudioReady get() = isPlaybackAndShareReadyAudioPath(audioPath)
}

internal fun isPlaybackAndShareReadyAudioPath(audioPath: String): Boolean =
    audioPath.isNotBlank() && !File(audioPath).extension.equals(RAW_KEYBOARD_AUDIO_EXTENSION, ignoreCase = true)

private const val RAW_KEYBOARD_AUDIO_EXTENSION = "f32pcm"

/**
 * Quick stats for the home screen header.
 */
@androidx.compose.runtime.Stable
data class HomeStats(
    val totalRecordings: Int = 0,
    val totalDurationMs: Long = 0L,
    val completedCount: Int = 0,
    val processingCount: Int = 0,
)

enum class ListFilterMode {
    ALL,
    PROCESSING,
}

/**
 * LOAD-3: first-load latch plus the emptiness of that same load, in one value so the two can
 * never disagree across independently-seeded flows.
 */
@androidx.compose.runtime.Stable
data class HomeLibraryLoadState(
    val loaded: Boolean = false,
    val empty: Boolean = true,
)

@androidx.compose.runtime.Stable
data class HomeQuickStartEntry(
    val id: UUID,
    val name: String,
    val icon: String? = null,
    val isPinned: Boolean = false,
)

@androidx.compose.runtime.Stable
data class RecordingPlaybackRowState(
    val recordingId: UUID? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val hasStartedPlayback: Boolean = false,
) {
    fun isForRecording(recordingId: UUID): Boolean = this.recordingId == recordingId
}

internal fun RecordingPlaybackState.toHomeRowState(): RecordingPlaybackRowState =
    RecordingPlaybackRowState(
        recordingId = recordingId,
        isPlaying = isPlaying,
        isLoading = isLoading,
        errorMessage = errorMessage,
        hasStartedPlayback = hasStartedPlayback,
    )

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        // I18N-08: snackbar/status copy comes from resources; the application context is the
        // standard Hilt-safe way to resolve them from a ViewModel.
        @ApplicationContext private val appContext: Context,
        private val recordingRepository: RecordingRepository,
        private val recordingManager: RecordingManager,
        private val tagRepository: TagRepository,
        private val profileRepository: ProfileRepository,
        private val transcriptionRecovery: TranscriptionRecovery,
        private val recordingTextEnrichment: RecordingTextEnhancementPort,
        private val audioImportOrchestrator: AudioImportOrchestrator,
        private val sessionRecovery: RecordingRecoveryStore,
        private val playbackController: RecordingPlaybackController,
        private val savedStateHandle: SavedStateHandle,
        // DATA-2: CPU-bound dispatcher for the heavy Home list transforms so the
        // filter/map/sort/build work (which re-runs on every Room invalidation) no longer
        // executes on the main dispatcher. Tests inject a TestDispatcher for determinism.
        @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
        private val serviceEvents: RecordingServiceEvents,
    ) : ViewModel() {
        /** Search query */
        private val _searchQuery = savedStateHandle.getStateFlow("searchQuery", "")
        val searchQuery: StateFlow<String> = _searchQuery

        private val _listFilter = savedStateHandle.getStateFlow("listFilter", ListFilterMode.ALL.name)
        val listFilter: StateFlow<ListFilterMode> =
            _listFilter
                .map {
                    ListFilterMode.valueOf(it)
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ListFilterMode.ALL)

        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

        // SLOP-23: progress/success notices ("Generating title...", "Title updated") have their own
        // channel so they are never painted as errors. errorMessage stays reserved for failures.
        private val _statusMessage = MutableStateFlow<String?>(null)
        val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

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
         * for the Home live row's hint line — the same resolution the record screen banner
         * uses, so the two surfaces always agree. Session-scoped: the service clears the
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

        private val _openStudioForRecordingId = MutableStateFlow<UUID?>(null)
        val openStudioForRecordingId: StateFlow<UUID?> = _openStudioForRecordingId.asStateFlow()

        fun consumeOpenStudioNavigation() {
            _openStudioForRecordingId.value = null
        }

        private val allProfiles: StateFlow<List<Profile>> =
            profileRepository
                .getAllProfiles()
                .unwrapRepositoryFlow { _errorMessage.value = it }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        private val allRecordingsState =
            recordingRepository
                .getAllRecordings()
                .onEach { state -> state.errorMessage?.let { _errorMessage.value = it } }
                .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

        private val allRecordingsRaw: StateFlow<List<Recording>> =
            allRecordingsState
                .map { it.value }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        /**
         * LOAD-3: `loaded` is false until the first recordings emission from Room resolves, then
         * latches true; `empty` reports whether that same emission carried zero recordings.
         *
         * Distinguishes "not loaded yet" from "loaded and genuinely empty" so the home screen holds
         * a skeleton on cold launch instead of flashing the empty illustration (and then crossfading
         * to the list) for a user who actually has recordings. Both fields travel in one value
         * derived from the same source flow as [allRecordingsRaw]: pairing the loaded latch with a
         * count from a separate query (the stats aggregate) let the empty illustration flash
         * whenever the list query resolved before the aggregate did.
         */
        val libraryLoadState: StateFlow<HomeLibraryLoadState> =
            allRecordingsState
                .map { HomeLibraryLoadState(loaded = true, empty = it.value.isEmpty()) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeLibraryLoadState())

        private val allRecordingsList: StateFlow<List<Recording>> = allRecordingsRaw

        private val filteredRecordings: StateFlow<List<Recording>> =
            _searchQuery
                // DATA-7: debounce keystrokes so a 7-character query no longer spawns 7 full
                // LIKE-scan flows + downstream re-subscriptions; distinctUntilChanged drops
                // no-op restores (e.g. clearing then retyping the same text). Only non-blank
                // queries are debounced, so the initial/cleared blank query reaches the full
                // list immediately instead of flashing an empty list for the debounce window.
                .debounce { query -> if (query.isBlank()) 0L else SEARCH_DEBOUNCE_MS }
                .distinctUntilChanged()
                .flatMapLatest { query ->
                    if (query.isBlank()) {
                        allRecordingsList
                    } else {
                        combine(
                            recordingRepository
                                .searchRecordings(query)
                                .unwrapRepositoryFlow { _errorMessage.value = it },
                            allRecordingsRaw,
                        ) { searchResults, allRecordings ->
                            val finalizingMatches =
                                allRecordings.filter { recording ->
                                    recording.status == RecordingStatus.RECORDING &&
                                        recording.title.contains(query, ignoreCase = true)
                                }
                            (searchResults + finalizingMatches)
                                .distinctBy(Recording::id)
                                .sortedByDescending { it.createdAt.time }
                        }
                    }
                }.flowOn(defaultDispatcher)
                    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        // DATA-1: key the tag + preview Room flows on the stable id-set, not the whole Recording
        // list. A background pipeline pass produces ~5 status transitions per recording; each one
        // re-emits filteredRecordings with a structurally different list but an identical id-set.
        // flatMapLatest on the id-set means those status ticks no longer cancel and recreate the
        // two inner Room flows (and rebuild their 500-entry maps) — the inner flows survive and
        // Room's own result dedup applies. The latest recordings list is combined back in below so
        // pure status updates still flow through to the rendered items.
        private val tagsAndPreviewsForVisibleRecordings:
            StateFlow<Pair<Map<UUID, List<Tag>>, Map<UUID, TranscriptPreview>>> =
            filteredRecordings
                .map { recordings -> recordings.map(Recording::id) }
                .distinctUntilChanged()
                .flatMapLatest { recordingIds ->
                    if (recordingIds.isEmpty()) {
                        flowOf(emptyMap<UUID, List<Tag>>() to emptyMap<UUID, TranscriptPreview>())
                    } else {
                        combine(
                            tagRepository
                                .getTagsForRecordingIdsFlow(recordingIds)
                                .unwrapRepositoryFlow { _errorMessage.value = it },
                            recordingRepository
                                .getTranscriptPreviewsFlow(recordingIds, HOME_TRANSCRIPT_PREVIEW_LIMIT)
                                .unwrapRepositoryFlow { _errorMessage.value = it },
                        ) { tagsByRecordingId, previewsByRecordingId ->
                            tagsByRecordingId to previewsByRecordingId
                        }
                    }
                }.flowOn(defaultDispatcher)
                    .stateIn(
                        viewModelScope,
                        SharingStarted.WhileSubscribed(5000),
                        emptyMap<UUID, List<Tag>>() to emptyMap(),
                    )

        private val recordingsWithTagsAndTranscripts: StateFlow<List<RecordingDisplayItem>> =
            combine(
                filteredRecordings,
                tagsAndPreviewsForVisibleRecordings,
            ) { recordings, (tagsByRecordingId, previewsByRecordingId) ->
                buildRecordingDisplayItems(
                    recordings = recordings,
                    tagsByRecordingId = tagsByRecordingId,
                    previewsByRecordingId = previewsByRecordingId,
                )
            }.flowOn(defaultDispatcher)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        /** All recordings based on search, enriched with tags/summary/profile */
        private val allDisplayItems: StateFlow<List<RecordingDisplayItem>> =
            combine(recordingsWithTagsAndTranscripts, allProfiles) { items, profiles ->
                val profilesById = profiles.associateBy(Profile::id)
                items.map { item ->
                    val profile = item.recording.profileId?.let(profilesById::get)
                    item.copy(
                        profileName = profile?.name,
                        profileIcon = profile?.icon,
                    )
                }
            }.flowOn(defaultDispatcher)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        val quickStartProfiles: StateFlow<List<HomeQuickStartEntry>> =
            combine(allProfiles, allRecordingsList) { profiles, recordings ->
                deriveHomeQuickStarts(profiles = profiles, recordings = recordings)
            }.flowOn(defaultDispatcher)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        val displayItems: StateFlow<List<RecordingDisplayItem>> =
            combine(
                allDisplayItems,
                _listFilter,
                recordingManager.state,
            ) { items, filter, recordingState ->
                val enriched =
                    items.map { item ->
                        item.copy(
                            isLiveCapture = isLiveCaptureHomeListItem(item.recording, recordingState),
                        )
                    }
                if (filter == ListFilterMode.ALL.name) {
                    enriched
                } else {
                    enriched.filter { item ->
                        isHomeListProcessingItem(item.recording)
                    }
                }
            }.flowOn(defaultDispatcher)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        val stuckCount: StateFlow<Int> =
            allDisplayItems
                .map { items ->
                    items.count { item ->
                        isProcessingOrStuckStatus(item.status)
                    }
                }.flowOn(defaultDispatcher)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

        // DAT-006: count/duration/completed come from a full-table aggregate; the list flow is
        // capped at the latest 500 rows, so deriving header stats from it undercounts (and shows
        // "500 recordings" forever) once the library outgrows the cap.
        private val libraryStats: StateFlow<RecordingLibraryStats> =
            recordingRepository
                .getLibraryStats()
                .unwrapRepositoryFlow { _errorMessage.value = it }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RecordingLibraryStats())

        /** Quick stats for the home header; full-table counts, capped-list processing count. */
        val stats: StateFlow<HomeStats> =
            combine(libraryStats, allRecordingsList) { library, recordings ->
                HomeStats(
                    totalRecordings = library.totalCount,
                    totalDurationMs = library.totalDurationMs,
                    completedCount = library.completedCount,
                    processingCount =
                        recordings.count {
                            isHomeListProcessingItem(it)
                        },
                )
            }.flowOn(defaultDispatcher)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeStats())

        /**
         * DAT-006: true once the library has outgrown the home list's row cap, so the list can
         * disclose "showing latest N" instead of silently dropping the oldest recordings.
         */
        val isHomeListCapped: StateFlow<Boolean> =
            libraryStats
                .map { it.totalCount > RecordingDao.HOME_RECORDINGS_LIMIT }
                .distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

        /** Current recording state */
        val recordingState: StateFlow<RecordingState> = recordingManager.state

        private val _isImporting = MutableStateFlow(false)
        val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

        private val _recoverableSessions = sessionRecovery.actionablePendingSessions
        val recoverableSessions: StateFlow<List<RecoverableRecordingSession>> = _recoverableSessions
        val playbackState: StateFlow<RecordingPlaybackState> = playbackController.state
        val playbackRowState: StateFlow<RecordingPlaybackRowState> =
            playbackController
                .state
                .map(RecordingPlaybackState::toHomeRowState)
                .distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RecordingPlaybackRowState())

        init {
            refreshRecoverableSessions()
        }

        fun refreshRecoverableSessions() {
            viewModelScope.launch {
                sessionRecovery.refresh()
            }
        }

        fun recoverInterruptedSession(sessionId: UUID) {
            viewModelScope.launch {
                when (val result = sessionRecovery.recoverSession(sessionId)) {
                    is SessionRecoveryResult.Recovered -> {
                        _errorMessage.value =
                            result.estimatedLostMinutes?.let { lostMinutes ->
                                appContext.resources.getQuantityString(
                                    R.plurals.rec_msg_recovered_with_loss,
                                    lostMinutes,
                                    lostMinutes,
                                )
                            } ?: appContext.getString(R.string.rec_msg_recovered)
                        refreshRecoverableSessions()
                    }
                    is SessionRecoveryResult.Failed -> {
                        _errorMessage.value = result.message
                        refreshRecoverableSessions()
                    }
                    else -> refreshRecoverableSessions()
                }
            }
        }

        fun discardInterruptedSession(sessionId: UUID) {
            viewModelScope.launch {
                val result = sessionRecovery.discardSession(sessionId)
                if (result is SessionRecoveryResult.Failed) {
                    // Surface the refusal (e.g. the finalize worker still owns the
                    // session) instead of letting the card silently disappear.
                    _errorMessage.value = result.message
                }
                refreshRecoverableSessions()
            }
        }

        fun keepInterruptedSession(sessionId: UUID) {
            viewModelScope.launch {
                val result = sessionRecovery.keepSession(sessionId)
                if (result is SessionRecoveryResult.Failed) {
                    _errorMessage.value = result.message
                }
                refreshRecoverableSessions()
            }
        }

        fun deferInterruptedSession(sessionId: UUID) {
            sessionRecovery.deferSession(sessionId)
        }

        fun playRecording(item: RecordingDisplayItem) {
            if (!item.isAudioReady) {
                if (item.audioPath.isBlank()) {
                    _errorMessage.value = appContext.getString(CoreUiR.string.rec_msg_audio_missing)
                }
                return
            }
            playbackController.play(item.id, item.title, item.audioPath)
        }

        /** Update search query */
        fun onSearchQueryChange(query: String) {
            savedStateHandle["searchQuery"] = query
        }

        fun onProcessingClick() {
            if (_listFilter.value == ListFilterMode.PROCESSING.name) {
                savedStateHandle["listFilter"] = ListFilterMode.ALL.name
                return
            }
            val hasProcessingItems =
                allRecordingsList.value.any { isProcessingOrStuckStatus(it.status) }
            if (!hasProcessingItems) {
                return
            }
            savedStateHandle["listFilter"] = ListFilterMode.PROCESSING.name
        }

        fun clearListFilters() {
            savedStateHandle["listFilter"] = ListFilterMode.ALL.name
            savedStateHandle["searchQuery"] = ""
        }

        fun setListFilter(filter: ListFilterMode) {
            savedStateHandle["listFilter"] = filter.name
        }

        /**
         * Toggle recording on/off.
         */
        fun toggleRecording(profileId: UUID? = null) {
            viewModelScope.launch {
                val result = recordingManager.toggleRecording(RecordingOrigin.APP, profileId)

                if (result is dev.chirpboard.app.feature.recording.ToggleResult.Started &&
                    result.startResult is RecordingStartResult.AlreadyRecording
                ) {
                    val originText =
                        when (result.startResult.currentOrigin) {
                            RecordingOrigin.APP -> appContext.getString(R.string.rec_origin_app)
                            RecordingOrigin.KEYBOARD -> appContext.getString(R.string.rec_origin_keyboard)
                            RecordingOrigin.WIDGET -> appContext.getString(R.string.rec_origin_widget)
                            RecordingOrigin.RECOGNITION -> appContext.getString(R.string.rec_origin_recognition)
                        }
                    _errorMessage.value = appContext.getString(R.string.rec_msg_already_recording, originText)
                }
            }
        }

        /**
         * Delete a recording.
         *
         * Order matters: Delete from database FIRST (critical), then audio file (best effort).
         * If DB delete fails, we keep the file. If file delete fails, that's acceptable
         * since the DB record is already gone. Transcript is cascade-deleted by Room.
         */
        fun deleteRecording(recording: RecordingDisplayItem) {
            viewModelScope.launch {
                try {
                    // Stop the mini player first when it is playing the row being deleted —
                    // otherwise it keeps playing (and holding) audio for a recording that no
                    // longer exists. Mirrors ProcessingStudioViewModel.deleteRecording.
                    if (playbackController.state.value.recordingId == recording.id) {
                        playbackController.stop()
                    }

                    // Step 0: cancel any queued/running transcription or enhancement work so
                    // an orphaned worker never spins up for the deleted row (PIPE-07).
                    transcriptionRecovery.cancelProcessing(recording.id)

                    // Step 1: Delete from database FIRST (the critical operation)
                    // Transcript is cascade-deleted via ForeignKey constraint
                    recordingRepository.deleteById(recording.id)

                    // Step 2: Delete audio file (non-critical, best effort)
                    // Run on IO dispatcher to avoid blocking main thread
                    withContext(Dispatchers.IO) {
                        try {
                            val file = File(recording.audioPath)
                            if (file.exists() && !file.delete()) {
                                Log.w(TAG, "Failed to delete audio file: ${recording.audioPath}")
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            // File deletion is non-fatal - log and continue
                            Log.w(TAG, "Error deleting audio file: ${recording.audioPath}", e)
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "Failed to delete recording: ${recording.id}", e)
                    _errorMessage.value = appContext.getString(CoreUiR.string.rec_msg_delete_failed)
                }
            }
        }

        /**
         * Clear error message.
         */
        fun clearError() {
            _errorMessage.value = null
            recordingManager.clearError()
        }

        /**
         * Clear the transient status notice once shown.
         */
        fun clearStatus() {
            _statusMessage.value = null
        }

        /**
         * Share a recording (audio + transcript if available).
         */
        fun shareRecording(
            recording: RecordingDisplayItem,
            context: Context,
        ) {
            viewModelScope.launch {
                if (!recording.isAudioReady) return@launch
                val file = File(recording.audioPath)

                // Check file existence on IO dispatcher
                val exists = withContext(Dispatchers.IO) { file.exists() }
                if (!exists) {
                    _errorMessage.value = appContext.getString(CoreUiR.string.rec_msg_audio_missing)
                    return@launch
                }

                try {
                    val uri =
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.provider",
                            file,
                        )

                    // Get transcript text if available
                    val transcript = recordingRepository.getTranscript(recording.id)
                    val text =
                        if (transcript != null) {
                            buildString {
                                appendLine("# ${recording.title}")
                                appendLine()
                                transcript.summary?.let { summary ->
                                    appendLine("## Summary")
                                    appendLine(summary)
                                    appendLine()
                                }
                                appendLine("## Transcript")
                                appendLine(transcript.effectiveText)
                            }
                        } else {
                            recording.title
                        }

                    val intent =
                        Intent(Intent.ACTION_SEND).apply {
                            type = RecordingOutputFormat.fromFile(file).mimeType
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, recording.title)
                            putExtra(Intent.EXTRA_TEXT, text)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }

                    context.startActivity(
                        Intent.createChooser(intent, context.getString(CoreUiR.string.rec_share_recording_chooser)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // I18N-05: exception messages are developer diagnostics; keep them in logs.
                    Log.e(TAG, "Failed to share recording ${recording.id}", e)
                    _errorMessage.value = appContext.getString(CoreUiR.string.rec_msg_share_failed)
                }
            }
        }

        /**
         * Retry transcription for a failed recording. Surfaces the actual outcome:
         * the retry may be refused (no longer FAILED, active work, ownership timeout)
         * and reporting an unconditional success would mislead the user.
         */
        fun retryTranscription(recording: RecordingDisplayItem) {
            viewModelScope.launch {
                if (recording.status == RecordingStatus.FAILED) {
                    val result = transcriptionRecovery.retry(recording.id)
                    _errorMessage.value =
                        result.toUserMessage(appContext, appContext.getString(CoreUiR.string.rec_msg_requeued_transcription))
                }
            }
        }

        fun recoverStuckItem(recording: RecordingDisplayItem) {
            viewModelScope.launch {
                val result =
                    when (recording.status) {
                        RecordingStatus.PENDING_TRANSCRIPTION -> {
                            transcriptionRecovery.recoverPendingTranscription(recording.id)
                        }

                        RecordingStatus.PENDING_ENHANCEMENT -> {
                            transcriptionRecovery.recoverPendingEnhancement(recording.id)
                        }

                        RecordingStatus.ENHANCING -> {
                            transcriptionRecovery.recoverEnhancing(recording.id)
                        }

                        else -> {
                            ManualRecoveryResult.NOT_RECOVERABLE_STATE
                        }
                    }

                _errorMessage.value =
                    result.toUserMessage(appContext, appContext.getString(R.string.rec_msg_recovery_queued))
            }
        }

        fun recoverAllStuck() {
            viewModelScope.launch {
                val recoveredCount = transcriptionRecovery.recoverStuckRecordings()
                _errorMessage.value =
                    if (recoveredCount > 0) {
                        appContext.resources.getQuantityString(
                            R.plurals.rec_msg_recover_all_queued,
                            recoveredCount,
                            recoveredCount,
                        )
                    } else {
                        appContext.getString(R.string.rec_msg_recover_all_none)
                    }
            }
        }

        /**
         * Generate an AI title for a recording.
         */
        fun generateTitle(recording: RecordingDisplayItem) {
            enrich(
                recording = recording,
                missingTranscriptError = appContext.getString(R.string.rec_msg_no_transcript_for_title),
                inProgressStatus = appContext.getString(R.string.rec_msg_generating_title),
                successStatus = appContext.getString(R.string.rec_msg_title_updated),
                failureTemplateRes = R.string.rec_msg_title_generation_failed,
                generate = { text -> recordingTextEnrichment.generateTitle(enrichmentContext(recording, text)) },
                persist = { result -> recordingRepository.updateTitle(recording.id, result) },
            )
        }

        /**
         * Generate an AI summary for a recording.
         */
        fun generateSummary(recording: RecordingDisplayItem) {
            enrich(
                recording = recording,
                missingTranscriptError = appContext.getString(R.string.rec_msg_no_transcript_for_summary),
                inProgressStatus = appContext.getString(R.string.rec_msg_generating_summary),
                successStatus = appContext.getString(R.string.rec_msg_summary_updated),
                failureTemplateRes = R.string.rec_msg_summary_generation_failed,
                generate = { text -> recordingTextEnrichment.generateSummary(enrichmentContext(recording, text)) },
                persist = { result -> recordingRepository.updateSummary(recording.id, result) },
            )
        }

        /**
         * Mirrors the enhancement worker's provider selection so a manual Generate action uses
         * the same backend the recording's automatic enrichment used: an explicitly requested
         * provider first, then Vertex for Chirp 3 cloud transcriptions, else the active provider.
         */
        private fun enrichmentContext(
            recording: RecordingDisplayItem,
            text: String,
        ): RecordingTextEnhancementContext {
            val requestedProviderId = recording.recording.requestedLlmProviderId
            val requestedModelId = recording.recording.requestedLlmModelId
            val (providerId, modelId) =
                when {
                    requestedProviderId != null || requestedModelId != null ->
                        requestedProviderId to requestedModelId

                    recording.recording.transcriptionEngineId == TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3.id ->
                        GOOGLE_CLOUD_VERTEX_PROVIDER_ID to null

                    else -> null to null
                }
            return RecordingTextEnhancementContext(
                text = text,
                providerId = providerId,
                modelId = modelId,
                recordingId = recording.id.toString(),
            )
        }

        /**
         * Shared title/summary enrichment flow (SLOP-23). Progress and success notices go through
         * [_statusMessage]; only genuine failures go through [_errorMessage].
         */
        private fun enrich(
            recording: RecordingDisplayItem,
            missingTranscriptError: String,
            inProgressStatus: String,
            successStatus: String,
            @androidx.annotation.StringRes failureTemplateRes: Int,
            generate: suspend (String) -> Result<String>,
            persist: suspend (String) -> Unit,
        ) {
            viewModelScope.launch {
                val transcript = recordingRepository.getTranscript(recording.id)
                if (transcript == null) {
                    _errorMessage.value = missingTranscriptError
                    return@launch
                }

                _statusMessage.value = inProgressStatus

                val result = generate(transcript.effectiveText)
                result.fold(
                    onSuccess = { value ->
                        // ERR-18: a one-shot Room write throws on a full disk; surface it
                        // instead of crashing the process from this bare launch.
                        try {
                            persist(value)
                            _statusMessage.value = successStatus
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.e(TAG, "Failed to persist enrichment result", e)
                            _errorMessage.value = appContext.getString(R.string.rec_msg_persist_failed_storage)
                        }
                    },
                    onFailure = { error ->
                        // I18N-05: never interpolate raw exception text into UI copy.
                        Log.e(TAG, "Enrichment failed for ${recording.id}", error)
                        _errorMessage.value =
                            appContext.getString(
                                failureTemplateRes,
                                appContext.getString(enrichmentFailureHintRes(error)),
                            )
                    },
                )
            }
        }

        /**
         * PIPE-07: user-facing cancel for queued/running transcription. The recovery port
         * resolves the row to a neutral awaiting/completed state rather than FAILED.
         */
        fun cancelTranscription(recording: RecordingDisplayItem) {
            viewModelScope.launch {
                try {
                    transcriptionRecovery.cancelProcessing(recording.id)
                    _statusMessage.value = appContext.getString(CoreUiR.string.rec_msg_transcription_cancelled)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "Failed to cancel processing for ${recording.id}", e)
                    _errorMessage.value = appContext.getString(CoreUiR.string.rec_msg_cancel_transcription_failed)
                }
            }
        }

        /**
         * PLH-4: explicit start for a recording whose profile skipped auto-transcription
         * (AWAITING_MANUAL_TRANSCRIPTION).
         */
        fun startManualTranscription(recording: RecordingDisplayItem) {
            viewModelScope.launch {
                val result = transcriptionRecovery.retranscribe(recording.id)
                val message =
                    result.toUserMessage(appContext, appContext.getString(R.string.rec_msg_queued_for_transcription))
                if (result == ManualRecoveryResult.ENQUEUED) {
                    _statusMessage.value = message
                } else {
                    _errorMessage.value = message
                }
            }
        }

        fun importAudio(uri: Uri) {
            _isImporting.value = true
            viewModelScope.launch {
                try {
                    when (val result = audioImportOrchestrator.import(uri)) {
                        is AudioImportResult.FailedBeforePersistence -> {
                            _errorMessage.value = result.message
                        }

                        is AudioImportResult.SavedAndQueued -> {
                            _openStudioForRecordingId.value = result.recordingId
                        }

                        is AudioImportResult.SavedPendingRecovery -> {
                            _openStudioForRecordingId.value = result.recordingId
                            _errorMessage.value = result.message
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // I18N-05: exception messages are developer diagnostics; keep them in logs.
                    Log.e(TAG, "Failed to import audio", e)
                    _errorMessage.value = appContext.getString(R.string.rec_msg_import_failed)
                } finally {
                    _isImporting.value = false
                }
            }
        }

        companion object {
            private const val TAG = "HomeViewModel"
            private const val SEARCH_DEBOUNCE_MS = 200L
        }
    }

internal fun isProcessingOrStuckStatus(status: RecordingStatus): Boolean =
    status in
        setOf(
            RecordingStatus.TRANSCRIBING,
            RecordingStatus.ENHANCING,
            RecordingStatus.PENDING_TRANSCRIPTION,
            RecordingStatus.PENDING_ENHANCEMENT,
        )

/**
 * True when the row represents the active in-app capture session (not background finalize).
 */
internal fun isLiveCaptureHomeListItem(
    recording: Recording,
    recordingState: RecordingState,
): Boolean {
    if (recording.status != RecordingStatus.RECORDING) {
        return false
    }
    val activeRecordingId = recordingState.activeRecordingId ?: return false
    if (recording.id != activeRecordingId) {
        return false
    }
    return when (recordingState) {
        is RecordingState.Starting,
        is RecordingState.Recording,
        is RecordingState.Paused,
        -> true

        else -> false
    }
}

internal fun isHomeListProcessingItem(
    recording: Recording,
): Boolean =
    isProcessingOrStuckStatus(recording.status) ||
        recording.status == RecordingStatus.RECORDING

internal fun buildRecordingDisplayItems(
    recordings: List<Recording>,
    tagsByRecordingId: Map<UUID, List<Tag>>,
    previewsByRecordingId: Map<UUID, TranscriptPreview>,
): List<RecordingDisplayItem> =
    recordings.map { recording ->
        val tags = tagsByRecordingId[recording.id].orEmpty()
        val transcriptPreview = previewsByRecordingId[recording.id]
        RecordingDisplayItem(
            recording = recording,
            tags = tags.toImmutableList(),
            summary = transcriptPreview?.summary ?: transcriptPreview?.previewText,
        )
    }

private const val HOME_TRANSCRIPT_PREVIEW_LIMIT = 120

internal fun deriveHomeQuickStarts(
    profiles: List<Profile>,
    recordings: List<Recording>,
): List<HomeQuickStartEntry> {
    if (profiles.isEmpty()) {
        return emptyList()
    }

    val profilesById = profiles.associateBy(Profile::id)
    val pinnedProfiles =
        profiles
            .asSequence()
            .filter(Profile::isQuickStartPinned)
            .sortedWith(compareBy(Profile::sortOrder, Profile::name))
            .toList()

    val pinnedIds = pinnedProfiles.map(Profile::id).toSet()
    val recentProfiles =
        recordings
            .asSequence()
            .mapNotNull(Recording::profileId)
            .filter(profilesById::containsKey)
            .filterNot(pinnedIds::contains)
            .distinct()
            .mapNotNull(profilesById::get)
            .toList()

    return (pinnedProfiles + recentProfiles)
        .take(4)
        .map { profile ->
            HomeQuickStartEntry(
                id = profile.id,
                name = profile.name,
                icon = profile.icon,
                isPinned = profile.isQuickStartPinned,
            )
        }
}

internal fun shouldShowHomeQuickStartSurface(quickStarts: List<HomeQuickStartEntry>): Boolean = quickStarts.isNotEmpty()

/**
 * I18N-05: actionable hint for an AI title/summary failure instead of the raw exception message
 * (which is logged, not shown).
 */
@androidx.annotation.StringRes
internal fun enrichmentFailureHintRes(error: Throwable): Int =
    if (error is java.io.IOException) {
        R.string.rec_msg_enrichment_hint_network
    } else {
        R.string.rec_msg_enrichment_hint_generic
    }

/**
 * I18N-05/I18N-06: persisted worker error messages are machine codes (or raw exception text on
 * legacy rows). The home card maps the typed kind to friendly resource copy and never displays
 * the persisted text itself; unknown kinds fall back to the generic stuck-state line.
 */
@androidx.annotation.StringRes
internal fun homeProcessingNoteRes(errorMessage: String?): Int? =
    when (dev.chirpboard.app.data.model.classifyRecordingProcessingNote(errorMessage)) {
        dev.chirpboard.app.data.model.RecordingProcessingNoteKind.STALE_RECOVERED -> R.string.rec_note_stale_recovered
        dev.chirpboard.app.data.model.RecordingProcessingNoteKind.QUEUE_HANDOFF -> R.string.rec_note_queue_handoff
        dev.chirpboard.app.data.model.RecordingProcessingNoteKind.MANUAL_RECOVERY -> R.string.rec_note_manual_recovery
        dev.chirpboard.app.data.model.RecordingProcessingNoteKind.WAITING_FOR_MODEL -> null
        dev.chirpboard.app.data.model.RecordingProcessingNoteKind.OTHER -> null
    }
