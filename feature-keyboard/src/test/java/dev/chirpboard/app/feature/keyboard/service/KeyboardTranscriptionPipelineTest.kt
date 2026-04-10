package dev.chirpboard.app.feature.keyboard.service

import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.keyboard.KeyboardPreferences
import dev.chirpboard.app.feature.keyboard.recorder.AudioEncoder
import dev.chirpboard.app.feature.keyboard.state.KeyboardState
import dev.chirpboard.app.feature.llm.TextProcessor
import dev.chirpboard.app.feature.obsidian.settings.ObsidianPreferences
import dev.chirpboard.app.feature.obsidian.ObsidianManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import dev.chirpboard.app.feature.llm.model.ProcessingMode
import io.mockk.just
import io.mockk.runs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardTranscriptionPipelineTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var recognizerProvider: TranscriberProvider
    private lateinit var textProcessor: TextProcessor
    private lateinit var keyboardPreferences: KeyboardPreferences
    private lateinit var persistenceScope: CoroutineScope
    private lateinit var filesDirProvider: () -> File
    private lateinit var audioEncoder: AudioEncoder
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var obsidianPreferences: ObsidianPreferences
    private lateinit var obsidianManager: ObsidianManager
    private lateinit var pipeline: KeyboardTranscriptionPipeline

    private var bufferedSamples: FloatArray? = FloatArray(10) { it.toFloat() }
    private var persistenceJob: Job? = null

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        recognizerProvider = mockk(relaxed = true)
        textProcessor = mockk(relaxed = true)
        keyboardPreferences = mockk(relaxed = true)
        persistenceScope = CoroutineScope(testDispatcher)
        filesDirProvider = { File("/dev/null") }
        audioEncoder = mockk(relaxed = true)
        recordingRepository = mockk(relaxed = true)
        obsidianPreferences = mockk(relaxed = true)
        obsidianManager = mockk(relaxed = true)
        mockkObject(ReliabilityEventLogger)
        every { ReliabilityEventLogger.newCorrelationId(any()) } returns "test-corr-id"
        every { ReliabilityEventLogger.log(any(), any(), any(), any(), any(), any()) } just runs

        every { keyboardPreferences.saveKeyboardRecordings } returns flowOf(true)

        pipeline = KeyboardTranscriptionPipeline(
            tag = "TestTag",
            recognizerProvider = recognizerProvider,
            textProcessor = textProcessor,
            keyboardPreferences = keyboardPreferences,
            persistenceScope = persistenceScope,
            filesDirProvider = filesDirProvider,
            audioEncoder = audioEncoder,
            recordingRepository = recordingRepository,
            obsidianPreferences = obsidianPreferences,
            obsidianManager = obsidianManager,
            getBufferedSamples = { bufferedSamples },
            setBufferedSamples = { bufferedSamples = it },
            getPersistenceJob = { persistenceJob },
            setPersistenceJob = { persistenceJob = it }
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkObject(ReliabilityEventLogger)
    }

    @Test
    fun `run with recognizer not ready emits error and persists`() = runTest(testDispatcher) {
        every { recognizerProvider.isReady() } returns false
        
        var stateEmitted: KeyboardState? = null
        pipeline.run(
            samples = FloatArray(10),
            currentMode = ProcessingMode.Proofread,
            llmEnabled = false,
            commitText = {},
            onStateChanged = { stateEmitted = it },
            onRecordingCompleted = {},
            onRecordingError = {}
        )
        
        assertEquals(KeyboardState.Error("Recognizer not ready"), stateEmitted)
        assertEquals(null, bufferedSamples) // Cleared after persistence
    }

    @Test
    fun `run with no speech discards capture and completes`() = runTest(testDispatcher) {
        every { recognizerProvider.isReady() } returns true
        coEvery { recognizerProvider.transcribe(any(), any()) } returns TranscriptionOutcome.NoSpeech
        
        var stateEmitted: KeyboardState? = null
        var completed = false
        pipeline.run(
            samples = FloatArray(10),
            currentMode = ProcessingMode.Proofread,
            llmEnabled = false,
            commitText = {},
            onStateChanged = { stateEmitted = it },
            onRecordingCompleted = { completed = true },
            onRecordingError = {}
        )
        
        assertEquals(KeyboardState.Idle, stateEmitted)
        assertEquals(true, completed)
        assertEquals(null, bufferedSamples) // Discarded
    }

    @Test
    fun `run with successful transcription commits text without llm`() = runTest(testDispatcher) {
        every { recognizerProvider.isReady() } returns true
        coEvery { recognizerProvider.transcribe(any(), any()) } returns TranscriptionOutcome.Success("hello test")
        
        var stateEmitted: KeyboardState? = null
        var committedText: String? = null
        pipeline.run(
            samples = FloatArray(10),
            currentMode = ProcessingMode.Proofread,
            llmEnabled = false,
            commitText = { committedText = it },
            onStateChanged = { stateEmitted = it },
            onRecordingCompleted = {},
            onRecordingError = {}
        )
        
        assertEquals(KeyboardState.Idle, stateEmitted)
        assertEquals("hello test ", committedText)
        assertEquals(null, bufferedSamples) // Persisted
    }

    @Test
    fun `run with successful transcription and llm commits polished text`() = runTest(testDispatcher) {
        every { recognizerProvider.isReady() } returns true
        coEvery { recognizerProvider.transcribe(any(), any()) } returns TranscriptionOutcome.Success("hello test")
        coEvery { textProcessor.process(any(), any()) } returns Result.success("Hello, test.")
        
        var stateEmitted: KeyboardState? = null
        var committedText: String? = null
        pipeline.run(
            samples = FloatArray(10),
            currentMode = ProcessingMode.Proofread,
            llmEnabled = true,
            commitText = { committedText = it },
            onStateChanged = { stateEmitted = it },
            onRecordingCompleted = {},
            onRecordingError = {}
        )
        
        assertEquals(KeyboardState.Idle, stateEmitted)
        assertEquals("Hello, test. ", committedText)
        assertEquals(null, bufferedSamples) // Persisted
    }
}
