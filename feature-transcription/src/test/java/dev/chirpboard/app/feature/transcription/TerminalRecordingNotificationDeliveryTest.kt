package dev.chirpboard.app.feature.transcription

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class TerminalRecordingNotificationDeliveryTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    private lateinit var context: Context
    private lateinit var repository: RecordingRepository
    private lateinit var delivery: TerminalRecordingNotificationDelivery

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        repository = mockk(relaxed = true)
        delivery = TerminalRecordingNotificationDelivery(context, repository)
        mockkStatic("dev.chirpboard.app.feature.transcription.TranscriptionWorkerSupportKt")
        mockkStatic("dev.chirpboard.app.feature.transcription.TerminalRecordingNotificationDeliveryKt")
        every { terminalRecordingNotificationsEnabled(context) } returns true
        every { terminalRecordingChannelEnabled(context, any()) } returns true
        every { showTranscriptionReadyNotification(any(), any(), any()) } just runs
        every { showTranscriptionCleanupRetryNotification(any(), any(), any()) } just runs
        every { showTranscriptionErrorNotification(any(), any(), any()) } just runs
        every { transcriptionFailureNotificationText(any(), "cloud failed") } returns "Transcription failed"
    }

    @After
    fun tearDown() {
        unmockkStatic("dev.chirpboard.app.feature.transcription.TerminalRecordingNotificationDeliveryKt")
        unmockkStatic("dev.chirpboard.app.feature.transcription.TranscriptionWorkerSupportKt")
    }

    @Test
    fun completedRecording_postsReadyThenClearsItsPendingMarker() =
        runTest {
            val recording = recording(RecordingStatus.COMPLETED)
            val transcript = Transcript(recordingId = recording.id, rawText = "raw words", processedText = "AI words")
            coEvery { repository.getRecording(recording.id) } returns recording
            coEvery { repository.getTranscript(recording.id) } returns transcript
            coEvery {
                repository.clearPendingTerminalNotification(recording.id, RecordingStatus.COMPLETED)
            } returns true

            val delivered = delivery.deliverRequested(recording.id)

            assertTrue(delivered)
            verify(exactly = 1) { showTranscriptionReadyNotification(context, recording.id, transcript) }
            coVerify(exactly = 1) {
                repository.clearPendingTerminalNotification(recording.id, RecordingStatus.COMPLETED)
            }
        }

    @Test
    fun failedRecordingWithTranscript_postsCleanupRetry() =
        runTest {
            val recording = recording(RecordingStatus.FAILED)
            coEvery { repository.getRecording(recording.id) } returns recording
            val transcript = Transcript(recordingId = recording.id, rawText = "saved words")
            coEvery { repository.getTranscript(recording.id) } returns transcript
            coEvery {
                repository.clearPendingTerminalNotification(recording.id, RecordingStatus.FAILED)
            } returns true

            val delivered = delivery.deliverRequested(recording.id)

            assertTrue(delivered)
            verify(exactly = 1) { showTranscriptionCleanupRetryNotification(context, recording.id, transcript) }
            verify(exactly = 0) { showTranscriptionErrorNotification(any(), any(), any()) }
        }

    @Test
    fun startupRecovery_replaysEveryPendingTerminalMarker() =
        runTest {
            val first = recording(RecordingStatus.COMPLETED)
            val second = recording(RecordingStatus.FAILED)
            coEvery { repository.getPendingTerminalNotifications() } returns listOf(first, second)
            coEvery { repository.getTranscript(first.id) } returns null
            coEvery { repository.getTranscript(second.id) } returns null
            coEvery { repository.clearPendingTerminalNotification(any(), any()) } returns true

            val delivered = delivery.recoverPendingNotifications()

            assertEquals(2, delivered)
            verify(exactly = 1) { showTranscriptionReadyNotification(context, first.id, null) }
            verify(exactly = 1) { showTranscriptionErrorNotification(context, second.id, "Transcription failed") }
        }

    @Test
    fun notificationPostFailure_keepsThePendingMarker() =
        runTest {
            val recording = recording(RecordingStatus.COMPLETED)
            coEvery { repository.getRecording(recording.id) } returns recording
            coEvery { repository.getTranscript(recording.id) } returns null
            every { showTranscriptionReadyNotification(context, recording.id, null) } throws
                IllegalStateException("notification service unavailable")

            val delivered = delivery.deliverRequested(recording.id)

            assertFalse(delivered)
            coVerify(exactly = 0) { repository.clearPendingTerminalNotification(any(), any()) }
        }

    @Test
    fun disabledNotifications_keepThePendingMarkerForRecovery() =
        runTest {
            val recording = recording(RecordingStatus.COMPLETED)
            coEvery { repository.getRecording(recording.id) } returns recording
            every { terminalRecordingNotificationsEnabled(context) } returns false

            val delivered = delivery.deliverRequested(recording.id)

            assertFalse(delivered)
            verify(exactly = 0) { showTranscriptionReadyNotification(any(), any(), any()) }
            coVerify(exactly = 0) { repository.clearPendingTerminalNotification(any(), any()) }
        }

    @Test
    fun disabledReadyChannel_keepsThePendingMarkerForRecovery() =
        runTest {
            val recording = recording(RecordingStatus.COMPLETED)
            coEvery { repository.getRecording(recording.id) } returns recording
            every { terminalRecordingChannelEnabled(context, TRANSCRIPTION_READY_CHANNEL_ID) } returns false

            val delivered = delivery.deliverRequested(recording.id)

            assertFalse(delivered)
            verify(exactly = 0) { showTranscriptionReadyNotification(any(), any(), any()) }
            coVerify(exactly = 0) { repository.clearPendingTerminalNotification(any(), any()) }
        }

    @Test
    fun disabledErrorChannel_keepsThePendingMarkerForRecovery() =
        runTest {
            val recording = recording(RecordingStatus.FAILED)
            coEvery { repository.getRecording(recording.id) } returns recording
            coEvery { repository.getTranscript(recording.id) } returns null
            every { terminalRecordingChannelEnabled(context, TRANSCRIPTION_ERROR_CHANNEL_ID) } returns false

            val delivered = delivery.deliverRequested(recording.id)

            assertFalse(delivered)
            verify(exactly = 0) { showTranscriptionErrorNotification(any(), any(), any()) }
            coVerify(exactly = 0) { repository.clearPendingTerminalNotification(any(), any()) }
        }

    @Test
    fun immutablePreferenceDoesNotReplayAClearedPendingMarker() =
        runTest {
            val recording =
                recording(RecordingStatus.COMPLETED).copy(
                    notifyWhenReady = true,
                    terminalNotificationPending = false,
                )
            coEvery { repository.getRecording(recording.id) } returns recording

            val delivered = delivery.deliverRequested(recording.id)

            assertFalse(delivered)
            verify(exactly = 0) { showTranscriptionReadyNotification(any(), any(), any()) }
            coVerify(exactly = 0) { repository.clearPendingTerminalNotification(any(), any()) }
        }

    private fun recording(status: RecordingStatus): Recording =
        Recording(
            id = UUID.randomUUID(),
            title = "Keyboard recording",
            audioPath = "/tmp/keyboard.wav",
            status = status,
            source = RecordingSource.KEYBOARD,
            errorMessage = "cloud failed",
            notifyWhenReady = true,
            terminalNotificationPending = true,
        )
}

