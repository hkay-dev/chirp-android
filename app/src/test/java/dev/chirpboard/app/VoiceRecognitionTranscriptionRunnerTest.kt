package dev.chirpboard.app

import dev.chirpboard.app.core.transcription.InlineAudioSource
import dev.chirpboard.app.core.transcription.InlineCapturePersistReason
import dev.chirpboard.app.core.transcription.InlineCapturePersistence
import dev.chirpboard.app.core.transcription.InlineTranscriptionPhase
import dev.chirpboard.app.core.transcription.InlineTranscriptionPort
import dev.chirpboard.app.feature.transcription.QuickInputResultNotificationPublisher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceRecognitionTranscriptionRunnerTest {
    @Test
    fun `floating review request does not publish a result notification`() =
        runBlocking {
            val transcription = mockk<InlineTranscriptionPort>()
            val persistence = mockk<InlineCapturePersistence>()
            val notification = mockk<QuickInputResultNotificationPublisher>()

            every { transcription.phase } returns MutableStateFlow(InlineTranscriptionPhase.Idle)
            coEvery { transcription.transcribe(any(), any(), any(), any(), any()) } coAnswers {
                arg<(String) -> Unit>(2)("copied result")
            }

            val outcome =
                VoiceRecognitionTranscriptionRunner(transcription, persistence, notification)
                    .start(
                        VoiceRecognitionTranscriptionRunner.Request(
                            audioSource = InlineAudioSource.InMemory(floatArrayOf(1f)),
                            llmEnabled = false,
                            processingModeId = "proofread",
                            secure = false,
                            publishNotification = false,
                        ),
                    ).result.await()

            assertEquals("copied result", outcome.committedText)
            coVerify(exactly = 0) { notification.show(any(), any()) }
        }

    @Test
    fun `completion survives a cancelled activity waiter and notifies after persistence`() =
        runBlocking {
            val transcription = mockk<InlineTranscriptionPort>()
            val persistence = mockk<InlineCapturePersistence>()
            val notification = mockk<QuickInputResultNotificationPublisher>()
            val phase = MutableStateFlow<InlineTranscriptionPhase>(InlineTranscriptionPhase.Idle)
            val transcribeGate = CompletableDeferred<Unit>()
            val notificationPosted = CompletableDeferred<Unit>()
            val order = CopyOnWriteArrayList<String>()

            every { transcription.phase } returns phase
            coEvery { persistence.checkpointAudioSource(any(), any(), any(), any()) } coAnswers {
                order += "checkpoint"
                true
            }
            coEvery { persistence.persistAudioSource(any(), any(), any(), any(), any()) } coAnswers {
                order += "persist"
            }
            coEvery { notification.show(any(), any()) } coAnswers {
                order += "notify"
                notificationPosted.complete(Unit)
                true
            }
            coEvery { transcription.transcribe(any(), any(), any(), any(), any()) } coAnswers {
                transcribeGate.await()
                order += "transcribe"
                val requestPersistence = arg<InlineCapturePersistence>(1)
                val commitText = arg<(String) -> Unit>(2)
                commitText("raw result ")
                requestPersistence.persistAudioSource(
                    audioSource = InlineAudioSource.PcmFloatFile("/tmp/quick-input.f32pcm", 1L),
                    rawText = "raw result",
                    processedText = "Polished result.",
                    errorMessage = null,
                    reason = InlineCapturePersistReason.COMPLETED,
                )
            }

            val runner = VoiceRecognitionTranscriptionRunner(transcription, persistence, notification)
            val session =
                runner.start(
                    VoiceRecognitionTranscriptionRunner.Request(
                        audioSource = InlineAudioSource.PcmFloatFile("/tmp/quick-input.f32pcm", 1L),
                        llmEnabled = true,
                        processingModeId = "proofread",
                        secure = false,
                    ),
                )

            val activityWaiter = async { session.result.await() }
            activityWaiter.cancelAndJoin()
            transcribeGate.complete(Unit)

            val outcome = withTimeout(5_000L) { session.result.await() }
            withTimeout(5_000L) { notificationPosted.await() }
            assertEquals("raw result", outcome.committedText)
            assertEquals("raw result", outcome.rawText)
            assertEquals("Polished result.", outcome.processedText)
            coVerify(exactly = 1) { notification.show("raw result", "Polished result.") }
            assertEquals(listOf("checkpoint", "transcribe", "persist", "notify"), order)
        }

    @Test
    fun `notification drops processed text rejected by the LLM guard`() =
        runBlocking {
            val transcription = mockk<InlineTranscriptionPort>()
            val persistence = mockk<InlineCapturePersistence>()
            val notification = mockk<QuickInputResultNotificationPublisher>()
            val phase =
                MutableStateFlow<InlineTranscriptionPhase>(
                    InlineTranscriptionPhase.LlmError("AI dropped opening content"),
                )
            val notificationPosted = CompletableDeferred<Unit>()

            every { transcription.phase } returns phase
            coEvery { persistence.checkpointAudioSource(any(), any(), any(), any()) } returns true
            coEvery { persistence.persistAudioSource(any(), any(), any(), any(), any()) } returns Unit
            coEvery { notification.show(any(), any()) } coAnswers {
                notificationPosted.complete(Unit)
                true
            }
            coEvery { transcription.transcribe(any(), any(), any(), any(), any()) } coAnswers {
                val requestPersistence = arg<InlineCapturePersistence>(1)
                val commitText = arg<(String) -> Unit>(2)
                commitText("keep the opening words")
                requestPersistence.persistAudioSource(
                    audioSource = InlineAudioSource.PcmFloatFile("/tmp/rejected-ai.f32pcm", 1L),
                    rawText = "keep the opening words",
                    processedText = "Opening words.",
                    errorMessage = null,
                    reason = InlineCapturePersistReason.COMPLETED,
                )
            }

            val runner = VoiceRecognitionTranscriptionRunner(transcription, persistence, notification)
            val session =
                runner.start(
                    VoiceRecognitionTranscriptionRunner.Request(
                        audioSource = InlineAudioSource.PcmFloatFile("/tmp/rejected-ai.f32pcm", 1L),
                        llmEnabled = true,
                        processingModeId = "proofread",
                        secure = false,
                    ),
                )

            val outcome = withTimeout(5_000L) { session.result.await() }
            withTimeout(5_000L) { notificationPosted.await() }

            assertEquals("keep the opening words", outcome.committedText)
            assertEquals("Opening words.", outcome.processedText)
            coVerify(exactly = 1) { notification.show("keep the opening words", null) }
        }
}
