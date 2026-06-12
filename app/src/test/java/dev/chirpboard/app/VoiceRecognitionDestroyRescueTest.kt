package dev.chirpboard.app

import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [VoiceRecognitionActivity]'s destroy-time rescue classification: rescue persists a
 * capture only when the *system* interrupted a live session. A teardown the user (cancel)
 * or the dialog (no-speech timeout) already classified as a discard must never be re-filed
 * as a "Voice recognition interrupted" rescue entry just because the asynchronous
 * coordinator cancel had not released the capture gate before onDestroy ran — that race
 * produced ghost rescue entries for cancelled all-silence sessions. The never-drop-speech
 * rule stays intact: an unclassified live capture is always rescued.
 *
 * Also pins the MIC-015 destroy teardown ([launchRecognitionDestroyTeardown]): the recorder
 * stop/rescue is dispatched asynchronously off the destroying main thread, with the rescue
 * decision captured synchronously before the hop — the rescue must still happen, in order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceRecognitionDestroyRescueTest {
    private val live = RecordingState.Recording(RecordingOrigin.KEYBOARD, startTimeMs = 0L)

    @Test
    fun `system-interrupted live capture is rescued`() {
        assertTrue(
            shouldRescueOnDestroy(
                recordingState = live,
                secureSession = false,
                teardownDiscardsAudio = false,
            ),
        )
    }

    @Test
    fun `user-cancelled capture is not rescued even while the async cancel still holds the gate`() {
        // Regression: cancelling mid-silence left a rescue entry because the IO-hop
        // teardown had not released the gate when onDestroy ran.
        assertFalse(
            shouldRescueOnDestroy(
                recordingState = RecordingState.Idle,
                secureSession = false,
                teardownDiscardsAudio = true,
            ),
        )
        // Even if the dialog state had not yet flipped to Idle, the discard mark wins.
        assertFalse(
            shouldRescueOnDestroy(
                recordingState = live,
                secureSession = false,
                teardownDiscardsAudio = true,
            ),
        )
    }

    @Test
    fun `capture handed to the inline pipeline is owned by the pipeline not rescue`() {
        assertFalse(
            shouldRescueOnDestroy(
                recordingState = RecordingState.Stopping(RecordingOrigin.KEYBOARD),
                secureSession = false,
                teardownDiscardsAudio = false,
            ),
        )
    }

    @Test
    fun `secure sessions never persist a rescue entry`() {
        assertFalse(
            shouldRescueOnDestroy(
                recordingState = live,
                secureSession = true,
                teardownDiscardsAudio = false,
            ),
        )
    }

    // --- MIC-015: the destroy teardown is dispatched off the destroying thread ---

    @Test
    fun `async destroy teardown still rescues with the synchronously captured classification`() =
        runTest {
            val order = mutableListOf<String>()
            var rescued: FloatArray? = null
            launchRecognitionDestroyTeardown(
                gateHeld = true,
                rescue = true,
                stopRecorder = {
                    order += "stop"
                    floatArrayOf(0.1f, 0.2f)
                },
                releaseGate = { order += "release" },
                rescueSamples = { samples ->
                    order += "rescue"
                    rescued = samples
                },
                closeRecorder = { order += "close" },
                abandonFocus = { order += "focus" },
            )

            // Nothing ran inline: onDestroy returns immediately and the recorder teardown
            // is dispatched to the rescue scope.
            assertTrue(order.isEmpty())

            advanceUntilIdle()
            assertEquals(listOf("stop", "release", "rescue", "close", "focus"), order)
            assertEquals(listOf(0.1f, 0.2f), rescued?.toList())
        }

    @Test
    fun `async destroy teardown skips rescue when the capture was classified as a discard`() =
        runTest {
            val order = mutableListOf<String>()
            launchRecognitionDestroyTeardown(
                gateHeld = true,
                rescue = false,
                stopRecorder = {
                    order += "stop"
                    floatArrayOf(0.1f)
                },
                releaseGate = { order += "release" },
                rescueSamples = { order += "rescue" },
                closeRecorder = { order += "close" },
                abandonFocus = { order += "focus" },
            )
            advanceUntilIdle()
            // The recorder still stops and the gate is still released; only the rescue
            // persist is skipped for the classified discard.
            assertEquals(listOf("stop", "release", "close", "focus"), order)
        }

    @Test
    fun `destroy teardown without a held gate only closes the recorder and abandons focus`() =
        runTest {
            val order = mutableListOf<String>()
            launchRecognitionDestroyTeardown(
                gateHeld = false,
                rescue = false,
                stopRecorder = {
                    order += "stop"
                    FloatArray(0)
                },
                releaseGate = { order += "release" },
                rescueSamples = { order += "rescue" },
                closeRecorder = { order += "close" },
                abandonFocus = { order += "focus" },
            )
            advanceUntilIdle()
            assertEquals(listOf("close", "focus"), order)
        }
}