class TerminalTranscriptNotificationTextTest {
    @Test
    fun distinctAiResult_keepsBothChoicesVisible() {
        val context =
            mockk<Context> {
                every { getString(R.string.transcription_ready_ai_label) } returns "AI result"
                every { getString(R.string.transcription_ready_raw_label) } returns "Transcript"
            }
        val transcript =
            Transcript(
                recordingId = UUID.randomUUID(),
                rawText = "rough opening words",
                processedText = "Polished opening words.",
            )

        assertEquals(
            "AI result\nPolished opening words.\n\nTranscript\nrough opening words",
            terminalTranscriptNotificationText(context, transcript),
        )
    }

    @Test
    fun duplicateProcessedText_showsOneResult() {
        val transcript =
            Transcript(
                recordingId = UUID.randomUUID(),
                rawText = "same words",
                processedText = " same words ",
            )

        assertEquals("same words", terminalTranscriptNotificationText(mockk(relaxed = true), transcript))
    }

    @Test
    fun copyActionsSelectTheirOwnStoredVariant() {
        val transcript =
            Transcript(
                recordingId = UUID.randomUUID(),
                rawText = "raw words",
                processedText = "AI words",
            )

        assertEquals("raw words", transcriptionCopyText(transcript, copyAiResult = false))
        assertEquals("AI words", transcriptionCopyText(transcript, copyAiResult = true))
    }
}

class TerminalRecordingNotificationAccessTest {
    @Test
    fun exactChannelWithNoImportanceIsDisabled() {
        val context = mockk<Context>()
        val notificationManager = mockk<NotificationManager>()
        val channel = mockk<NotificationChannel>()
        every { context.getSystemService(NotificationManager::class.java) } returns notificationManager
        every { notificationManager.getNotificationChannel(TRANSCRIPTION_READY_CHANNEL_ID) } returns channel
        every { channel.importance } returns NotificationManager.IMPORTANCE_NONE

        assertFalse(terminalRecordingChannelEnabled(context, TRANSCRIPTION_READY_CHANNEL_ID))
    }
}
