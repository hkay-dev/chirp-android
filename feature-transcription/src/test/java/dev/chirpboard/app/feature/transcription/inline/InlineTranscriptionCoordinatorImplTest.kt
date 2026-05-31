package dev.chirpboard.app.feature.transcription.inline

import dev.chirpboard.app.core.llm.RecordingTextEnhancementPort
import dev.chirpboard.app.core.modelreadiness.ModelReadyResult
import dev.chirpboard.app.core.modelreadiness.ModelReadinessVerificationSource
import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.modelreadiness.VerificationTrigger
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import dev.chirpboard.app.core.transcription.InlineAudioSource
import dev.chirpboard.app.core.transcription.InlineCapturePersistence
import dev.chirpboard.app.core.transcription.InlineTranscriptionRequest
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalCoroutinesApi::class)
class InlineTranscriptionCoordinatorImplTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var transcriberProvider: TranscriberProvider
    private lateinit var textEnhancement: RecordingTextEnhancementPort
    private lateinit var readinessGate: SpeechModelReadinessGate
    private lateinit var coordinator: InlineTranscriptionCoordinatorImpl

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        transcriberProvider = mockk()
        textEnhancement = mockk(relaxed = true)
        readinessGate = mockk(relaxed = true)
        coordinator = InlineTranscriptionCoordinatorImpl(transcriberProvider, textEnhancement, readinessGate)

        mockkObject(ReliabilityEventLogger)
        every { ReliabilityEventLogger.newCorrelationId(any()) } returns "inline-test"
        every { ReliabilityEventLogger.log(any(), any(), any(), any(), any(), any()) } just runs
    }

    @After
    fun tearDown() {
        unmockkObject(ReliabilityEventLogger)
        Dispatchers.resetMain()
    }

    @Test
    fun `keyboard dictation readiness path asks gate before initializing model`() = runTest {
        every { transcriberProvider.isReady() } returns false andThen false andThen true
        every { transcriberProvider.isModelDownloaded() } returns true
        coEvery { readinessGate.ensureReady(VerificationTrigger.KEYBOARD_DICTATION) } returns
            ModelReadyResult.Ready(ModelReadinessVerificationSource.PROCESS_CACHE)
        coEvery { transcriberProvider.initialize() } returns true
        coEvery { transcriberProvider.transcribe(any(), any()) } returns TranscriptionOutcome.Success("hello")

        coordinator.transcribe(
            request =
                InlineTranscriptionRequest(
                    samples = floatArrayOf(0.1f, 0.2f),
                    llmEnabled = false,
                    processingModeId = "proofread",
                ),
            persistence = CapturingPersistence(),
            commitText = {},
        )
        coVerify { readinessGate.ensureReady(VerificationTrigger.KEYBOARD_DICTATION) }
    }

    @Test
    fun `file backed dictation is transcribed in bounded chunks`() = runTest {
        val sampleCount = 480_000 + 4_000
        val file = temporaryFolder.newFile("long-dictation.f32pcm")
        writeFloatPcm(file, sampleCount)
        every { transcriberProvider.isReady() } returns true
        var calls = 0
        coEvery { transcriberProvider.transcribe(any(), any()) } coAnswers {
            calls++
            TranscriptionOutcome.Success("chunk$calls")
        }

        var committed = ""
        coordinator.transcribe(
            request =
                InlineTranscriptionRequest(
                    samples = FloatArray(0),
                    llmEnabled = false,
                    processingModeId = "proofread",
                    audioSource =
                        InlineAudioSource.PcmFloatFile(
                            path = file.absolutePath,
                            sampleCount = sampleCount.toLong(),
                        ),
                ),
            persistence = CapturingPersistence(),
            commitText = { committed = it },
        )
        assertEquals("chunk1 chunk2 ", committed)
        assertEquals(2, calls)
    }

    private fun writeFloatPcm(
        file: java.io.File,
        sampleCount: Int,
    ) {
        file.outputStream().use { output ->
            val buffer = ByteBuffer.allocate(4_096 * java.lang.Float.BYTES).order(ByteOrder.LITTLE_ENDIAN)
            var written = 0
            while (written < sampleCount) {
                buffer.clear()
                val count = minOf(4_096, sampleCount - written)
                repeat(count) { index ->
                    buffer.putFloat(((written + index) % 100) / 100f)
                }
                output.write(buffer.array(), 0, count * java.lang.Float.BYTES)
                written += count
            }
        }
    }

    private class CapturingPersistence : InlineCapturePersistence {
        override suspend fun persist(
            samples: FloatArray?,
            rawText: String?,
            processedText: String?,
            errorMessage: String?,
        ) = Unit

        override fun discardSamples() = Unit
    }
}
