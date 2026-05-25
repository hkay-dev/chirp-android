package dev.chirpboard.app.feature.recording.cleanup

import android.content.Context
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.recording.session.RecordingSessionJournal
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class OrphanedAudioCleanerTest {
    private lateinit var context: Context
    private lateinit var repository: RecordingRepository
    private lateinit var journal: RecordingSessionJournal
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
        cleaner = OrphanedAudioCleaner(context, repository, journal)
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
}
