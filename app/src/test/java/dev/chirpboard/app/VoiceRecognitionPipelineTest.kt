package dev.chirpboard.app

import android.speech.SpeechRecognizer
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import dev.chirpboard.app.feature.llm.TextProcessor
import dev.chirpboard.app.feature.llm.model.ProcessingMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoiceRecognitionPipelineTest {
    private lateinit var transcriberProvider: TranscriberProvider
    private lateinit var textProcessor: TextProcessor
    private lateinit var pipeline: VoiceRecognitionPipeline

    @Before
    fun setUp() {
        transcriberProvider = mockk()
        textProcessor = mockk()
        pipeline =
            VoiceRecognitionPipeline(
                tag = "TestPipeline",
                transcriberProvider = transcriberProvider,
                textProcessor = textProcessor,
            )
    }

    @Test
    fun `process with empty samples returns ERROR_NO_MATCH`() =
        runTest {
            val result =
                pipeline.process(
                    samples = FloatArray(0),
                    llmEnabled = false,
                    processingMode = ProcessingMode.Proofread,
                    onPartialTranscript = {},
                )
            assertTrue(result is VoiceRecognitionPipelineResult.Error)
            assertEquals(SpeechRecognizer.ERROR_NO_MATCH, (result as VoiceRecognitionPipelineResult.Error).code)
        }

    @Test
    fun `process when transcriber not ready returns ERROR_RECOGNIZER_BUSY`() =
        runTest {
            every { transcriberProvider.isReady() } returns false

            val result =
                pipeline.process(
                    samples = floatArrayOf(0.1f, 0.2f),
                    llmEnabled = false,
                    processingMode = ProcessingMode.Proofread,
                    onPartialTranscript = {},
                )

            assertTrue(result is VoiceRecognitionPipelineResult.Error)
            assertEquals(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, (result as VoiceRecognitionPipelineResult.Error).code)
        }

    @Test
    fun `process with no speech returns ERROR_NO_MATCH`() =
        runTest {
            every { transcriberProvider.isReady() } returns true
            coEvery { transcriberProvider.transcribe(any()) } returns TranscriptionOutcome.NoSpeech

            val result =
                pipeline.process(
                    samples = floatArrayOf(0.1f, 0.2f),
                    llmEnabled = false,
                    processingMode = ProcessingMode.Proofread,
                    onPartialTranscript = {},
                )

            assertTrue(result is VoiceRecognitionPipelineResult.Error)
            assertEquals(SpeechRecognizer.ERROR_NO_MATCH, (result as VoiceRecognitionPipelineResult.Error).code)
        }

    @Test
    fun `process with successful transcription and llm disabled returns success`() =
        runTest {
            every { transcriberProvider.isReady() } returns true
            coEvery { transcriberProvider.transcribe(any()) } returns TranscriptionOutcome.Success("hello world")

            var partial = ""
            val result =
                pipeline.process(
                    samples = floatArrayOf(0.1f, 0.2f),
                    llmEnabled = false,
                    processingMode = ProcessingMode.Proofread,
                    onPartialTranscript = { partial = it },
                )

            assertEquals("hello world", partial)
            assertTrue(result is VoiceRecognitionPipelineResult.Success)
            assertEquals("hello world ", (result as VoiceRecognitionPipelineResult.Success).text)
        }

    @Test
    fun `process with successful transcription and llm enabled returns processed text`() =
        runTest {
            every { transcriberProvider.isReady() } returns true
            coEvery { transcriberProvider.transcribe(any()) } returns TranscriptionOutcome.Success("hello")
            coEvery { textProcessor.process("hello", ProcessingMode.Proofread) } returns Result.success("HELLO")

            val result =
                pipeline.process(
                    samples = floatArrayOf(0.1f, 0.2f),
                    llmEnabled = true,
                    processingMode = ProcessingMode.Proofread,
                    onPartialTranscript = {},
                )

            assertTrue(result is VoiceRecognitionPipelineResult.Success)
            assertEquals("HELLO ", (result as VoiceRecognitionPipelineResult.Success).text)
            coVerify { textProcessor.process("hello", ProcessingMode.Proofread) }
        }
}
