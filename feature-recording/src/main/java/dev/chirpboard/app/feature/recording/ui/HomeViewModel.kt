package dev.chirpboard.app.feature.recording.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStartResult
import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.TagRepository
import dev.chirpboard.app.feature.recording.RecordingManager
import dev.chirpboard.app.feature.llm.client.LlmClient
import dev.chirpboard.app.feature.transcription.ManualRecoveryResult
import dev.chirpboard.app.feature.transcription.TranscriptionQueueManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * UI model for a recording with all its associated data pre-loaded.
 */
data class RecordingDisplayItem(
    val recording: Recording,
    val tags: List<Tag> = emptyList(),
    val summary: String? = null,
    val profileName: String? = null,
    val profileIcon: String? = null
)

/**
 * Quick stats for the home screen header.
 */
data class HomeStats(
    val totalRecordings: Int = 0,
    val totalDurationMs: Long = 0L,
    val completedCount: Int = 0,
    val processingCount: Int = 0
)

enum class ListFilterMode {
    ALL,
    PROCESSING
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val recordingManager: RecordingManager,
    private val tagRepository: TagRepository,
    private val profileRepository: ProfileRepository,
    private val transcriptionQueueManager: TranscriptionQueueManager,
    private val llmClient: LlmClient,
    @ApplicationContext private val context: Context
) : ViewModel() {

    /** Search query */
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _listFilter = MutableStateFlow(ListFilterMode.ALL)
    val listFilter: StateFlow<ListFilterMode> = _listFilter.asStateFlow()

    /** Cached profiles for fast lookup */
    private val profileCache = mutableMapOf<UUID, Profile?>()
    
    /** All recordings based on search, enriched with tags/summary/profile */
    private val allDisplayItems: StateFlow<List<RecordingDisplayItem>> = _searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) {
                recordingRepository.getAllRecordings()
            } else {
                recordingRepository.searchRecordings(query)
            }
        }
        .map { recordings ->
            withContext(Dispatchers.IO) {
                // Pre-fetch all profiles IN PARALLEL (not sequentially)
                // This prevents blocking when displaying many recordings with different profiles
                val profileIds = recordings.mapNotNull { it.profileId }.distinct()
                profileIds.map { id ->
                    async {
                        if (!profileCache.containsKey(id)) {
                            profileCache[id] = profileRepository.getProfile(id)
                        }
                    }
                }.awaitAll()
                
                // Load all recordings in parallel for faster enrichment
                recordings.map { recording ->
                    async {
                        val tags = tagRepository.getTagsForRecordingList(recording.id)
                        val transcript = recordingRepository.getTranscript(recording.id)
                        val profile = recording.profileId?.let { profileCache[it] }
                        RecordingDisplayItem(
                            recording = recording,
                            tags = tags,
                            summary = transcript?.summary ?: transcript?.processedText?.take(120)
                                ?: transcript?.rawText?.take(120),
                            profileName = profile?.name,
                            profileIcon = profile?.icon
                        )
                    }
                }.awaitAll()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val displayItems: StateFlow<List<RecordingDisplayItem>> = combine(
        allDisplayItems,
        _listFilter
    ) { items, filter ->
        if (filter == ListFilterMode.ALL) {
            items
        } else {
            items.filter { isProcessingOrStuckStatus(it.recording.status) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val stuckCount: StateFlow<Int> = allDisplayItems
        .map { items ->
            items.count { item ->
                item.recording.status == RecordingStatus.PENDING_TRANSCRIPTION ||
                    item.recording.status == RecordingStatus.ENHANCING
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Quick stats derived from recordings */
    val stats: StateFlow<HomeStats> = recordingRepository
        .getAllRecordings()
        .map { recordings ->
            HomeStats(
                totalRecordings = recordings.size,
                totalDurationMs = recordings.sumOf { it.durationMs },
                completedCount = recordings.count {
                    it.status == dev.chirpboard.app.data.model.RecordingStatus.COMPLETED
                },
                processingCount = recordings.count {
                    it.status in listOf(
                        dev.chirpboard.app.data.model.RecordingStatus.TRANSCRIBING,
                        dev.chirpboard.app.data.model.RecordingStatus.ENHANCING,
                        dev.chirpboard.app.data.model.RecordingStatus.PENDING_TRANSCRIPTION,
                        dev.chirpboard.app.data.model.RecordingStatus.PENDING_ENHANCEMENT
                    )
                }
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeStats())

    /** Current recording state */
    val recordingState: StateFlow<RecordingState> = recordingManager.state
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** Update search query */
    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun onProcessingClick() {
        _listFilter.value = if (_listFilter.value == ListFilterMode.PROCESSING) {
            ListFilterMode.ALL
        } else {
            ListFilterMode.PROCESSING
        }
    }
    
    /**
     * Toggle recording on/off.
     */
    fun toggleRecording(profileId: UUID? = null) {
        val result = recordingManager.toggleRecording(RecordingOrigin.APP, profileId)
        
        if (result is RecordingStartResult.AlreadyRecording) {
            val originText = when (result.currentOrigin) {
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
                        // File deletion is non-fatal - log and continue
                        Log.w(TAG, "Error deleting audio file: ${recording.audioPath}", e)
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete recording: ${recording.id}", e)
                _errorMessage.value = "Failed to delete recording"
            }
        }
    }
    
    companion object {
        private const val TAG = "HomeViewModel"
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
    fun shareRecording(recording: Recording) {
        viewModelScope.launch {
            val file = File(recording.audioPath)
            
            // Check file existence on IO dispatcher
            val exists = withContext(Dispatchers.IO) { file.exists() }
            if (!exists) {
                _errorMessage.value = "Audio file not found"
                return@launch
            }
            
            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    file
                )
                
                // Get transcript text if available
                val transcript = recordingRepository.getTranscript(recording.id)
                val text = if (transcript != null) {
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
                
                val intent = Intent(Intent.ACTION_SEND).apply {
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
                    }
                )
            } catch (e: Exception) {
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
            val result = when (recording.status) {
                RecordingStatus.PENDING_TRANSCRIPTION -> {
                    transcriptionQueueManager.recoverPendingTranscription(recording.id)
                }

                RecordingStatus.ENHANCING -> {
                    transcriptionQueueManager.recoverEnhancing(recording.id)
                }

                else -> ManualRecoveryResult.NOT_RECOVERABLE_STATE
            }

            _errorMessage.value = when (result) {
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
            _errorMessage.value = if (recoveredCount > 0) {
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
                }
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
                }
            )
        }
    }
}

internal fun isProcessingOrStuckStatus(status: RecordingStatus): Boolean {
    return status in setOf(
        RecordingStatus.TRANSCRIBING,
        RecordingStatus.ENHANCING,
        RecordingStatus.PENDING_TRANSCRIPTION,
        RecordingStatus.PENDING_ENHANCEMENT
    )
}
