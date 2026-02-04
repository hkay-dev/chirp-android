package dev.chirpboard.app.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.TagRepository
import dev.chirpboard.app.feature.llm.settings.LlmPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import kotlin.random.Random

data class DevMenuUiState(
    val apiKeyInput: String = "",
    val hasApiKey: Boolean = false,
    val isGenerating: Boolean = false,
    val message: String? = null
)

@HiltViewModel
class DevMenuViewModel @Inject constructor(
    private val llmPreferences: LlmPreferences,
    private val recordingRepository: RecordingRepository,
    private val tagRepository: TagRepository,
    private val profileRepository: ProfileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DevMenuUiState())
    val uiState: StateFlow<DevMenuUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            llmPreferences.apiKey.collect { key ->
                _uiState.update { it.copy(hasApiKey = key != null) }
            }
        }
    }

    fun onApiKeyChange(key: String) {
        _uiState.update { it.copy(apiKeyInput = key) }
    }

    fun saveApiKey() {
        viewModelScope.launch {
            llmPreferences.setApiKey(_uiState.value.apiKeyInput)
            _uiState.update { it.copy(message = "API key saved", apiKeyInput = "") }
        }
    }

    fun clearApiKey() {
        viewModelScope.launch {
            llmPreferences.clearApiKey()
            _uiState.update { it.copy(message = "API key cleared") }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    // ===== Dummy Data Generation =====

    private val sampleTitles = listOf(
        "Meeting notes with the team",
        "Grocery list for the week",
        "Voice memo about project ideas",
        "Quick reminder for tomorrow",
        "Brainstorming session",
        "Call with John about the presentation",
        "Lecture notes from class",
        "Podcast episode summary",
        "Interview preparation notes",
        "Daily journal entry",
        "Book review thoughts",
        "Recipe instructions",
        "Travel planning ideas",
        "Workout routine notes",
        "Music practice session"
    )

    private val sampleSummaries = listOf(
        "Discussed Q4 roadmap priorities, assigned action items to team leads, and set deadline for next review on Friday.",
        "Need to pick up milk, eggs, bread, chicken, vegetables for stir fry, and snacks for the kids' lunches.",
        "Three potential features to explore: voice commands for smart home, offline mode improvements, and collaborative editing.",
        "Remember to call the dentist, submit expense report, and follow up on the apartment lease renewal.",
        "Generated 15 new product name ideas. Top contenders: Chirp Pro, VoiceFlow, and AudioNote Plus.",
        "John will handle the slides, I'll prepare the demo. Presentation is at 3pm in Conference Room B.",
        "Key concepts covered: machine learning basics, supervised vs unsupervised learning, and neural network fundamentals.",
        "Great episode on productivity habits. Main takeaways: time blocking, two-minute rule, and weekly reviews.",
        "Practice answers for common questions. Focus on STAR method for behavioral questions.",
        "Feeling grateful today. Made progress on the project and had a nice dinner with family.",
        null, null, null // Some recordings have no summary
    )

    private val sampleRawTexts = listOf(
        "So today we talked about the quarterly goals and I think the main thing is that we need to focus on user retention...",
        "Okay so for groceries I need to get um milk, definitely eggs, probably some bread if they have the whole wheat kind...",
        "I've been thinking about this feature where users could just say hey chirp and it would start recording automatically...",
        "Note to self: tomorrow morning call the dentist's office, they close at noon on Fridays so do it early...",
        "What if we called it something like VoiceFlow? Or maybe AudioNote? Actually Chirp Pro sounds pretty good too..."
    )

    private val sampleErrorMessages = listOf(
        "Transcription failed: Model not available",
        "Network error during processing",
        "Audio file corrupted or unreadable",
        "LLM API rate limit exceeded",
        "Insufficient storage space"
    )

    fun generateDummyRecordings(count: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true) }
            
            try {
                // Get existing tags and profiles to reference
                val tags = tagRepository.getAllTagsList()
                val profiles = profileRepository.getAllProfilesList()
                
                repeat(count) { i ->
                    val status = RecordingStatus.entries.random()
                    val recording = createDummyRecording(
                        status = status,
                        profile = profiles.randomOrNull()
                    )
                    recordingRepository.insert(recording)
                    
                    // Add transcript for completed/enhancing recordings
                    if (status in listOf(RecordingStatus.COMPLETED, RecordingStatus.ENHANCING, RecordingStatus.PENDING_ENHANCEMENT)) {
                        val transcript = createDummyTranscript(recording.id)
                        recordingRepository.saveTranscript(transcript)
                    }
                    
                    // Randomly assign tags
                    if (tags.isNotEmpty() && Random.nextBoolean()) {
                        val selectedTags = tags.shuffled().take(Random.nextInt(1, minOf(4, tags.size + 1)))
                        tagRepository.setTagsForRecording(recording.id, selectedTags.map { it.id })
                    }
                }
                
                _uiState.update { it.copy(message = "Added $count dummy recordings") }
            } catch (e: Exception) {
                _uiState.update { it.copy(message = "Error: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isGenerating = false) }
            }
        }
    }

    fun addTranscribingRecording() {
        viewModelScope.launch {
            val recording = createDummyRecording(status = RecordingStatus.TRANSCRIBING)
            recordingRepository.insert(recording)
            _uiState.update { it.copy(message = "Added transcribing recording") }
        }
    }

    fun addEnhancingRecording() {
        viewModelScope.launch {
            val recording = createDummyRecording(status = RecordingStatus.ENHANCING)
            recordingRepository.insert(recording)
            val transcript = createDummyTranscript(recording.id, hasProcessedText = false)
            recordingRepository.saveTranscript(transcript)
            _uiState.update { it.copy(message = "Added enhancing recording") }
        }
    }

    fun addPendingRecording() {
        viewModelScope.launch {
            val recording = createDummyRecording(status = RecordingStatus.PENDING_TRANSCRIPTION)
            recordingRepository.insert(recording)
            _uiState.update { it.copy(message = "Added pending recording") }
        }
    }

    fun addFailedRecording() {
        viewModelScope.launch {
            val recording = createDummyRecording(
                status = RecordingStatus.FAILED,
                errorMessage = sampleErrorMessages.random()
            )
            recordingRepository.insert(recording)
            _uiState.update { it.copy(message = "Added failed recording") }
        }
    }

    fun addCompletedWithSummary() {
        viewModelScope.launch {
            val recording = createDummyRecording(status = RecordingStatus.COMPLETED)
            recordingRepository.insert(recording)
            val transcript = createDummyTranscript(recording.id, forceSummary = true)
            recordingRepository.saveTranscript(transcript)
            _uiState.update { it.copy(message = "Added completed recording with summary") }
        }
    }

    fun addCompletedWithTags() {
        viewModelScope.launch {
            // Ensure we have some tags
            var tags = tagRepository.getAllTagsList()
            if (tags.isEmpty()) {
                // Create some default tags
                val defaultTags = listOf(
                    Tag(name = "Work", color = "#4285F4"),
                    Tag(name = "Personal", color = "#34A853"),
                    Tag(name = "Important", color = "#EA4335"),
                    Tag(name = "Ideas", color = "#FBBC05")
                )
                defaultTags.forEach { tagRepository.insert(it) }
                tags = tagRepository.getAllTagsList()
            }
            
            val recording = createDummyRecording(status = RecordingStatus.COMPLETED)
            recordingRepository.insert(recording)
            val transcript = createDummyTranscript(recording.id)
            recordingRepository.saveTranscript(transcript)
            
            // Assign 2-3 random tags
            val selectedTags = tags.shuffled().take(Random.nextInt(2, minOf(4, tags.size + 1)))
            tagRepository.setTagsForRecording(recording.id, selectedTags.map { it.id })
            
            _uiState.update { it.copy(message = "Added recording with ${selectedTags.size} tags") }
        }
    }

    fun deleteAllRecordings() {
        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true) }
            try {
                recordingRepository.deleteAll()
                _uiState.update { it.copy(message = "All recordings deleted") }
            } catch (e: Exception) {
                _uiState.update { it.copy(message = "Error: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isGenerating = false) }
            }
        }
    }

    private fun createDummyRecording(
        status: RecordingStatus,
        profile: Profile? = null,
        errorMessage: String? = null
    ): Recording {
        val id = UUID.randomUUID()
        val createdAt = Date(System.currentTimeMillis() - Random.nextLong(0, 7 * 24 * 60 * 60 * 1000)) // Random time in last 7 days
        val durationMs = Random.nextLong(5000, 10 * 60 * 1000) // 5 seconds to 10 minutes
        
        return Recording(
            id = id,
            title = sampleTitles.random(),
            audioPath = "/dev/null/dummy_${id}.m4a", // Non-existent path
            status = status,
            source = RecordingSource.entries.random(),
            profileId = profile?.id,
            createdAt = createdAt,
            durationMs = durationMs,
            errorMessage = errorMessage
        )
    }

    private fun createDummyTranscript(
        recordingId: UUID,
        hasProcessedText: Boolean = Random.nextBoolean(),
        forceSummary: Boolean = false
    ): Transcript {
        val rawText = sampleRawTexts.random()
        val summary = if (forceSummary) sampleSummaries.filterNotNull().random() else sampleSummaries.random()
        
        return Transcript(
            id = UUID.randomUUID(),
            recordingId = recordingId,
            rawText = rawText,
            processedText = if (hasProcessedText) rawText.replace("um ", "").replace("uh ", "") else null,
            processingMode = if (hasProcessedText) "cleanup" else null,
            summary = summary,
            createdAt = Date(),
            updatedAt = Date()
        )
    }
}
