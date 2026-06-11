package dev.chirpboard.app.core.recording

import dev.chirpboard.app.core.testing.MockAndroidLogRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RecordingServiceCommandsTest {
    @get:Rule
    val logRule = MockAndroidLogRule()

    @Test
    fun `guardDispatch returns true when start succeeds`() {
        var started = false

        val result =
            RecordingServiceCommands.guardDispatch(RecordingServiceCommands.ACTION_START_RECORDING) {
                started = true
            }

        assertTrue(result)
        assertTrue(started)
    }

    @Test
    fun `guardDispatch swallows background start rejection`() {
        val result =
            RecordingServiceCommands.guardDispatch(RecordingServiceCommands.ACTION_START_RECORDING) {
                throw IllegalStateException("startForegroundService() not allowed")
            }

        assertFalse(result)
    }

    @Test
    fun `guardDispatch swallows security rejection`() {
        val result =
            RecordingServiceCommands.guardDispatch(RecordingServiceCommands.ACTION_STOP_RECORDING) {
                throw SecurityException("caller not allowed")
            }

        assertFalse(result)
    }
}
