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
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingStartResult
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.audio.RecordingPlaybackController
import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.TagRepository
import dev.chirpboard.app.data.repository.unwrapRepositoryFlow
import dev.chirpboard.app.feature.llm.client.LlmClient
import dev.chirpboard.app.feature.recording.RecordingManager
import dev.chirpboard.app.feature.recording.importing.AudioImportOrchestrator
import dev.chirpboard.app.feature.recording.importing.AudioImportResult
import dev.chirpboard.app.feature.recording.session.RecoverableRecordingSession
import dev.chirpboard.app.feature.recording.session.RecordingRecoveryStore
import dev.chirpboard.app.feature.recording.session.SessionRecoveryResult
import dev.chirpboard.app.core.transcription.ManualRecoveryResult
import dev.chirpboard.app.core.transcription.TranscriptionRecovery
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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
}

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

@androidx.compose.runtime.Stable
data class HomeQuickStartEntry(
    val id: UUID,
    val name: String,
    val icon: String? = null,
    val isPinned: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val recordingRepository: RecordingRepository,
        private val recordingManager: RecordingManager,
        private val tagRepository: TagRepository,
        private val profileRepository: ProfileRepository,
        private val transcriptionRecovery: TranscriptionRecovery,
        private val llmClient: LlmClient,
        private val audioImportOrchestrator: AudioImportOrchestrator,
        private val sessionRecovery: RecordingRecoveryStore,
        private val playbackController: RecordingPlaybackController,
        private val savedStateHandle: SavedStateHandle,
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

        private val allProfiles: StateFlow<List<Profile>> =
            profileRepository
                .getAllProfiles()
                .unwrapRepositoryFlow { _errorMessage.value = it }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        private val allRecordingsList: StateFlow<List<Recording>> =
            recordingRepository
                .getAllRecordings()
                .unwrapRepositoryFlow { _errorMessage.value = it }
                .map { recordings ->
                    recordings.filter { it.status != RecordingStatus.RECORDING }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        private val filteredRecordings: StateFlow<List<Recording>> =
            combine(_searchQuery, allRecordingsList) { query, all ->
                query to all
            }.flatMapLatest { (query, all) ->
                if (query.isBlank()) {
                    kotlinx.coroutines.flow.flowOf(all)
                } else {
                    recordingRepository
                        .searchRecordings(query)
                        .unwrapRepositoryFlow { _errorMessage.value = it }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        private val recordingsWithTagsAndTranscripts: StateFlow<List<RecordingDisplayItem>> =
            filteredRecordings
                .map { recordings ->
                    withContext(Dispatchers.IO) {
                        val recordingIds = recordings.map(Recording::id)
                        val tagsByRecordingId = tagRepository.getTagsForRecordingIds(recordingIds)
                        val transcriptsByRecordingId = recordingRepository.getTranscripts(recordingIds)

                        recordings.map { recording ->
                            val tags = tagsByRecordingId[recording.id].orEmpty()
                            val transcript = transcriptsByRecordingId[recording.id]
                            RecordingDisplayItem(
                                recording = recording,
                                tags = tags.toImmutableList(),
                                summary =
                                    transcript?.summary ?: transcript?.effectiveText?.take(120),
                            )
                        }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        val quickStartProfiles: StateFlow<List<HomeQuickStartEntry>> =
            combine(allProfiles, allRecordingsList) { profiles, recordings ->
                deriveHomeQuickStarts(profiles = profiles, recordings = recordings)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        val displayItems: StateFlow<List<RecordingDisplayItem>> =
            combine(
                allDisplayItems,
                _listFilter,
            ) { items, filter ->
                if (filter == ListFilterMode.ALL.name) {
                    items
                } else {
                    items.filter { isProcessingOrStuckStatus(it.status) }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        val stuckCount: StateFlow<Int> =
            allDisplayItems
                .map { items ->
                    items.count { item ->
                        item.status == RecordingStatus.PENDING_TRANSCRIPTION ||
                            item.status == RecordingStatus.ENHANCING
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

        /** Quick stats derived from recordings */
        val stats: StateFlow<HomeStats> =
            allRecordingsList
                .map { recordings ->
                    HomeStats(
                        totalRecordings = recordings.size,
                        totalDurationMs = recordings.sumOf { it.durationMs },
                        completedCount =
                            recordings.count {
                                it.status == dev.chirpboard.app.data.model.RecordingStatus.COMPLETED
                            },
                        processingCount =
                            recordings.count {
                                it.status in
                                    listOf(
                                        dev.chirpboard.app.data.model.RecordingStatus.TRANSCRIBING,
                                        dev.chirpboard.app.data.model.RecordingStatus.ENHANCING,
                                        dev.chirpboard.app.data.model.RecordingStatus.PENDING_TRANSCRIPTION,
                                        dev.chirpboard.app.data.model.RecordingStatus.PENDING_ENHANCEMENT,
                                    )
                            },
                    )
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeStats())

        /** Current recording state */
        val recordingState: StateFlow<RecordingState> = recordingManager.state

        private val _isImporting = MutableStateFlow(false)
        val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

        private val _recoverableSessions = sessionRecovery.pendingSessions
        val recoverableSessions: StateFlow<List<RecoverableRecordingSession>> = _recoverableSessions
        val playbackState: StateFlow<dev.chirpboard.app.core.audio.RecordingPlaybackState> = playbackController.state

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
                                "Recording recovered. Up to $lostMinutes minute(s) of recent audio may be missing."
                            } ?: "Recording recovered."
                        refreshRecoverableSessions()
                    }
                    is SessionRecoveryResult.Failed -> {
                        _errorMessage.value = result.message
                    }
                    else -> refreshRecoverableSessions()
                }
            }
        }

        fun discardInterruptedSession(sessionId: UUID) {
            viewModelScope.launch {
                sessionRecovery.discardSession(sessionId)
                refreshRecoverableSessions()
            }
        }

        fun keepInterruptedSession(sessionId: UUID) {
            viewModelScope.launch {
                sessionRecovery.keepSession(sessionId)
                refreshRecoverableSessions()
            }
        }

        fun playRecording(item: RecordingDisplayItem) {
            if (item.audioPath.isBlank()) {
                _errorMessage.value = "Audio file not found"
                return
            }
            playbackController.play(item.id, item.title, item.audioPath)
        }

        /** Update search query */
        fun onSearchQueryChange(query: String) {
            savedStateHandle["searchQuery"] = query
        }

        fun onProcessingClick() {
            val newListFilter =
                if (_listFilter.value == ListFilterMode.PROCESSING.name) {
                    ListFilterMode.ALL.name
                } else {
                    ListFilterMode.PROCESSING.name
                }
            savedStateHandle["listFilter"] = newListFilter
        }

        fun setListFilter(filter: ListFilterMode) {
            savedStateHandle["listFilter"] = filter.name
        }

        /**
         * Toggle recording on/off.
         */
        fun toggleRecording(profileId: UUID? = null) {
            val result = recordingManager.toggleRecording(RecordingOrigin.APP, profileId)

            if (result is dev.chirpboard.app.feature.recording.ToggleResult.Started &&
                result.startResult is RecordingStartResult.AlreadyRecording
            ) {
                val originText =
                    when (result.startResult.currentOrigin) {
                        RecordingOrigin.APP -> "the app"
                        RecordingOrigin.KEYBOARD -> "the keyboard"
                        RecordingOrigin.WIDGET -> "the widget"
                    }
                _errorMessage.value = "Recording already in progress from $originText"
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
                    _errorMessage.value = "Failed to delete recording"
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
         * Share a recording (audio + transcript if available).
         */
        fun shareRecording(
            recording: RecordingDisplayItem,
            context: Context,
        ) {
            viewModelScope.launch {
                val file = File(recording.audioPath)

                // Check file existence on IO dispatcher
                val exists = withContext(Dispatchers.IO) { file.exists() }
                if (!exists) {
                    _errorMessage.value = "Audio file not found"
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
                            type = "audio/m4a"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, recording.title)
                            putExtra(Intent.EXTRA_TEXT, text)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }

                    context.startActivity(
                        Intent.createChooser(intent, "Share recording").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _errorMessage.value = "Failed to share: ${e.message}"
                }
            }
        }

        /**
         * Retry transcription for a failed recording.
         */
        fun retryTranscription(recording: RecordingDisplayItem) {
            viewModelScope.launch {
                if (recording.status == RecordingStatus.FAILED) {
                    transcriptionRecovery.retry(recording.id)
                    _errorMessage.value = "Re-queued for transcription"
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

                        RecordingStatus.ENHANCING -> {
                            transcriptionRecovery.recoverEnhancing(recording.id)
                        }

                        else -> {
                            ManualRecoveryResult.NOT_RECOVERABLE_STATE
                        }
                    }

                _errorMessage.value =
                    when (result) {
                        ManualRecoveryResult.ENQUEUED -> "Recovery queued"
                        ManualRecoveryResult.BLOCKED_ACTIVE_WORK -> "Already processing. Recovery skipped"
                        ManualRecoveryResult.BLOCKED_OWNERSHIP_TIMEOUT -> "Ownership check timed out. Try again"
                        ManualRecoveryResult.NOT_RECOVERABLE_STATE -> "Recovery unavailable for this item"
                    }
            }
        }

        fun recoverAllStuck() {
            viewModelScope.launch {
                val recoveredCount = transcriptionRecovery.recoverStuckRecordings()
                _errorMessage.value =
                    if (recoveredCount > 0) {
                        "Queued recovery for $recoveredCount recording${if (recoveredCount == 1) "" else "s"}"
                    } else {
                        "No recoverable recordings were queued"
                    }
            }
        }

        /**
         * Generate an AI title for a recording.
         */
        fun generateTitle(recording: RecordingDisplayItem) {
            viewModelScope.launch {
                val transcript = recordingRepository.getTranscript(recording.id)
                if (transcript == null) {
                    _errorMessage.value = "No transcript available for title generation"
                    return@launch
                }

                _errorMessage.value = "Generating title..."

                val text = transcript.effectiveText
                val result = llmClient.generateTitle(text)

                result.fold(
                    onSuccess = { title ->
                        recordingRepository.updateTitle(recording.id, title)
                        _errorMessage.value = "Title updated"
                    },
                    onFailure = { error ->
                        _errorMessage.value = "Failed to generate title: ${error.message}"
                    },
                )
            }
        }

        /**
         * Generate an AI summary for a recording.
         */
        fun generateSummary(recording: RecordingDisplayItem) {
            viewModelScope.launch {
                val transcript = recordingRepository.getTranscript(recording.id)
                if (transcript == null) {
                    _errorMessage.value = "No transcript available for summary generation"
                    return@launch
                }

                _errorMessage.value = "Generating summary..."

                val text = transcript.effectiveText
                val result = llmClient.generateSummary(text)

                result.fold(
                    onSuccess = { summary ->
                        recordingRepository.updateSummary(recording.id, summary)
                        _errorMessage.value = "Summary updated"
                    },
                    onFailure = { error ->
                        _errorMessage.value = "Failed to generate summary: ${error.message}"
                    },
                )
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
                            Unit
                        }

                        is AudioImportResult.SavedPendingRecovery -> {
                            _errorMessage.value = result.message
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "Failed to import audio", e)
                    _errorMessage.value = "Failed to import audio: ${e.message}"
                } finally {
                    _isImporting.value = false
                }
            }
        }

        companion object {
            private const val TAG = "HomeViewModel"
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
