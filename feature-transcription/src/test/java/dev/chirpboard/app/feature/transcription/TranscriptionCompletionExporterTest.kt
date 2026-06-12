package dev.chirpboard.app.feature.transcription

import dev.chirpboard.app.core.export.TranscriptExportOutcome
import dev.chirpboard.app.core.export.TranscriptExportPort
import dev.chirpboard.app.core.export.TranscriptExportRecording
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.TagRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class TranscriptionCompletionExporterTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    private lateinit var recordingRepository: RecordingRepository
    private lateinit var profileRepository: ProfileRepository
    private lateinit var tagRepository: TagRepository
    private lateinit var exportPort: TranscriptExportPort
    private lateinit var exporter: TranscriptionCompletionExporter

    private val recordingId = UUID.randomUUID()
    private val profileId = UUID.randomUUID()

    @Before
    fun setup() {
        recordingRepository = mockk(relaxed = true)
        profileRepository = mockk(relaxed = true)
        tagRepository = mockk(relaxed = true)
        exportPort = mockk()
        coEvery {
            exportPort.exportIfEnabled(any(), any(), any(), any(), any())
        } returns Result.success(TranscriptExportOutcome(exportedUri = "content://vault/note.md"))
        coEvery { tagRepository.getTagsForRecordingList(any()) } returns emptyList()
        coEvery { profileRepository.getProfile(any()) } returns null
        exporter =
            TranscriptionCompletionExporter(
                recordingRepository = recordingRepository,
                profileRepository = profileRepository,
                tagRepository = tagRepository,
                transcriptExportPort = exportPort,
            )
    }

    @Test
    fun `exports completed recording with final text summary tags and profile flag`() = runTest {
        coEvery { recordingRepository.getRecording(recordingId) } returns
            recording(status = RecordingStatus.COMPLETED, profileId = profileId)
        coEvery { recordingRepository.getTranscript(recordingId) } returns
            transcript(processedText = "polished text", summary = "the summary")
        coEvery { profileRepository.getProfile(profileId) } returns
            Profile(id = profileId, name = "Work", autoExportToObsidian = true)
        coEvery { tagRepository.getTagsForRecordingList(recordingId) } returns
            listOf(Tag(name = "meeting"), Tag(name = "work"))

        exporter.exportIfCompleted(recordingId)

        val recordingSlot = slot<TranscriptExportRecording>()
        coVerify(exactly = 1) {
            exportPort.exportIfEnabled(
                recording = capture(recordingSlot),
                transcript = "polished text",
                summary = "the summary",
                tags = listOf("meeting", "work"),
                requestedByProfile = true,
            )
        }
        assertEquals(recordingId, recordingSlot.captured.id)
        assertEquals("app", recordingSlot.captured.sourceName)
    }

    @Test
    fun `skips export when recording is not in terminal completed state`() = runTest {
        coEvery { recordingRepository.getRecording(recordingId) } returns
            recording(status = RecordingStatus.FAILED, profileId = null)

        exporter.exportIfCompleted(recordingId)

        coVerify(exactly = 0) { exportPort.exportIfEnabled(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `falls back to raw text when processed text is blank`() = runTest {
        coEvery { recordingRepository.getRecording(recordingId) } returns
            recording(status = RecordingStatus.COMPLETED, profileId = null)
        coEvery { recordingRepository.getTranscript(recordingId) } returns
            transcript(processedText = " ", summary = null)

        exporter.exportIfCompleted(recordingId)

        coVerify(exactly = 1) {
            exportPort.exportIfEnabled(any(), "raw text", null, emptyList(), false)
        }
    }

    @Test
    fun `export failure never throws out of the exporter`() = runTest {
        coEvery { recordingRepository.getRecording(recordingId) } returns
            recording(status = RecordingStatus.COMPLETED, profileId = null)
        coEvery { recordingRepository.getTranscript(recordingId) } returns
            transcript(processedText = "text", summary = null)
        coEvery {
            exportPort.exportIfEnabled(any(), any(), any(), any(), any())
        } returns Result.failure(SecurityException("vault gone"))

        exporter.exportIfCompleted(recordingId)

        // Reaching here without an exception is the assertion.
        assertTrue(true)
    }

    private fun recording(
        status: RecordingStatus,
        profileId: UUID?,
    ): Recording =
        Recording(
            id = recordingId,
            title = "Title",
            audioPath = "/audio/file.m4a",
            status = status,
            source = RecordingSource.APP,
            profileId = profileId,
            durationMs = 1_000L,
        )

    private fun transcript(
        processedText: String?,
        summary: String?,
    ): Transcript =
        Transcript(
            recordingId = recordingId,
            rawText = "raw text",
            processedText = processedText,
            processingMode = "word_replacement",
            summary = summary,
        )
}
