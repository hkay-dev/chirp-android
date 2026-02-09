package dev.chirpboard.app.recognition

import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionHistoryPersistenceTest {

    @Test
    fun `persistRecognitionHistoryAtomically returns success and linked ids`() = runBlocking {
        var capturedRecording: Recording? = null
        var capturedTranscript: Transcript? = null

        val result = persistRecognitionHistoryAtomically("hello from test") { recording, transcript ->
            capturedRecording = recording
            capturedTranscript = transcript
        }

        assertTrue(result.isSuccess)
        assertNotNull(capturedRecording)
        assertNotNull(capturedTranscript)
        assertEquals(capturedRecording!!.id, capturedTranscript!!.recordingId)
        assertEquals("hello from test", capturedTranscript!!.rawText)
        assertEquals(RecordingSource.KEYBOARD, capturedRecording!!.source)
        assertEquals(RecordingStatus.COMPLETED, capturedRecording!!.status)
    }

    @Test
    fun `persistRecognitionHistoryAtomically returns failure when persistence throws`() = runBlocking {
        val result = persistRecognitionHistoryAtomically("hello") { _, _ ->
            throw IllegalStateException("simulated db failure")
        }

        assertTrue(result.isFailure)
        assertEquals("simulated db failure", result.exceptionOrNull()?.message)
    }
}
