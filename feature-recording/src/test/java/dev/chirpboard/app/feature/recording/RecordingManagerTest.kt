package dev.chirpboard.app.feature.recording

import android.content.Context
import dev.chirpboard.app.core.recording.KeyboardPendingStopStore
import dev.chirpboard.app.core.recording.KeyboardRecordingStopBridge
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingStartResult
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecordingManagerTest {

    private lateinit var context: Context
    private lateinit var stateManager: RecordingStateManager
    private lateinit var keyboardStopBridge: KeyboardRecordingStopBridge
    private lateinit var pendingStopStore: KeyboardPendingStopStore
    private lateinit var manager: RecordingManager

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        stateManager = mockk(relaxed = true)
        keyboardStopBridge = KeyboardRecordingStopBridge()
        pendingStopStore = mockk(relaxed = true)
        manager = RecordingManager(context, stateManager, keyboardStopBridge, pendingStopStore)
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

    @Test
    fun `stopRecording routes keyboard recording through bridge`() =
        runTest {
            var bridgeInvoked = false
            keyboardStopBridge.registerStopHandler {
                bridgeInvoked = true
                true
            }
            every { stateManager.state.value } returns RecordingState.Recording(origin = RecordingOrigin.KEYBOARD)

            manager.stopRecording()

            assertTrue(bridgeInvoked)
            coVerify(exactly = 0) { pendingStopStore.enqueue(any()) }
        }

    @Test
    fun `stopRecording queues keyboard stop when bridge refuses`() =
        runTest {
            var queued = false
            keyboardStopBridge.registerStopHandler { false }
            every { stateManager.state.value } returns RecordingState.Recording(origin = RecordingOrigin.KEYBOARD)

            manager.stopRecording {
                queued = true
            }

            assertTrue(queued)
            coVerify { pendingStopStore.enqueue(RecordingOrigin.APP) }
        }
}
