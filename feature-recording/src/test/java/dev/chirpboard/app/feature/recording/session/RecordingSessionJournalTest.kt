package dev.chirpboard.app.feature.recording.session

import android.content.Context
import dev.chirpboard.app.core.recording.RecordingOrigin
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class RecordingSessionJournalTest {
    private lateinit var context: Context
    private lateinit var journal: RecordingSessionJournal

    @Before
    fun setup() {
        val root = createTempDir("journal-test")
        context =
            mockk(relaxed = true) {
                every { filesDir } returns root
            }
        journal = RecordingSessionJournal(context)
    }

    @Test
    fun createSession_writesActiveJournal() {
        val sessionId = UUID.randomUUID()
        val audioPath = File(context.filesDir, "recordings/recording_test.m4a").absolutePath

        val entry =
            journal.createSession(
                sessionId = sessionId,
                audioPath = audioPath,
                origin = RecordingOrigin.APP,
                profileId = null,
                recordingId = UUID.randomUUID(),
                correlationId = "corr-1",
            )

        assertEquals(SessionJournalState.ACTIVE, entry.state)
        assertTrue(journal.getSafelistedAudioPaths().contains(audioPath))
    }

    @Test
    fun markFinalized_removesSafelistEntry() {
        val sessionId = UUID.randomUUID()
        val audioPath = File(context.filesDir, "recordings/recording_test.m4a").absolutePath
        journal.createSession(sessionId, audioPath, RecordingOrigin.APP, null, UUID.randomUUID(), "corr-1")

        journal.markFinalized(sessionId)

        assertFalse(journal.getSafelistedAudioPaths().contains(audioPath))
        assertTrue(journal.loadActiveSessions().isEmpty())
    }

    @Test
    fun markAbandoned_keepsJournalButNotSafelistedAsActive() {
        val sessionId = UUID.randomUUID()
        val audioPath = File(context.filesDir, "recordings/recording_test.m4a").absolutePath
        journal.createSession(sessionId, audioPath, RecordingOrigin.APP, null, UUID.randomUUID(), "corr-1")

        journal.markAbandoned(sessionId)

        assertFalse(journal.getSafelistedAudioPaths().contains(audioPath))
        assertEquals(SessionJournalState.ABANDONED, journal.findBySessionId(sessionId)?.state)
    }

    @Test
    fun pruneAbandonedEntries_deletesStaleAbandonedJournals() {
        val sessionId = UUID.randomUUID()
        val audioPath = File(context.filesDir, "recordings/recording_test.m4a").absolutePath
        journal.createSession(sessionId, audioPath, RecordingOrigin.APP, null, UUID.randomUUID(), "corr-1")
        journal.markAbandoned(sessionId)

        assertEquals(0, journal.pruneAbandonedEntries(maxAgeMs = Long.MAX_VALUE))
        assertEquals(SessionJournalState.ABANDONED, journal.findBySessionId(sessionId)?.state)

        assertEquals(1, journal.pruneAbandonedEntries(maxAgeMs = 0))
        assertEquals(null, journal.findBySessionId(sessionId))
    }

    @Test
    fun commitPausedSegment_recordsCompletedHiddenSegment() {
        val sessionId = UUID.randomUUID()
        val finalPath = File(context.filesDir, "recordings/recording_test.m4a").absolutePath
        val firstSegment = File(context.filesDir, "recordings/.capture/$sessionId/seg-000.m4a").absolutePath

        journal.createSession(
            sessionId = sessionId,
            audioPath = firstSegment,
            origin = RecordingOrigin.APP,
            profileId = null,
            recordingId = UUID.randomUUID(),
            correlationId = "corr-1",
            finalAudioPath = finalPath,
        )

        journal.commitPausedSegment(
            sessionId = sessionId,
            completedSegmentPath = firstSegment,
            fileBytes = 2048L,
        )

        val entry = journal.findBySessionId(sessionId)
        assertEquals(listOf(firstSegment), entry?.segmentPaths)
        assertEquals(firstSegment, entry?.audioPath)
        assertEquals(2048L, entry?.fileBytes)
        assertTrue(entry?.lastSegmentFinalizedAtEpochMs != null)
    }

    @Test
    fun beginNextSegment_setsActiveCapturePath() {
        val sessionId = UUID.randomUUID()
        val finalPath = File(context.filesDir, "recordings/recording_test.m4a").absolutePath
        val firstSegment = File(context.filesDir, "recordings/.capture/$sessionId/seg-000.m4a").absolutePath
        val secondSegment = File(context.filesDir, "recordings/.capture/$sessionId/seg-001.m4a").absolutePath

        journal.createSession(
            sessionId = sessionId,
            audioPath = firstSegment,
            origin = RecordingOrigin.APP,
            profileId = null,
            recordingId = UUID.randomUUID(),
            correlationId = "corr-1",
            finalAudioPath = finalPath,
        )
        journal.commitPausedSegment(sessionId, firstSegment, 1024L)
        journal.beginNextSegment(sessionId, secondSegment)

        val entry = journal.findBySessionId(sessionId)
        assertEquals(secondSegment, entry?.audioPath)
        assertEquals(listOf(firstSegment), entry?.segmentPaths)
        assertTrue(entry?.lastSegmentFinalizedAtEpochMs != null)
        assertTrue(entry?.activeSegmentStartedAtEpochMs != null)
    }
}
