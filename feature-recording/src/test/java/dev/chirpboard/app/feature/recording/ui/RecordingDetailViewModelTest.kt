package dev.chirpboard.app.feature.recording.ui

import androidx.lifecycle.SavedStateHandle
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.recording.audio.AudioPlayer
import dev.chirpboard.app.core.transcription.TranscriptionRecovery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingDetailViewModelTest {
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var audioPlayer: AudioPlayer
    private lateinit var transcriptionQueueManager: TranscriptionRecovery
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        recordingRepository = mockk(relaxed = true)
        audioPlayer = mockk(relaxed = true)
        transcriptionQueueManager = mockk(relaxed = true)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `viewModel can be initialized`() {
        val savedStateHandle = SavedStateHandle(mapOf("recordingId" to UUID.randomUUID().toString()))
        val viewModel =
            RecordingDetailViewModel(
                savedStateHandle,
                recordingRepository,
                audioPlayer,
                transcriptionQueueManager,
            )
        assertNotNull(viewModel)
    }
}
