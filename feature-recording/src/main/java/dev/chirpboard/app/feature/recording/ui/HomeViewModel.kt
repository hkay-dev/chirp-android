package dev.chirpboard.app.feature.recording.ui

import android.net.Uri
import android.media.MediaMetadataRetriever
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingStartResult
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.TagRepository
import dev.chirpboard.app.feature.llm.client.LlmClient
import dev.chirpboard.app.feature.recording.RecordingManager
import dev.chirpboard.app.feature.transcription.ManualRecoveryResult
import dev.chirpboard.app.feature.transcription.TranscriptionQueueManager
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.util.concurrent.ConcurrentHashMap
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
)

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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val recordingRepository: RecordingRepository,
        private val recordingManager: RecordingManager,
        private val tagRepository: TagRepository,
        private val profileRepository: ProfileRepository,
        private val transcriptionQueueManager: TranscriptionQueueManager,
        private val llmClient: LlmClient,
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

        /** Cached profiles for fast lookup */
        private val profileCache = ConcurrentHashMap<UUID, Profile?>()

        /** All recordings based on search, enriched with tags/summary/profile */
        private val allDisplayItems: StateFlow<List<RecordingDisplayItem>> =
            _searchQuery
                .flatMapLatest { query ->
                    if (query.isBlank()) {
                        recordingRepository.getAllRecordings()
                    } else {
                        recordingRepository.searchRecordings(query)
                    }
                }.map { recordings ->
                    withContext(Dispatchers.IO) {
                        val profileIds = recordings.mapNotNull { it.profileId }.distinct()
                        val missingProfileIds = profileIds.filterNot(profileCache::containsKey)
                        profileCache.putAll(profileRepository.getProfiles(missingProfileIds))

                        val recordingIds = recordings.map { it.id }
                        val tagsByRecordingId = tagRepository.getTagsForRecordingIds(recordingIds)
                        val transcriptsByRecordingId = recordingRepository.getTranscripts(recordingIds)

                        recordings.map { recording ->
                            val tags = tagsByRecordingId[recording.id].orEmpty()
                            val transcript = transcriptsByRecordingId[recording.id]
                            val profile = recording.profileId?.let { profileCache[it] }
                            RecordingDisplayItem(
                                recording = recording,
                                tags = tags.toImmutableList(),
                                summary =
                                    transcript?.summary ?: transcript?.processedText?.take(120)
                                        ?: transcript?.rawText?.take(120),
                                profileName = profile?.name,
                                profileIcon = profile?.icon,
                            )
                        }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        val displayItems: StateFlow<List<RecordingDisplayItem>> =
            combine(
                allDisplayItems,
                _listFilter,
            ) { items, filter ->
                if (filter == ListFilterMode.ALL.name) {
                    items
                } else {
                    items.filter { isProcessingOrStuckStatus(it.recording.status) }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        val stuckCount: StateFlow<Int> =
            allDisplayItems
                .map { items ->
                    items.count { item ->
                        item.recording.status == RecordingStatus.PENDING_TRANSCRIPTION ||
                            item.recording.status == RecordingStatus.ENHANCING
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

        /** Quick stats derived from recordings */
        val stats: StateFlow<HomeStats> =
            recordingRepository
                .getAllRecordings()
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

        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

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

        /**
         * Toggle recording on/off.
         */
        fun toggleRecording(profileId: UUID? = null) {
            val result = recordingManager.toggleRecording(RecordingOrigin.APP, profileId)

            if (result is RecordingStartResult.AlreadyRecording) {
                val originText =
                    when (result.currentOrigin) {
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
        fun deleteRecording(recording: Recording) {
            viewModelScope.launch {
                try {
                    // Step 1: Delete from database FIRST (the critical operation)
                    // Transcript is cascade-deleted via ForeignKey constraint
                    recordingRepository.delete(recording)

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
            recording: Recording,
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
                                appendLine(transcript.processedText ?: transcript.rawText)
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
        fun retryTranscription(recording: Recording) {
            viewModelScope.launch {
                if (recording.status == RecordingStatus.FAILED) {
                    transcriptionQueueManager.retry(recording.id)
                    _errorMessage.value = "Re-queued for transcription"
                }
            }
        }

        fun recoverStuckItem(recording: Recording) {
            viewModelScope.launch {
                val result =
                    when (recording.status) {
                        RecordingStatus.PENDING_TRANSCRIPTION -> {
                            transcriptionQueueManager.recoverPendingTranscription(recording.id)
                        }

                        RecordingStatus.ENHANCING -> {
                            transcriptionQueueManager.recoverEnhancing(recording.id)
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
                val recoveredCount = transcriptionQueueManager.recoverStuckRecordings()
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
        fun generateTitle(recording: Recording) {
            viewModelScope.launch {
                val transcript = recordingRepository.getTranscript(recording.id)
                if (transcript == null) {
                    _errorMessage.value = "No transcript available for title generation"
                    return@launch
                }

                _errorMessage.value = "Generating title..."

                val text = transcript.processedText ?: transcript.rawText
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
        fun generateSummary(recording: Recording) {
            viewModelScope.launch {
                val transcript = recordingRepository.getTranscript(recording.id)
                if (transcript == null) {
                    _errorMessage.value = "No transcript available for summary generation"
                    return@launch
                }

                _errorMessage.value = "Generating summary..."

                val text = transcript.processedText ?: transcript.rawText
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
        fun importAudio(uri: Uri, context: Context) {
            _isImporting.value = true
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val outputDir = File(context.filesDir, "recordings").apply { mkdirs() }
                    val outputFile = File(outputDir, "imported_${System.currentTimeMillis()}.m4a")
                    
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            outputFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }

                        val durationMs = try {
                            val mmr = MediaMetadataRetriever()
                            try {
                                mmr.setDataSource(context, uri)
                                val durationStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                durationStr?.toLongOrNull() ?: 0L
                            } finally {
                                mmr.release()
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            0L
                        }

                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            val recording = dev.chirpboard.app.data.entity.Recording(
                                title = "Imported Audio",
                                audioPath = outputFile.absolutePath,
                                source = dev.chirpboard.app.data.model.RecordingSource.IMPORTED,
                                durationMs = durationMs
                            )

                            recordingRepository.insert(recording)
                            transcriptionQueueManager.enqueue(recording.id, UUID.randomUUID().toString())
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            if (outputFile.exists()) {
                                outputFile.delete()
                            }
                        }
                        throw e
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
