package dev.chirpboard.app.core.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardRecordingStopBridgeTest {
    @Test
    fun requestStop_invokesRegisteredHandler() {
        val bridge = KeyboardRecordingStopBridge()
        var invoked = false
        bridge.registerStopHandler {
            invoked = true
            true
        }

        assertTrue(bridge.requestStop())
        assertTrue(invoked)
    }

    @Test
    fun requestStop_returnsFalseWhenNoHandlerRegistered() {
        val bridge = KeyboardRecordingStopBridge()

        assertFalse(bridge.requestStop())
    }
    @Test
    fun requestStop_returnsFalseWhenHandlerRefusesStop() {
        val bridge = KeyboardRecordingStopBridge()
        var invoked = false
        bridge.registerStopHandler {
            invoked = true
            false
        }

        assertFalse(bridge.requestStop())
        assertTrue(invoked)
    }


    @Test
    fun clearStopHandler_removesHandler() {
        val bridge = KeyboardRecordingStopBridge()
        bridge.registerStopHandler { true }
        bridge.clearStopHandler()

        assertFalse(bridge.requestStop())
    }
}
