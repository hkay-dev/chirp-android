package dev.chirpboard.app.feature.recording.session

import dev.chirpboard.app.core.testing.MockAndroidLogRule
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class RecordingSessionHeartbeatTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    @Test
    fun checkpointFailure_doesNotPreventLaterHeartbeat() =
        runTest {
            val journal = mockk<RecordingSessionJournal>(relaxed = true)
            val heartbeat = RecordingSessionHeartbeat(journal)
            val sessionId = UUID.randomUUID()
            val file = File.createTempFile("heartbeat", ".wav").apply { writeBytes(ByteArray(1024)) }

            heartbeat.checkpointOnce(
                sessionIdProvider = { sessionId },
                activeFileProvider = { file },
                durabilityCheckpoint = { error("transient sync failure") },
            )
            heartbeat.checkpointOnce(
                sessionIdProvider = { sessionId },
                activeFileProvider = { file },
            )

            verify(exactly = 1) { journal.updateHeartbeat(sessionId, 1024L) }
            file.delete()
        }
}
