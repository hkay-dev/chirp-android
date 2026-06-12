package dev.chirpboard.app.feature.keyboard.quickcapture

import android.content.Context
import android.widget.Toast
import dev.chirpboard.app.core.audio.AudioFocusManager
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.core.audio.recorder.VoiceRecorder
import dev.chirpboard.app.core.quickcapture.QuickCaptureStartResult
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingPermissionGuard
import dev.chirpboard.app.core.recording.RecordingStartResult
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import dev.chirpboard.app.feature.keyboard.R
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * TST-007: the keyboard mic-acquisition ladder in [QuickCaptureSessionImpl.start] — permission
 * gate, global single-recording lock with user-facing source labels, audio-focus denial, and a
 * recorder that refuses to start. Each failure must leave the shared state machine consistent
 * (error reported, focus abandoned) so the next dictation attempt is not wedged.
 */
class QuickCaptureSessionImplTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    private val context =
        mockk<Context>(relaxed = true) {
            every { getString(R.string.keyboard_mic_source_app) } returns "app"
            every { getString(R.string.keyboard_audio_busy) } returns "Another app is using audio"
            every { getString(R.string.keyboard_record_start_failed) } returns "Failed to start recording"
        }
    private val inputDeviceSelector = mockk<AudioInputDeviceSelector>(relaxed = true)
    private val recordingStateManager = mockk<RecordingStateManager>(relaxed = true)
    private val audioFocusManager = mockk<AudioFocusManager>(relaxed = true)

    @Before
    fun setup() {
        mockkConstructor(VoiceRecorder::class)
        mockkObject(RecordingPermissionGuard)
        mockkStatic(Toast::class)
        every { Toast.makeText(any(), any<CharSequence>(), any()) } returns mockk(relaxed = true)
        every { RecordingPermissionGuard.hasRecordAudioPermission(any()) } returns true
        every { recordingStateManager.tryStartRecording(RecordingOrigin.KEYBOARD, null) } returns
            RecordingStartResult.Success
        every { audioFocusManager.requestFocus() } returns AudioFocusManager.FocusResult.Granted
    }

    @After
    fun tearDown() {
        unmockkConstructor(VoiceRecorder::class)
        unmockkObject(RecordingPermissionGuard)
        unmockkStatic(Toast::class)
    }

    private fun createSession(scope: kotlinx.coroutines.CoroutineScope): QuickCaptureSessionImpl =
        QuickCaptureSessionImpl(
            context = context,
            scope = scope,
            inputDeviceSelector = inputDeviceSelector,
            recordingStateManager = recordingStateManager,
            audioFocusManager = audioFocusManager,
        )

    @Test
    fun `missing mic permission fails before touching the recording lock`() =
        runTest {
            every { RecordingPermissionGuard.hasRecordAudioPermission(any()) } returns false
            val session = createSession(this)

            val result = session.start()

            assertEquals(
                QuickCaptureStartResult.PermissionDenied(RecordingPermissionGuard.PERMISSION_DENIED_MESSAGE),
                result,
            )
            verify(exactly = 0) { recordingStateManager.tryStartRecording(any(), null) }
            verify(exactly = 0) { audioFocusManager.requestFocus() }
        }

    @Test
    fun `mic held by another origin maps to its user-facing source label`() =
        runTest {
            every { recordingStateManager.tryStartRecording(RecordingOrigin.KEYBOARD, null) } returns
                RecordingStartResult.AlreadyRecording(currentOrigin = RecordingOrigin.APP)
            val session = createSession(this)

            val result = session.start()

            assertEquals(QuickCaptureStartResult.AlreadyRecording(sourceLabel = "app"), result)
            // The refusal never requests focus or starts the recorder.
            verify(exactly = 0) { audioFocusManager.requestFocus() }
        }

    @Test
    fun `denied audio focus reports a recording error and fails the start`() =
        runTest {
            every { audioFocusManager.requestFocus() } returns AudioFocusManager.FocusResult.Denied
            val session = createSession(this)

            val result = session.start()

            assertEquals(
                QuickCaptureStartResult.AudioFocusDenied("Another app is using audio"),
                result,
            )
            // The shared state machine must learn about the failure so the lock taken by
            // tryStartRecording is released for the next attempt.
            verify { recordingStateManager.onRecordingError("Another app is using audio", null) }
        }

    @Test
    fun `recorder refusing to start abandons focus and reports the error`() =
        runTest {
            coEvery { anyConstructed<VoiceRecorder>().start() } returns false
            val session = createSession(this)

            val result = session.start()

            assertEquals(
                QuickCaptureStartResult.Failed("Failed to start recording"),
                result,
            )
            verify { audioFocusManager.abandonFocus() }
            verify { recordingStateManager.onRecordingError("Failed to start recording", null) }
        }

    @Test
    fun `successful start marks the session recording`() =
        runTest {
            coEvery { anyConstructed<VoiceRecorder>().start() } returns true
            val session = createSession(this)

            val result = session.start()

            assertTrue(result is QuickCaptureStartResult.Success)
            verify { recordingStateManager.onRecordingStarted("keyboard_temp_recording", null) }
            verify(exactly = 0) { audioFocusManager.abandonFocus() }
        }

    @Test
    fun `close abandons audio focus before closing the recorder`() =
        runTest {
            justRun { anyConstructed<VoiceRecorder>().close() }
            val session = createSession(this)

            session.close()

            verifyOrder {
                audioFocusManager.abandonFocus()
                anyConstructed<VoiceRecorder>().close()
            }
        }
}
