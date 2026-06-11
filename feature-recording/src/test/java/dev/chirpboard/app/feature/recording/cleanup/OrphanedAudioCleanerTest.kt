package dev.chirpboard.app.feature.recording.cleanup

import android.content.Context
import dev.chirpboard.app.core.audio.recorder.VoiceRecorder
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.recording.session.RecordingRecoveryProtectedPathsStore
import dev.chirpboard.app.feature.recording.session.RecordingSessionJournal
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class OrphanedAudioCleanerTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    private lateinit var context: Context
    private lateinit var repository: RecordingRepository
    private lateinit var journal: RecordingSessionJournal
    private lateinit var protectedPathsStore: RecordingRecoveryProtectedPathsStore
    private lateinit var cleaner: OrphanedAudioCleaner

    @Before
    fun setup() {
        val root = createTempDir("orphan-cleaner-test")
        val cacheRoot = createTempDir("orphan-cleaner-cache")
        context =
            mockk(relaxed = true) {
                every { filesDir } returns root
                every { cacheDir } returns cacheRoot
            }
        repository = mockk(relaxed = true)
        journal = RecordingSessionJournal(context)
        protectedPathsStore = mockk(relaxed = true)
        coEvery { protectedPathsStore.activeProtectedPaths() } returns emptySet()
        coEvery { protectedPathsStore.consumeExpiredPaths() } returns emptySet()
        cleaner = OrphanedAudioCleaner(context, repository, journal, protectedPathsStore)
    }

    private fun orphanMp3(ageMs: Long = 10 * 60 * 1000): File {
        val recordingsDir = File(context.filesDir, "recordings").apply { mkdirs() }
        return File(recordingsDir, "orphan_export.mp3").also { file ->
            file.writeText("fake mp3")
            file.setLastModified(System.currentTimeMillis() - ageMs)
        }
    }

    @Test
    fun cleanOrphanedFiles_skipsSafelistedSessionFile() =
        runTest {
            val recordingsDir = File(context.filesDir, "recordings").apply { mkdirs() }
            val file = File(recordingsDir, "recording_old.m4a")
            file.writeText("fake audio")
            file.setLastModified(System.currentTimeMillis() - 10 * 60 * 1000)

            journal.createSession(
                sessionId = UUID.randomUUID(),
                audioPath = file.absolutePath,
                origin = RecordingOrigin.APP,
                profileId = null,
                recordingId = UUID.randomUUID(),
                correlationId = "corr",
            )

            coEvery { repository.getAllAudioPaths() } returns emptyList()

            cleaner.cleanOrphanedFiles()

            assertTrue(file.exists())
        }

    @Test
    fun cleanOrphanedFiles_deletesUnreferencedMp3AfterGrace() =
        runTest {
            val file = orphanMp3()
            coEvery { repository.getAllAudioPaths() } returns emptyList()

            cleaner.cleanOrphanedFiles()

            assertFalse(file.exists())
        }

    @Test
    fun cleanOrphanedFiles_retainsReferencedMp3() =
        runTest {
            val file = orphanMp3()
            coEvery { repository.getAllAudioPaths() } returns listOf(file.absolutePath)

            cleaner.cleanOrphanedFiles()

            assertTrue(file.exists())
        }

    @Test
    fun cleanOrphanedFiles_skipsProtectedMp3() =
        runTest {
            val file = orphanMp3()
            coEvery { repository.getAllAudioPaths() } returns emptyList()
            coEvery { protectedPathsStore.activeProtectedPaths() } returns setOf(file.absolutePath)

            cleaner.cleanOrphanedFiles()

            assertTrue(file.exists())
        }

    @Test
    fun cleanOrphanedFiles_quarantinesExpiredProtectedRecoverableAudio() =
        runTest {
            val recordingsDir = File(context.filesDir, "recordings").apply { mkdirs() }
            val file =
                File(recordingsDir, "kept_recording.m4a").apply {
                    writeText("x".repeat(1024))
                    setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000)
                }
            coEvery { repository.getAllAudioPaths() } returns emptyList()
            coEvery { protectedPathsStore.consumeExpiredPaths() } returns setOf(file.absolutePath)

            cleaner.cleanOrphanedFiles()

            assertFalse(file.exists())
            val quarantined = File(recordingsDir, "${OrphanedAudioCleaner.QUARANTINE_DIR_NAME}/${file.name}")
            assertTrue(quarantined.exists())
        }

    @Test
    fun cleanOrphanedFiles_deletesExpiredProtectedAudioTooSmallToRecover() =
        runTest {
            val recordingsDir = File(context.filesDir, "recordings").apply { mkdirs() }
            val file =
                File(recordingsDir, "kept_stub.m4a").apply {
                    writeText("tiny")
                    setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000)
                }
            coEvery { repository.getAllAudioPaths() } returns emptyList()
            coEvery { protectedPathsStore.consumeExpiredPaths() } returns setOf(file.absolutePath)

            cleaner.cleanOrphanedFiles()

            assertFalse(file.exists())
            assertFalse(File(recordingsDir, "${OrphanedAudioCleaner.QUARANTINE_DIR_NAME}/${file.name}").exists())
        }

    @Test
    fun cleanOrphanedFiles_deletesUnreferencedCaptureDirectoryAfterGrace() =
        runTest {
            val captureDir =
                File(context.filesDir, "recordings/.capture/${UUID.randomUUID()}").apply {
                    mkdirs()
                }
            val segment =
                File(captureDir, "seg-000.m4a").apply {
                    writeText("orphan segment")
                    setLastModified(System.currentTimeMillis() - 10 * 60 * 1000)
                }
            captureDir.setLastModified(System.currentTimeMillis() - 10 * 60 * 1000)
            coEvery { repository.getAllAudioPaths() } returns emptyList()

            cleaner.cleanOrphanedFiles()

            assertFalse(segment.exists())
            assertFalse(captureDir.exists())
        }

    @Test
    fun cleanOrphanedFiles_quarantinesExpiredProtectedCaptureDirectory() =
        runTest {
            val sessionId = UUID.randomUUID()
            val recordingsDir = File(context.filesDir, "recordings").apply { mkdirs() }
            val captureDir = File(recordingsDir, ".capture/$sessionId").apply { mkdirs() }
            val segment =
                File(captureDir, "seg-000.m4a").apply {
                    writeText("x".repeat(1024))
                    setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000)
                }
            captureDir.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000)
            coEvery { repository.getAllAudioPaths() } returns emptyList()
            coEvery { protectedPathsStore.consumeExpiredPaths() } returns setOf(segment.absolutePath)

            cleaner.cleanOrphanedFiles()

            assertFalse(captureDir.exists())
            val quarantinedDir = File(recordingsDir, "${OrphanedAudioCleaner.QUARANTINE_DIR_NAME}/$sessionId")
            assertTrue(quarantinedDir.isDirectory)
            assertTrue(File(quarantinedDir, segment.name).exists())
        }

    @Test
    fun cleanOrphanedFiles_deletesExpiredProtectedCaptureDirectoryTooSmallToRecover() =
        runTest {
            val sessionId = UUID.randomUUID()
            val recordingsDir = File(context.filesDir, "recordings").apply { mkdirs() }
            val captureDir = File(recordingsDir, ".capture/$sessionId").apply { mkdirs() }
            val segment =
                File(captureDir, "seg-000.m4a").apply {
                    writeText("tiny")
                    setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000)
                }
            captureDir.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000)
            coEvery { repository.getAllAudioPaths() } returns emptyList()
            coEvery { protectedPathsStore.consumeExpiredPaths() } returns setOf(segment.absolutePath)

            cleaner.cleanOrphanedFiles()

            assertFalse(captureDir.exists())
            assertFalse(File(recordingsDir, "${OrphanedAudioCleaner.QUARANTINE_DIR_NAME}/$sessionId").exists())
        }

    @Test
    fun cleanOrphanedFiles_purgesStaleQuarantinedDirectory() =
        runTest {
            val recordingsDir = File(context.filesDir, "recordings").apply { mkdirs() }
            val quarantinedDir =
                File(recordingsDir, "${OrphanedAudioCleaner.QUARANTINE_DIR_NAME}/stale-session").apply { mkdirs() }
            File(quarantinedDir, "seg-000.m4a").writeText("old segment")
            quarantinedDir.setLastModified(System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000)
            coEvery { repository.getAllAudioPaths() } returns emptyList()

            cleaner.cleanOrphanedFiles()

            assertFalse(quarantinedDir.exists())
        }

    private fun dictationCapture(
        name: String,
        ageMs: Long,
    ): File {
        val captureDir =
            File(context.cacheDir, VoiceRecorder.KEYBOARD_CAPTURE_CACHE_DIR).apply { mkdirs() }
        return File(captureDir, name).also { file ->
            file.writeText("fake pcm")
            file.setLastModified(System.currentTimeMillis() - ageMs)
        }
    }

    @Test
    fun cleanOrphanedFiles_deletesStaleDictationCapture() =
        runTest {
            val file =
                dictationCapture(
                    name = "${VoiceRecorder.DICTATION_CAPTURE_FILE_PREFIX}stale${VoiceRecorder.DICTATION_CAPTURE_FILE_SUFFIX}",
                    ageMs = OrphanedAudioCleaner.DICTATION_CAPTURE_MAX_AGE_MS + 60 * 60 * 1000,
                )
            coEvery { repository.getAllAudioPaths() } returns emptyList()

            cleaner.cleanOrphanedFiles()

            assertFalse(file.exists())
        }

    @Test
    fun cleanOrphanedFiles_retainsFreshDictationCapture() =
        runTest {
            val file =
                dictationCapture(
                    name = "${VoiceRecorder.DICTATION_CAPTURE_FILE_PREFIX}fresh${VoiceRecorder.DICTATION_CAPTURE_FILE_SUFFIX}",
                    ageMs = 10 * 60 * 1000,
                )
            coEvery { repository.getAllAudioPaths() } returns emptyList()

            cleaner.cleanOrphanedFiles()

            assertTrue(file.exists())
        }

    @Test
    fun cleanOrphanedFiles_retainsStaleNonDictationFileInCaptureCacheDir() =
        runTest {
            val file =
                dictationCapture(
                    name = "unrelated.bin",
                    ageMs = OrphanedAudioCleaner.DICTATION_CAPTURE_MAX_AGE_MS + 60 * 60 * 1000,
                )
            coEvery { repository.getAllAudioPaths() } returns emptyList()

            cleaner.cleanOrphanedFiles()

            assertTrue(file.exists())
        }

    @Test
    fun cleanOrphanedFiles_retainsJournalReferencedCaptureDirectory() =
        runTest {
            val sessionId = UUID.randomUUID()
            val captureDir =
                File(context.filesDir, "recordings/.capture/$sessionId").apply {
                    mkdirs()
                }
            val segment =
                File(captureDir, "seg-000.m4a").apply {
                    writeText("active segment")
                    setLastModified(System.currentTimeMillis() - 10 * 60 * 1000)
                }
            captureDir.setLastModified(System.currentTimeMillis() - 10 * 60 * 1000)
            journal.createSession(
                sessionId = sessionId,
                audioPath = segment.absolutePath,
                origin = RecordingOrigin.APP,
                profileId = null,
                recordingId = UUID.randomUUID(),
                correlationId = "corr",
            )
            coEvery { repository.getAllAudioPaths() } returns emptyList()

            cleaner.cleanOrphanedFiles()

            assertTrue(segment.exists())
            assertTrue(captureDir.exists())
        }
}
