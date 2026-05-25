package dev.chirpboard.app.feature.recording

import android.content.Context
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingStartResult
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecordingManagerTest {

    private lateinit var context: Context
    private lateinit var stateManager: RecordingStateManager
    private lateinit var manager: RecordingManager

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        stateManager = mockk(relaxed = true)
        manager = RecordingManager(context, stateManager)
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `startRecording returns AlreadyRecording if cannot start`() {
        every { stateManager.canStartRecording() } returns false
        every { stateManager.state.value } returns RecordingState.Recording(origin = RecordingOrigin.WIDGET)

        val result = manager.startRecording()
        assertTrue(result is RecordingStartResult.AlreadyRecording)
        assertEquals(RecordingOrigin.WIDGET, (result as RecordingStartResult.AlreadyRecording).currentOrigin)
    }
}
