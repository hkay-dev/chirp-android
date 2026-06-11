package dev.chirpboard.app.feature.recording.session

import android.content.Context
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class RecordingSessionJournalTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

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
    fun pruneAbandonedEntries_keepsJournalsWithRecoverableAudio() {
        val sessionId = UUID.randomUUID()
        val audioFile =
            File(context.filesDir, "recordings/recording_test.m4a").apply {
                parentFile?.mkdirs()
                writeText("x".repeat(RecordingSessionJournal.MIN_RECOVERABLE_FILE_BYTES.toInt()))
            }
        journal.createSession(sessionId, audioFile.absolutePath, RecordingOrigin.APP, null, UUID.randomUUID(), "corr-1")
        journal.markAbandoned(sessionId)

        assertEquals(0, journal.pruneAbandonedEntries(maxAgeMs = 0))
        assertEquals(SessionJournalState.ABANDONED, journal.findBySessionId(sessionId)?.state)
    }

    @Test
    fun loadSessions_quarantinesUnparseableEntryAndKeepsItsAudioReferenced() {
        val sessionId = UUID.randomUUID()
        val audioPath = File(context.filesDir, "recordings/recording_corrupt.m4a").absolutePath
        journal.createSession(sessionId, audioPath, RecordingOrigin.APP, null, UUID.randomUUID(), "corr-1")

        val journalFile = File(context.filesDir, "recordings/.sessions/$sessionId.json")
        // Corrupt the entry: required fields are gone but the audio path survives.
        journalFile.writeText("""{"audioPath":"$audioPath"}""")

        assertTrue(journal.loadAllEntries().isEmpty())
        assertFalse(journalFile.exists())
        val quarantined = File(journalFile.parentFile, "${journalFile.name}${RecordingSessionJournal.CORRUPT_SUFFIX}")
        assertTrue(quarantined.exists())
        // The referenced audio must stay out of orphan cleanup.
        assertTrue(journal.getAllReferencedAudioPaths().contains(audioPath))
    }

    @Test
    fun findBySessionId_quarantinesUnparseableEntryAndReturnsNull() {
        val sessionId = UUID.randomUUID()
        val audioPath = File(context.filesDir, "recordings/recording_corrupt.m4a").absolutePath
        journal.createSession(sessionId, audioPath, RecordingOrigin.APP, null, UUID.randomUUID(), "corr-1")
        val journalFile = File(context.filesDir, "recordings/.sessions/$sessionId.json")
        journalFile.writeText("not json at all")

        assertEquals(null, journal.findBySessionId(sessionId))
        assertFalse(journalFile.exists())
        assertTrue(File(journalFile.parentFile, "${journalFile.name}${RecordingSessionJournal.CORRUPT_SUFFIX}").exists())
    }

    @Test
    fun loadSessions_skipsUnreadableEntryWithoutQuarantine() {
        // A directory with a .json name makes readText fail with an IOException; a
        // transient read failure is not corruption and must not quarantine the entry.
        val sessionId = UUID.randomUUID()
        val journalFile = File(context.filesDir, "recordings/.sessions/$sessionId.json")
        journalFile.mkdirs()

        assertTrue(journal.loadAllEntries().isEmpty())
        assertEquals(null, journal.findBySessionId(sessionId))
        assertTrue(journalFile.exists())
        assertFalse(File(journalFile.parentFile, "${journalFile.name}${RecordingSessionJournal.CORRUPT_SUFFIX}").exists())
    }

    @Test
    fun pruneCorruptEntries_deletesOnlyStaleQuarantinedJournals() {
        val sessionId = UUID.randomUUID()
        val audioPath = File(context.filesDir, "recordings/recording_corrupt.m4a").absolutePath
        journal.createSession(sessionId, audioPath, RecordingOrigin.APP, null, UUID.randomUUID(), "corr-1")
        val journalFile = File(context.filesDir, "recordings/.sessions/$sessionId.json")
        // Required fields are gone but the audio path is still extractable best-effort.
        journalFile.writeText("""{"audioPath":"$audioPath"}""")
        assertEquals(null, journal.findBySessionId(sessionId))
        val quarantined = File(journalFile.parentFile, "${journalFile.name}${RecordingSessionJournal.CORRUPT_SUFFIX}")
        assertTrue(quarantined.exists())

        // Within retention the quarantined entry (and its audio shield) survives.
        assertEquals(0, journal.pruneCorruptEntries(maxAgeMs = Long.MAX_VALUE))
        assertTrue(quarantined.exists())
        assertTrue(journal.getAllReferencedAudioPaths().contains(audioPath))

        quarantined.setLastModified(System.currentTimeMillis() - 1_000)
        assertEquals(1, journal.pruneCorruptEntries(maxAgeMs = 0))
        assertFalse(quarantined.exists())
        assertFalse(journal.getAllReferencedAudioPaths().contains(audioPath))
    }

    @Test
    fun updateEntry_serializesConcurrentSegmentAppends() {
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

        val threadCount = 16
        val startGate = CountDownLatch(1)
        val threads =
            (0 until threadCount).map { index ->
                thread {
                    startGate.await()
                    journal.commitPausedSegment(
                        sessionId = sessionId,
                        completedSegmentPath =
                            File(context.filesDir, "recordings/.capture/$sessionId/seg-$index.m4a").absolutePath,
                        fileBytes = index.toLong(),
                    )
                }
            }
        startGate.countDown()
        threads.forEach { it.join() }

        // Without serialized read-modify-write cycles, concurrent appends lose segments.
        assertEquals(threadCount, journal.findBySessionId(sessionId)?.segmentPaths?.size)
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
