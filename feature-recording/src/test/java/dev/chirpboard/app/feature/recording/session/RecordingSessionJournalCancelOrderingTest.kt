package dev.chirpboard.app.feature.recording.session

import android.content.Context
import dev.chirpboard.app.core.recording.RecordingOrigin
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class RecordingSessionJournalCancelOrderingTest {
    private lateinit var context: Context
    private lateinit var journal: RecordingSessionJournal

    @Before
    fun setup() {
        val root = createTempDir("cancel-ordering-test")
        context =
            mockk(relaxed = true) {
                every { filesDir } returns root
            }
        journal = RecordingSessionJournal(context)
    }

    @Test
    fun markAbandoned_noJournalFile_isNoOp() {
        val sessionId = UUID.randomUUID()

        journal.markAbandoned(sessionId)

        assertNull(journal.findBySessionId(sessionId))
        assertTrue(journal.loadRecoverableSessions().isEmpty())
    }

    @Test
    fun markAbandoned_removesSessionFromRecoverableList() {
        val sessionId = UUID.randomUUID()
        val audioFile =
            File(context.filesDir, "recordings/recording_cancel.m4a").apply {
                parentFile?.mkdirs()
                writeText("x".repeat(RecordingSessionJournal.MIN_RECOVERABLE_FILE_BYTES.toInt()))
            }

        journal.createSession(
            sessionId = sessionId,
            audioPath = audioFile.absolutePath,
            origin = RecordingOrigin.APP,
            profileId = null,
            recordingId = UUID.randomUUID(),
            correlationId = "corr",
        )

        journal.markAbandoned(sessionId)

        assertTrue(journal.loadRecoverableSessions().none { it.sessionId == sessionId })
    }
}
