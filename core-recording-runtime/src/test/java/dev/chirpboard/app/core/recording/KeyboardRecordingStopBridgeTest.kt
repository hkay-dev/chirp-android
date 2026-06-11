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
        val registration = bridge.registerStopHandler { true }
        bridge.clearStopHandler(registration)

        assertFalse(bridge.requestStop())
    }
    @Test
    fun staleClear_doesNotRemoveNewerHandler() {
        val bridge = KeyboardRecordingStopBridge()
        val staleRegistration = bridge.registerStopHandler { false }
        bridge.registerStopHandler { true }

        bridge.clearStopHandler(staleRegistration)

        assertTrue(bridge.requestStop())
    }

}
