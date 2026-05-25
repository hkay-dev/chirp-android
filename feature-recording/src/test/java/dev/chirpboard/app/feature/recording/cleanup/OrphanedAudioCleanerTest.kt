package dev.chirpboard.app.feature.recording.cleanup

import android.content.Context
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
        context =
            mockk(relaxed = true) {
                every { filesDir } returns root
            }
        repository = mockk(relaxed = true)
        journal = RecordingSessionJournal(context)
        protectedPathsStore = mockk(relaxed = true)
        coEvery { protectedPathsStore.activeProtectedPaths() } returns emptySet()
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
}
