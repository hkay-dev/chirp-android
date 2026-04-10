package dev.chirpboard.app.feature.recording.ui

import android.content.Context
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.feature.recording.service.RecordingService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.UUID

class RecordViewModelTest {
    private lateinit var context: Context
    private lateinit var recordingStateManager: RecordingStateManager
    private lateinit var viewModel: RecordViewModel

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        recordingStateManager =
            mockk(relaxed = true) {
                every { state } returns MutableStateFlow(RecordingState())
                every { waveformBuffer } returns dev.chirpboard.app.core.recording.WaveformBuffer(1000)
                every { amplitudeFlow } returns MutableStateFlow(0f)
                every { lastCompletedRecordingId } returns MutableStateFlow(null)
            }

        mockkObject(RecordingService)
        every { RecordingService.startRecording(any(), any(), any()) } returns Unit
        every { RecordingService.pauseRecording(any()) } returns Unit
        every { RecordingService.resumeRecording(any()) } returns Unit
        every { RecordingService.stopRecording(any()) } returns Unit
        every { RecordingService.cancelRecording(any()) } returns Unit
        every { RecordingService.restartRecording(any(), any(), any()) } returns Unit

        viewModel = RecordViewModel(context, recordingStateManager)
    }

    @After
    fun teardown() {
        unmockkObject(RecordingService)
    }

    @Test
    fun `viewModel exposes stateManager flows`() {
        assertEquals(0f, viewModel.currentAmplitude.value)
        assertEquals(0, viewModel.waveformBuffer.count)
    }

    @Test
    fun `startRecording calls service`() {
        val profileId = UUID.randomUUID()
        viewModel.startRecording(profileId)

        verify { RecordingService.startRecording(context, RecordingOrigin.APP, profileId) }
    }

    @Test
    fun `pauseRecording calls service`() {
        viewModel.pauseRecording()
        verify { RecordingService.pauseRecording(context) }
    }

    @Test
    fun `resumeRecording calls service`() {
        viewModel.resumeRecording()
        verify { RecordingService.resumeRecording(context) }
    }

    @Test
    fun `stopRecording calls service`() {
        viewModel.stopRecording()
        verify { RecordingService.stopRecording(context) }
    }

    @Test
    fun `cancelRecording calls service`() {
        viewModel.cancelRecording()
        verify { RecordingService.cancelRecording(context) }
    }

    @Test
    fun `restartRecording calls service`() {
        val profileId = UUID.randomUUID()
        viewModel.restartRecording(profileId)
        verify { RecordingService.restartRecording(context, RecordingOrigin.APP, profileId) }
    }

    @Test
    fun `clearLastCompletedRecordingId calls state manager`() {
        viewModel.clearLastCompletedRecordingId()
        verify { recordingStateManager.clearLastCompletedRecordingId() }
    }
}
