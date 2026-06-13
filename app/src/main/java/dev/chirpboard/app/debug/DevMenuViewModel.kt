package dev.chirpboard.app.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.entity.WordReplacement
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.TagRepository
import dev.chirpboard.app.data.repository.WordReplacementRepository
import dev.chirpboard.app.feature.llm.settings.LlmPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val profileRepository: ProfileRepository,
    private val wordReplacementRepository: WordReplacementRepository,
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
                if (e is kotlinx.coroutines.CancellationException) throw e
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
                if (e is kotlinx.coroutines.CancellationException) throw e
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

    // ===== Fuzz / Stress Seeding (debug only) =====
    // Adversarial inputs to surface rendering, layout, bidi, DB and pipeline edge cases.

    private val fuzzTitles = listOf(
        "🎉🎊 Quarterly planning 🥳 — Q4 OKRs & 💰 budget",
        "اجتماع الفريق غدًا 10:00 صباحًا — ملخص أسبوعي",          // Arabic (RTL) + Latin digits (bidi)
        "פגישת צוות — סיכום שבועי 📌",                            // Hebrew (RTL)
        "会議のメモ 📝 プロジェクト計画について話し合う",            // Japanese + emoji
        "Combining maŕk̃s and źálgo tĕxt",
        "Zero​width‌spaces‍hidden﻿inside",
        "Line one\nLine two\nLine three\twith a tab",
        "**bold** _italic_ `code` ~~strike~~ # H1 > quote",
        "<script>alert('xss')</script> & <b>html</b> &amp; entities",
        "Robert'); DROP TABLE recordings;-- with \"quotes\" and 'apostrophes'",
        "format chars 100% %s %d %1\$s {0} {{mustache}} \\backslash\\",
        "Mixed script: English العربية 中文 русский 😀 end",
        "x",
        "😀😀😀😀😀😀😀😀😀😀😀😀",
        "   leading and trailing whitespace   ",
        "emoji-only 🔥💯✅⚠️🎤🗣️📌📤",
        "naïve café résumé Zürich — accents & ligatures ﬀ ﬁ",
        "👨‍👩‍👧‍👦 family ZWJ and 🏳️‍🌈 flag sequences",
    )

    private val fuzzNotes = listOf(
        "- [ ] todo one\n- [x] done two\n- bullet three\n\n1. ordered\n2. list",
        "Run-on note. " + "This sentence keeps going to test wrapping and scrolling without a natural break. ".repeat(12),
        "emoji 🎤📝✅ with RTL العربية mixed and a URL https://example.com/path?q=1&x=2",
        "```kotlin\nfun answer() = 42\n```\nfenced code block inside a note",
        "| col1 | col2 |\n|---|---|\n| a | b |\na markdown table",
        "​zero-width and ́combininǵ marks in a noté",
    )

    private val fuzzTagNames = listOf(
        "Work", "Personal", "Important", "Ideas", "🔥 Urgent", "📌 Pinned",
        "العمل", "重要", "follow-up", "a-very-long-tag-name-that-overflows-the-chip-layout-badly",
        "café", "x", "😀", "naïve", "ALLCAPS",
    )

    private val fuzzColors = listOf("#4285F4", "#34A853", "#EA4335", "#FBBC05", null, "not-a-color", "#FFF", "")

    private val fuzzReplacementPairs = listOf(
        "teh" to "the",
        "u" to "you",
        "gonna" to "going to",
        "chirp" to "Chirp",
        "a.*b" to "[regex-meta-literal]",
        "café" to "coffee",
        "😀" to ":)",
        "VERYLONGORIGINAL".repeat(4) to "short",
        "  spaced  " to "trimmed",
        "DROP TABLE" to "[redacted]",
    )

    private val transcriptStatuses =
        setOf(RecordingStatus.COMPLETED, RecordingStatus.ENHANCING, RecordingStatus.PENDING_ENHANCEMENT)

    /** Seeds a large adversarial dataset through the real repositories. */
    fun fuzzSeed(recordingCount: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true, message = "Fuzz seeding…") }
            try {
                val profiles = seedFuzzProfiles()
                val tags = seedFuzzTags()
                seedFuzzWordReplacements()
                seedFuzzRecordings(recordingCount, profiles, tags)
                _uiState.update {
                    it.copy(
                        message = "Seeded $recordingCount recordings, ${tags.size} tags, " +
                            "${fuzzReplacementPairs.size} replacements, ${profiles.size} profiles",
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(message = "Fuzz error: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isGenerating = false) }
            }
        }
    }

    private suspend fun seedFuzzProfiles(): List<Profile> {
        listOf(
            Profile(name = "Meetings", icon = "📋", autoTranscribe = true, autoTitle = true, autoSummary = true, isQuickStartPinned = true, sortOrder = 0),
            Profile(name = "Quick note ⚡", icon = "⚡", autoTranscribe = true, isQuickStartPinned = true, sortOrder = 1),
            Profile(name = "اجتماع", icon = "🕌", autoTranscribe = true, autoTitle = true, sortOrder = 2),
            Profile(name = "Manual (no auto)", icon = null, autoTranscribe = false, sortOrder = 3),
            Profile(name = "Obsidian export 📤", icon = "📤", autoTranscribe = true, autoTitle = true, autoSummary = true, autoExportToObsidian = true, sortOrder = 4),
            Profile(name = "a-really-long-profile-name-to-test-row-and-chip-overflow", icon = "😀", autoTranscribe = true, autoTitle = true, autoSummary = true, sortOrder = 5),
        ).forEach { profileRepository.insert(it) }
        return profileRepository.getAllProfilesList()
    }

    private suspend fun seedFuzzTags(): List<Tag> {
        fuzzTagNames.forEachIndexed { i, name ->
            tagRepository.insert(Tag(name = name, color = fuzzColors[i % fuzzColors.size]))
        }
        return tagRepository.getAllTagsList()
    }

    private suspend fun seedFuzzWordReplacements() {
        fuzzReplacementPairs.forEachIndexed { i, pair ->
            wordReplacementRepository.insert(
                WordReplacement(
                    original = pair.first,
                    replacement = pair.second,
                    caseSensitive = i % 3 == 0,
                    enabled = i % 4 != 0,
                ),
            )
        }
    }

    private suspend fun seedFuzzRecordings(
        count: Int,
        profiles: List<Profile>,
        tags: List<Tag>,
    ) {
        val statuses = RecordingStatus.entries
        val sources = RecordingSource.entries
        val now = System.currentTimeMillis()
        val spreadMs = 400L * 24 * 60 * 60 * 1000 // ~13 months back-to-front
        val durations = listOf(0L, 1_000L, 30_000L, 5 * 60_000L, 60 * 60_000L, 5L * 60 * 60_000L)
        val titles = sampleTitles + fuzzTitles
        repeat(count) { i ->
            val id = UUID.randomUUID()
            val status = if (i % 3 == 0) RecordingStatus.COMPLETED else statuses[i % statuses.size]
            val baseTitle = titles[i % titles.size]
            val title = if (i % 7 == 0) "$baseTitle #$i" else baseTitle
            val recording = Recording(
                id = id,
                title = title,
                audioPath = "/dev/null/fuzz_$id.m4a",
                status = status,
                source = sources[i % sources.size],
                profileId = profiles.randomOrNull()?.id,
                createdAt = Date(now - i.toLong() * spreadMs / maxOf(count, 1)),
                durationMs = durations[i % durations.size],
                errorMessage = if (status == RecordingStatus.FAILED) sampleErrorMessages.random() else null,
                notes = if (i % 3 == 0) fuzzNotes[i % fuzzNotes.size] else null,
            )
            recordingRepository.insert(recording)
            if (status in transcriptStatuses) {
                recordingRepository.saveTranscript(createDummyTranscript(id))
            }
            if (tags.isNotEmpty() && i % 5 != 0) {
                val pick = tags.shuffled().take(Random.nextInt(0, minOf(6, tags.size + 1)))
                if (pick.isNotEmpty()) tagRepository.setTagsForRecording(id, pick.map { it.id })
            }
            if (i % 100 == 99) {
                _uiState.update { it.copy(message = "Seeding… ${i + 1}/$count") }
            }
        }
    }

    /** Wipes recordings, tags, word replacements and profiles (debug cleanup). */
    fun clearAllData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true, message = "Clearing all data…") }
            try {
                recordingRepository.deleteAll()
                tagRepository.getAllTagsList().forEach { tagRepository.delete(it) }
                wordReplacementRepository.getAllReplacementsList().forEach { wordReplacementRepository.delete(it) }
                profileRepository.getAllProfilesList().forEach { profileRepository.delete(it) }
                _uiState.update { it.copy(message = "Cleared recordings, tags, replacements, profiles") }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(message = "Clear error: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isGenerating = false) }
            }
        }
    }
}
