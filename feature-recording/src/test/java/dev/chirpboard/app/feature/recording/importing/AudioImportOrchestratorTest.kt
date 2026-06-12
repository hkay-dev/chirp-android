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
import org.junit.Assert.assertEquals
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
    private lateinit var orchestrator: AudioImportOrchestrator

    private lateinit var tempDir: File
    private lateinit var uri: Uri

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        contentResolver = mockk(relaxed = true)
        recordingRepository = mockk(relaxed = true)
        transcriptionQueueManager = mockk(relaxed = true)
        uri = mockk(relaxed = true)
        tempDir = createTempDir(prefix = "audio-import-test")

        every { context.filesDir } returns tempDir
        every { context.contentResolver } returns contentResolver
        every { contentResolver.getType(uri) } returns "audio/mpeg"

        orchestrator = AudioImportOrchestrator(context, recordingRepository, transcriptionQueueManager)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `successful import copies the stream creates an IMPORTED recording and queues it`() =
        runTest {
            // TST-006: the success contract — byte-for-byte copy into recordings/, an
            // IMPORTED row pointing at that file, a transcription enqueue for that exact
            // recording id, and a SavedAndQueued result carrying the same id.
            val recordingId = UUID.randomUUID()
            val audioBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
            var capturedAudioPath: String? = null

            every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(audioBytes)
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

            val result = orchestrator.import(uri)

            assertTrue(result is AudioImportResult.SavedAndQueued)
            assertEquals(recordingId, (result as AudioImportResult.SavedAndQueued).recordingId)
            val copiedFile = File(capturedAudioPath!!)
            assertEquals(File(tempDir, "recordings"), copiedFile.parentFile)
            assertTrue(audioBytes.contentEquals(copiedFile.readBytes()))
            coVerify(exactly = 1) { transcriptionQueueManager.enqueue(recordingId, any()) }
            coVerify(exactly = 0) { transcriptionQueueManager.markPendingForQueueRecovery(any(), any(), any()) }
        }

    @Test
    fun `import file extension resolves from the resolver mime subtype`() =
        runTest {
            every { contentResolver.getType(uri) } returns "audio/x-wav"
            val capturedPath = importAndCapturePath()

            // SEC-9 sanitization strips the '-' from the subtype; no path separators survive.
            assertTrue(capturedPath.endsWith(".xwav"))
        }

    @Test
    fun `import file extension falls back to the uri segment then the default`() =
        runTest {
            // No MIME type: the (sanitized, lowercased) uri extension wins.
            every { contentResolver.getType(uri) } returns null
            every { uri.lastPathSegment } returns "Voice Memo.MP3"
            assertTrue(importAndCapturePath().endsWith(".mp3"))

            // Nothing usable anywhere: the m4a default applies.
            every { uri.lastPathSegment } returns null
            assertTrue(importAndCapturePath().endsWith(".m4a"))
        }

    private suspend fun importAndCapturePath(): String {
        var capturedAudioPath: String? = null
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream("audio-data".toByteArray())
        coEvery {
            recordingRepository.createRecording(any(), any(), RecordingSource.IMPORTED, any(), any())
        } answers {
            capturedAudioPath = invocation.args[1] as String
            Recording(
                id = UUID.randomUUID(),
                title = invocation.args[0] as String,
                audioPath = capturedAudioPath!!,
                source = RecordingSource.IMPORTED,
                durationMs = invocation.args[4] as Long,
            )
        }

        val result = orchestrator.import(uri)

        assertTrue(result is AudioImportResult.SavedAndQueued)
        return capturedAudioPath!!
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
