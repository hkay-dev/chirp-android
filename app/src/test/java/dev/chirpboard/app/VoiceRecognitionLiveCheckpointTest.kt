package dev.chirpboard.app

import dev.chirpboard.app.core.audio.recorder.VoiceRecorder
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.transcription.InlineAudioSource
import dev.chirpboard.app.core.transcription.InlineCapturePersistence
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRecognitionLiveCheckpointTest {
    @Test
    fun `recorder failure can finalize audio from the first-sample start window`() {
        val starting = RecordingState.Starting(RecordingOrigin.KEYBOARD)

        assertTrue(canFinalizeRecognitionCapture(starting, recorderFailed = true))
        assertFalse(canFinalizeRecognitionCapture(starting, recorderFailed = false))
        assertTrue(
            canFinalizeRecognitionCapture(
                RecordingState.Recording(RecordingOrigin.KEYBOARD),
                recorderFailed = false,
            ),
        )
    }

    @Test
    fun `first durable block checkpoints the exact file and trusted sample count`() =
        runTest {
            val persistence = mockk<InlineCapturePersistence>()
            val file = File("/tmp/quick-input-live.f32pcm")
            val capture = VoiceRecorder.CapturedPcmFloatFile(file, sampleRate = 16_000, sampleCount = 1_024)
            coEvery { persistence.checkpointAudioSource(any(), any(), any(), any()) } returns true

            assertTrue(checkpointRecognitionAudio(capture, persistence))

            coVerify(exactly = 1) {
                persistence.checkpointAudioSource(
                    audioSource = InlineAudioSource.PcmFloatFile(file.absolutePath, 1_024L, 16_000),
                    trustedSampleCount = 1_024L,
                    partialTranscript = null,
                    estimatedGapMs = null,
                )
            }
        }
}
