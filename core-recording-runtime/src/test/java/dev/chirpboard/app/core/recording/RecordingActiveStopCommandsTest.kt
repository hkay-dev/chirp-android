package dev.chirpboard.app.core.recording

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
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


    @Test
    fun `rejected service stop dispatch surfaces a recording error instead of dying silently`() =
        runTest {
            val stateManager = RecordingStateManager()
            stateManager.tryStartRecording(RecordingOrigin.APP, profileId = null)
            stateManager.onRecordingStarted(audioFilePath = "app.m4a", recordingId = UUID.randomUUID())
            mockkObject(RecordingServiceCommands)
            try {
                every { RecordingServiceCommands.stopRecording(any()) } returns false

                RecordingActiveStopCommands.stopActiveRecording(
                    context = mockk(relaxed = true),
                    recordingStateManager = stateManager,
                    keyboardStopBridge = KeyboardRecordingStopBridge(),
                    pendingStopStore = pendingStopStore(),
                    requesterOrigin = RecordingOrigin.APP,
                )

                val state = stateManager.state.value
                assertTrue(state is RecordingState.Error)
                assertEquals(
                    "Could not stop the recording service",
                    (state as RecordingState.Error).message,
                )
            } finally {
                unmockkObject(RecordingServiceCommands)
            }
        }

    @Test
    fun `accepted service stop dispatch leaves recording state untouched`() =
        runTest {
            val stateManager = RecordingStateManager()
            stateManager.tryStartRecording(RecordingOrigin.APP, profileId = null)
            stateManager.onRecordingStarted(audioFilePath = "app.m4a", recordingId = UUID.randomUUID())
            mockkObject(RecordingServiceCommands)
            try {
                every { RecordingServiceCommands.stopRecording(any()) } returns true

                RecordingActiveStopCommands.stopActiveRecording(
                    context = mockk(relaxed = true),
                    recordingStateManager = stateManager,
                    keyboardStopBridge = KeyboardRecordingStopBridge(),
                    pendingStopStore = pendingStopStore(),
                    requesterOrigin = RecordingOrigin.APP,
                )

                assertTrue(stateManager.state.value is RecordingState.Recording)
            } finally {
                unmockkObject(RecordingServiceCommands)
            }
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
