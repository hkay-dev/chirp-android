package dev.chirpboard.app.feature.recording.importing

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.core.transcription.TranscriptionRecovery
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class AudioImportOrchestratorTest {
    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var transcriptionQueueManager: TranscriptionRecovery
    private lateinit var metadataReader: ImportedAudioMetadataReader
    private lateinit var orchestrator: AudioImportOrchestrator

    private lateinit var tempDir: File
    private lateinit var uri: Uri

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        contentResolver = mockk(relaxed = true)
        recordingRepository = mockk(relaxed = true)
        transcriptionQueueManager = mockk(relaxed = true)
        metadataReader = mockk(relaxed = true)
        uri = mockk(relaxed = true)
        tempDir = createTempDir(prefix = "audio-import-test")

        every { context.filesDir } returns tempDir
        every { context.contentResolver } returns contentResolver
        every { contentResolver.getType(uri) } returns "audio/mpeg"
        every { metadataReader.readDurationMs(any()) } returns 1234L

        orchestrator = AudioImportOrchestrator(context, recordingRepository, transcriptionQueueManager, metadataReader)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `pre-persistence failure does not create a recording`() =
        runTest {
            every { contentResolver.openInputStream(uri) } returns null

            val result = orchestrator.import(uri)

            assertTrue(result is AudioImportResult.FailedBeforePersistence)
            coVerify(exactly = 0) { recordingRepository.createRecording(any(), any(), any(), any(), any()) }
            assertTrue(File(tempDir, "recordings").listFiles().isNullOrEmpty())
        }

    @Test
    fun `persistence failure cleans up the copied file`() =
        runTest {
            every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream("audio-data".toByteArray())
            coEvery { recordingRepository.createRecording(any(), any(), any(), any(), any()) } throws RuntimeException("db down")

            val result = orchestrator.import(uri)

            assertTrue(result is AudioImportResult.FailedBeforePersistence)
            assertTrue(File(tempDir, "recordings").listFiles().isNullOrEmpty())
        }

    @Test
    fun `queue handoff failure keeps the copied file and marks recovery`() =
        runTest {
            val recordingId = UUID.randomUUID()
            var capturedAudioPath: String? = null

            every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream("audio-data".toByteArray())
            coEvery {
                recordingRepository.createRecording(any(), any(), RecordingSource.IMPORTED, any(), any())
            } answers {
                capturedAudioPath = invocation.args[1] as String
                Recording(
                    id = recordingId,
                    title = invocation.args[0] as String,
                    audioPath = capturedAudioPath!!,
                    source = RecordingSource.IMPORTED,
                    durationMs = invocation.args[4] as Long,
                )
            }
            coEvery { transcriptionQueueManager.enqueue(recordingId, any()) } throws RuntimeException("enqueue failed")

            val result = orchestrator.import(uri)

            assertTrue(result is AudioImportResult.SavedPendingRecovery)
            assertTrue(capturedAudioPath != null)
            assertTrue(File(capturedAudioPath!!).exists())
            coVerify { transcriptionQueueManager.markPendingForQueueRecovery(recordingId, any(), any()) }
        }
}
