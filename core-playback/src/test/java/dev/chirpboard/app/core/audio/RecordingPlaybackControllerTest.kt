package dev.chirpboard.app.core.audio

import android.content.Context
import io.mockk.every
import io.mockk.mockk
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
