package dev.chirpboard.app.core.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import dev.chirpboard.app.core.audio.AudioSettingsStore
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingStateManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingPlaybackControllerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val recordingStateManager = RecordingStateManager()
    private val audioSettingsStore = mockk<AudioSettingsStore>(relaxed = true)
    private val listenerSlot = slot<Player.Listener>()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // The controller restores this once at startup; the relaxed default (0f) would
        // otherwise snap to the lowest supported speed.
        coEvery { audioSettingsStore.currentPlaybackSpeed() } returns 1f
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
        unmockkAll()
    }

    @Test
    fun initialState_isIdle() {
        val controller = controller(testContext())
        assertTrue(controller.state.value.isIdle)
    }

    @Test
    fun prepare_missingAudioFile_doesNotStartForegroundService() {
        val context = testContext()
        val controller = controller(context)
        val recordingId = UUID.randomUUID()

        controller.prepare(recordingId, "Missing clip", "/does/not/exist.m4a")

        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun prepare_missingAudioFile_surfacesErrorAndStaysInactive() {
        val controller = controller(testContext())
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
    fun missingFile_marksTheSessionStartedOnlyWhenTheUserAskedToPlay() {
        val controller = controller(testContext())

        controller.prepare(UUID.randomUUID(), "Missing clip", "/does/not/exist.m4a")
        // A Studio prepare the user never played must not earn a global error bar.
        assertFalse(controller.state.value.hasStartedPlayback)

        controller.play(UUID.randomUUID(), "Missing clip", "/does/not/exist.m4a")
        assertTrue(controller.state.value.hasStartedPlayback)
    }

    @Test
    fun pauseIfDifferentRecording_noOpWhenIdle() {
        val controller = controller(testContext())
        controller.pauseIfDifferentRecording(UUID.randomUUID())
        assertTrue(controller.state.value.isIdle)
    }

    @Test
    fun pauseIfDifferentRecording_noOpWhenSameRecordingPrepared() {
        val controller = controller(testContext())
        val recordingId = UUID.randomUUID()

        controller.prepare(recordingId, "Same clip", "/does/not/exist.m4a")
        controller.pauseIfDifferentRecording(recordingId)

        assertEquals(recordingId, controller.state.value.recordingId)
    }

    @Test
    fun stop_clearsActivePlaybackState() {
        val controller = controller(testContext())
        val recordingId = UUID.randomUUID()

        controller.prepare(recordingId, "Missing clip", "/does/not/exist.m4a")
        controller.stop()

        assertTrue(controller.state.value.isIdle)
    }

    @Test
    fun play_refusedWithMessageWhileRecordingIsActive() {
        val context = testContext()
        every { context.getString(R.string.playback_blocked_while_recording) } returns BLOCKED_MESSAGE
        val controller = controller(context)
        val recordingId = UUID.randomUUID()
        val audioFile = temporaryFolder.newFile("clip.m4a")
        recordingStateManager.tryStartRecording(RecordingOrigin.APP)

        controller.play(recordingId, "Clip", audioFile.absolutePath)

        val state = controller.state.value
        assertEquals(BLOCKED_MESSAGE, state.errorMessage)
        assertEquals(recordingId, state.recordingId)
        assertFalse(state.isPlaying)
        // The refusal must never touch the media session (no focus request).
        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun play_notRefusedAfterRecordingEnds() {
        val context = testContext()
        every { context.getString(R.string.playback_blocked_while_recording) } returns BLOCKED_MESSAGE
        val controller = controller(context)
        recordingStateManager.tryStartRecording(RecordingOrigin.APP)
        recordingStateManager.forceCancel()

        controller.play(UUID.randomUUID(), "Clip", "/does/not/exist.m4a")

        // Falls through the recording guard into normal validation.
        assertEquals("Audio file not found", controller.state.value.errorMessage)
    }

    @Test
    fun setPlaybackSpeed_snapsToSupportedOptionAndPersists() {
        val controller = controller(testContext())

        controller.setPlaybackSpeed(1.3f)

        assertEquals(1.25f, controller.state.value.playbackSpeed)
        coVerify { audioSettingsStore.setPlaybackSpeed(1.25f) }
    }

    @Test
    fun cyclePlaybackSpeed_advancesThroughOptionsAndWraps() {
        val controller = controller(testContext())

        controller.cyclePlaybackSpeed()
        assertEquals(1.25f, controller.state.value.playbackSpeed)
        controller.cyclePlaybackSpeed()
        assertEquals(1.5f, controller.state.value.playbackSpeed)
        controller.cyclePlaybackSpeed()
        assertEquals(2.0f, controller.state.value.playbackSpeed)
        controller.cyclePlaybackSpeed()
        assertEquals(0.75f, controller.state.value.playbackSpeed)
    }

    @Test
    fun stop_preservesPlaybackSpeed() {
        val controller = controller(testContext())
        controller.setPlaybackSpeed(2.0f)

        controller.stop()

        assertEquals(2.0f, controller.state.value.playbackSpeed)
    }

    @Test
    fun play_refusalWhileRecording_preservesSelectedPlaybackSpeed() {
        val context = testContext()
        every { context.getString(R.string.playback_blocked_while_recording) } returns BLOCKED_MESSAGE
        val controller = controller(context)
        controller.setPlaybackSpeed(1.5f)
        recordingStateManager.tryStartRecording(RecordingOrigin.APP)

        controller.play(UUID.randomUUID(), "Clip", "/does/not/exist.m4a")

        val state = controller.state.value
        assertEquals(BLOCKED_MESSAGE, state.errorMessage)
        assertEquals(1.5f, state.playbackSpeed)
    }

    @Test
    fun togglePlayPause_whileRecordingIsActive_staysRefused() {
        val context = testContext()
        every { context.getString(R.string.playback_blocked_while_recording) } returns BLOCKED_MESSAGE
        val controller = controller(context)
        val recordingId = UUID.randomUUID()
        val audioFile = temporaryFolder.newFile("toggle.m4a")
        recordingStateManager.tryStartRecording(RecordingOrigin.APP)
        controller.play(recordingId, "Clip", audioFile.absolutePath)

        // The user taps play/pause again while the recorder still owns the mic: the
        // toggle re-routes through play() and must hit the same gate, never the session.
        controller.togglePlayPause()

        val state = controller.state.value
        assertEquals(BLOCKED_MESSAGE, state.errorMessage)
        assertFalse(state.isPlaying)
        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun releaseIfNeverPlayed_clearsAStudioPrepareThatNeverPlayed() {
        val controller = controller(testContext())
        val recordingId = UUID.randomUUID()

        controller.onStudioOpened(recordingId, "Clip", "/does/not/exist.m4a")
        controller.releaseIfNeverPlayed(recordingId)

        assertTrue(controller.state.value.isIdle)
    }

    @Test
    fun releaseIfNeverPlayed_keepsPlaybackTheUserStarted() {
        val context = testContext()
        every { context.getString(R.string.playback_blocked_while_recording) } returns BLOCKED_MESSAGE
        val controller = controller(context)
        val recordingId = UUID.randomUUID()
        recordingStateManager.tryStartRecording(RecordingOrigin.APP)
        // Refused, but hasStartedPlayback is set: the user asked for this one.
        controller.play(recordingId, "Clip", "/does/not/exist.m4a")

        controller.releaseIfNeverPlayed(recordingId)

        assertEquals(recordingId, controller.state.value.recordingId)
    }

    @Test
    fun releaseIfNeverPlayed_ignoresADifferentRecording() {
        val controller = controller(testContext())
        val prepared = UUID.randomUUID()

        controller.onStudioOpened(prepared, "Clip", "/does/not/exist.m4a")
        controller.releaseIfNeverPlayed(UUID.randomUUID())

        assertEquals(prepared, controller.state.value.recordingId)
    }

    @Test
    fun strayPlayerCallback_doesNotClobberAnErrorRaisedForAnotherRecording() {
        val context = testContext()
        every { context.getString(R.string.playback_blocked_while_recording) } returns BLOCKED_MESSAGE
        val controller = controller(context)
        val playing = UUID.randomUUID()
        val refused = UUID.randomUUID()
        val player = fakePlayer(playing)
        controller.connectController = { player }

        controller.prepare(playing, "Playing clip", temporaryFolder.newFile("a.m4a").absolutePath)
        assertEquals(playing, controller.state.value.recordingId)

        // A capture starts, the user taps play on a second recording: the refusal is shown
        // for `refused` while the player still holds `playing`.
        recordingStateManager.tryStartRecording(RecordingOrigin.APP)
        controller.play(refused, "Refused clip", "/does/not/exist.m4a")
        assertEquals(refused, controller.state.value.recordingId)
        assertEquals(BLOCKED_MESSAGE, controller.state.value.errorMessage)

        // The position ticker and the callbacks from pausing the old item land ~100ms later.
        capturedListener().onPlaybackStateChanged(Player.STATE_READY)

        val state = controller.state.value
        assertEquals(refused, state.recordingId)
        assertEquals(BLOCKED_MESSAGE, state.errorMessage)
    }

    @Test
    fun setPlaybackSpeed_persistenceFailure_stillAppliesTheSpeed() {
        coEvery { audioSettingsStore.setPlaybackSpeed(any()) } throws RuntimeException("datastore offline")
        val controller = controller(testContext())

        controller.setPlaybackSpeed(1.5f)

        assertEquals(1.5f, controller.state.value.playbackSpeed)
    }

    /**
     * A connected MediaController that keeps reporting [recordingId] as its loaded item, so a
     * stray callback fired after an unrelated failure syncs the *old* recording.
     */
    private fun fakePlayer(recordingId: UUID): MediaController {
        val uri = mockk<Uri>(relaxed = true)
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns uri
        val mediaItem =
            MediaItem.Builder()
                .setMediaId(recordingId.toString())
                .setUri(uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle("Playing clip").build())
                .build()
        return mockk(relaxed = true) {
            every { isConnected } returns true
            every { currentMediaItem } returns mediaItem
            every { mediaItemCount } returns 1
            every { playerError } returns null
            every { playbackState } returns Player.STATE_READY
            every { isPlaying } returns false
            every { duration } returns 1_000L
            every { currentPosition } returns 0L
            every { playbackParameters } returns PlaybackParameters(1f)
            every { addListener(capture(listenerSlot)) } returns Unit
        }
    }

    private fun capturedListener(): Player.Listener = listenerSlot.captured

    private fun controller(context: Context): RecordingPlaybackController =
        RecordingPlaybackController(context, recordingStateManager, audioSettingsStore)
            // Unconfined so the file-validation hop resolves before the test asserts.
            .apply { ioDispatcher = UnconfinedTestDispatcher() }

    private fun testContext(): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getString(R.string.playback_error_file_missing) } returns FILE_MISSING_MESSAGE
        return context
    }

    private companion object {
        const val BLOCKED_MESSAGE = "Can't play audio while a recording is in progress"
        const val FILE_MISSING_MESSAGE = "Audio file not found"
    }
}
