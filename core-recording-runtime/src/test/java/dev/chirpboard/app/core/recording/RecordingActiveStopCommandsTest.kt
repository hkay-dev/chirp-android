package dev.chirpboard.app.core.recording

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class RecordingActiveStopCommandsTest {
    @get:Rule
    val logRule = MockAndroidLogRule()

    @Test
    fun `keyboard pending stop is durable before queued callback`() =
        runTest {
            val store = pendingStopStore()
            val stateManager = RecordingStateManager()
            val bridge = KeyboardRecordingStopBridge()
            val recordingId = UUID.randomUUID()
            var callbackSawPending = false
            stateManager.tryStartRecording(RecordingOrigin.KEYBOARD, profileId = null)
            stateManager.onRecordingStarted(audioFilePath = "keyboard.m4a", recordingId = recordingId)

            RecordingActiveStopCommands.stopActiveRecording(
                context = mockk(relaxed = true),
                recordingStateManager = stateManager,
                keyboardStopBridge = bridge,
                pendingStopStore = store,
                requesterOrigin = RecordingOrigin.WIDGET,
                onKeyboardStopQueued = {
                    callbackSawPending = runBlocking { store.peek()?.requesterOrigin == RecordingOrigin.WIDGET }
                },
            )

            assertTrue(callbackSawPending)
            assertEquals(RecordingOrigin.WIDGET, store.peek()?.requesterOrigin)
        }
    @Test
    fun `keyboard pending stop is queued when registered handler refuses stop`() =
        runTest {
            val store = pendingStopStore()
            val stateManager = RecordingStateManager()
            val bridge = KeyboardRecordingStopBridge()
            var handlerInvoked = false
            var callbackSawPending = false
            stateManager.tryStartRecording(RecordingOrigin.KEYBOARD, profileId = null)
            stateManager.onRecordingStarted(audioFilePath = "keyboard.m4a", recordingId = UUID.randomUUID())
            bridge.registerStopHandler {
                handlerInvoked = true
                false
            }

            RecordingActiveStopCommands.stopActiveRecording(
                context = mockk(relaxed = true),
                recordingStateManager = stateManager,
                keyboardStopBridge = bridge,
                pendingStopStore = store,
                requesterOrigin = RecordingOrigin.WIDGET,
                onKeyboardStopQueued = {
                    callbackSawPending = runBlocking { store.peek()?.requesterOrigin == RecordingOrigin.WIDGET }
                },
            )

            assertTrue(handlerInvoked)
            assertTrue(callbackSawPending)
            assertEquals(RecordingOrigin.WIDGET, store.peek()?.requesterOrigin)
        }

    @Test
    fun `keyboard pending stop is not queued when registered handler accepts stop`() =
        runTest {
            val store = pendingStopStore()
            val stateManager = RecordingStateManager()
            val bridge = KeyboardRecordingStopBridge()
            var callbackInvoked = false
            stateManager.tryStartRecording(RecordingOrigin.KEYBOARD, profileId = null)
            stateManager.onRecordingStarted(audioFilePath = "keyboard.m4a", recordingId = UUID.randomUUID())
            bridge.registerStopHandler { true }

            RecordingActiveStopCommands.stopActiveRecording(
                context = mockk(relaxed = true),
                recordingStateManager = stateManager,
                keyboardStopBridge = bridge,
                pendingStopStore = store,
                requesterOrigin = RecordingOrigin.WIDGET,
                onKeyboardStopQueued = {
                    callbackInvoked = true
                },
            )

            assertFalse(callbackInvoked)
            assertEquals(null, store.peek())
        }


    private fun pendingStopStore(): KeyboardPendingStopStore {
        val root = createTempDir("active-stop-command-test")
        val dataStore: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                produceFile = { File(root, "keyboard_pending_stop.preferences_pb") },
            )
        return KeyboardPendingStopStore(dataStore)
    }
}
