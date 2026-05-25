package dev.chirpboard.app.core.playback

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class RecordingPlaybackControllerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun initialState_isIdle() {
        val controller = RecordingPlaybackController(testContext())
        assertTrue(controller.state.value.isIdle)
    }

    @Test
    fun prepare_missingAudioFile_doesNotStartForegroundService() {
        val context = testContext()
        val controller = RecordingPlaybackController(context)
        val recordingId = UUID.randomUUID()

        controller.prepare(recordingId, "Missing clip", "/does/not/exist.m4a")

        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun prepare_missingAudioFile_surfacesErrorAndStaysInactive() {
        val controller = RecordingPlaybackController(testContext())
        val recordingId = UUID.randomUUID()

        controller.prepare(recordingId, "Missing clip", "/does/not/exist.m4a")

        val state = controller.state.value
        assertEquals(recordingId, state.recordingId)
        assertEquals("/does/not/exist.m4a", state.audioPath)
        assertEquals("Audio file not found", state.errorMessage)
        assertFalse(state.isLoading)
        assertFalse(state.isActive)
    }

    @Test
    fun stop_clearsActivePlaybackState() {
        val controller = RecordingPlaybackController(testContext())
        val recordingId = UUID.randomUUID()

        controller.prepare(recordingId, "Missing clip", "/does/not/exist.m4a")
        controller.stop()

        assertTrue(controller.state.value.isIdle)
    }

    private fun testContext(): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        return context
    }
}
