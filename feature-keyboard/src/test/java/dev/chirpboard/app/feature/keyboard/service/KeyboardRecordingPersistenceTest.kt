package dev.chirpboard.app.feature.keyboard.service

import dev.chirpboard.app.core.audio.RecordingQualityPreset
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.core.audio.recorder.AudioEncoder
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import dev.chirpboard.app.feature.keyboard.testing.MockAndroidLogRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardRecordingPersistenceTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `saveKeyboardRecording uses selected quality preset`() =
        runTest {
            val outputPath = slot<String>()
            val audioEncoder = mockk<AudioEncoder>()
            val recordingRepository = mockk<RecordingRepository>(relaxed = true)
            every {
                audioEncoder.encodeToM4a(
                    samples = any(),
                    sampleRate = any(),
                    outputPath = capture(outputPath),
                    config = RecordingQualityPreset.Low.keyboardRecordingConfig,
                )
            } returns true

            val recording =
                saveKeyboardRecording(
                    filesDir = temporaryFolder.root,
                    audioEncoder = audioEncoder,
                    recordingRepository = recordingRepository,
                    persistencePlan =
                        buildKeyboardPersistencePlan(
                            rawText = "hello world",
                            processedText = "hello world",
                            errorMessage = null,
                        ),
                    samples = floatArrayOf(0.1f, -0.1f, 0.2f),
                    recordingQualityPreset = RecordingQualityPreset.Low,
                )

            assertNotNull(recording)
            verify {
                audioEncoder.encodeToM4a(
                    samples = any(),
                    sampleRate = any(),
                    outputPath = any(),
                    config = RecordingQualityPreset.Low.keyboardRecordingConfig,
                )
            }
            assertTrue(outputPath.captured.endsWith(".m4a"))
        }

    @Test
    fun `saveKeyboardRecording removes partial file when encoding fails`() =
        runTest {
            val outputPath = slot<String>()
            val audioEncoder = mockk<AudioEncoder>()
            val recordingRepository = mockk<RecordingRepository>(relaxed = true)
            every {
                audioEncoder.encodeToM4a(
                    samples = any(),
                    sampleRate = any(),
                    outputPath = capture(outputPath),
                    config = RecordingQualityPreset.High.keyboardRecordingConfig,
                )
            } answers {
                File(outputPath.captured).apply {
                    parentFile?.mkdirs()
                    writeText("partial")
                }
                false
            }

            val recording =
                saveKeyboardRecording(
                    filesDir = temporaryFolder.root,
                    audioEncoder = audioEncoder,
                    recordingRepository = recordingRepository,
                    persistencePlan =
                        buildKeyboardPersistencePlan(
                            rawText = "hello world",
                            processedText = "hello world",
                            errorMessage = null,
                        ),
                    samples = floatArrayOf(0.1f, -0.1f, 0.2f),
                    recordingQualityPreset = RecordingQualityPreset.High,
                )

            assertNull(recording)
            assertFalse(File(outputPath.captured).exists())
            coVerify(exactly = 0) { recordingRepository.insert(any()) }
            coVerify(exactly = 0) { recordingRepository.createRecordingWithTranscript(any(), any()) }
        }
}
