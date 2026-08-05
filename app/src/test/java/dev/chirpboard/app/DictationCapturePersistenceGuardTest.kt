package dev.chirpboard.app

import dev.chirpboard.app.core.transcription.InlineAudioSource
import dev.chirpboard.app.core.transcription.InlineCapturePersistReason
import dev.chirpboard.app.core.transcription.InlineCapturePersistence
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DictationCapturePersistenceGuardTest {
    private val source = InlineAudioSource.InMemory(floatArrayOf(0.1f))

    @Test
    fun `user-cancel persist after a completed success persist is suppressed`() =
        runTest {
            val delegate = RecordingCapturePersistence()
            val guard = DictationCapturePersistenceGuard(delegate)

            guard.persistAudioSource(source, "raw", "processed", null, InlineCapturePersistReason.COMPLETED)
            guard.persistAudioSource(source, "raw", null, "Dictation cancelled", InlineCapturePersistReason.USER_CANCELLED)

            assertEquals(1, delegate.persistCalls.size)
            assertEquals(InlineCapturePersistReason.COMPLETED, delegate.persistCalls.single().reason)
        }

    @Test
    fun `any persist after a completed rescue persist is suppressed`() =
        runTest {
            val delegate = RecordingCapturePersistence()
            val guard = DictationCapturePersistenceGuard(delegate)

            guard.persistAudioSource(source, "raw", null, "Commit refused", InlineCapturePersistReason.RESCUE)
            guard.persistAudioSource(source, "raw", null, "Dictation cancelled", InlineCapturePersistReason.USER_CANCELLED)

            assertEquals(1, delegate.persistCalls.size)
            assertEquals(InlineCapturePersistReason.RESCUE, delegate.persistCalls.single().reason)
        }

    @Test
    fun `rescue persist after a completed success persist still runs`() =
        runTest {
            val delegate = RecordingCapturePersistence()
            val guard = DictationCapturePersistenceGuard(delegate)

            guard.persistAudioSource(source, "raw", "processed", null, InlineCapturePersistReason.COMPLETED)
            guard.persistAudioSource(source, "raw", null, "Pipeline failed", InlineCapturePersistReason.RESCUE)

            // A success persist may have been skipped per user preference, so a forced
            // rescue can be the only surviving copy; suppressing it would risk loss.
            assertEquals(2, delegate.persistCalls.size)
            assertEquals(InlineCapturePersistReason.RESCUE, delegate.persistCalls.last().reason)
        }

    @Test
    fun `failed persist leaves the guard open for the follow-up persist`() =
        runTest {
            val delegate = RecordingCapturePersistence(failNextPersist = true)
            val guard = DictationCapturePersistenceGuard(delegate)

            runCatching {
                guard.persistAudioSource(source, "raw", null, "Commit refused", InlineCapturePersistReason.RESCUE)
            }
            guard.persistAudioSource(source, "raw", null, "Dictation cancelled", InlineCapturePersistReason.USER_CANCELLED)

            assertEquals(InlineCapturePersistReason.USER_CANCELLED, delegate.persistCalls.single().reason)
        }

    @Test
    fun `completed persist reports raw and AI text after delegate succeeds`() =
        runTest {
            val delegate = RecordingCapturePersistence()
            var reported: Pair<String, String?>? = null
            val guard =
                DictationCapturePersistenceGuard(delegate) { rawText, processedText ->
                    reported = rawText to processedText
                }

            guard.persistAudioSource(source, "raw", "processed", null, InlineCapturePersistReason.COMPLETED)

            assertEquals("raw" to "processed", reported)
        }

    @Test
    fun `rescue and user cancel persists never report a completed result`() =
        runTest {
            var reportCalls = 0
            val rescueGuard = DictationCapturePersistenceGuard(RecordingCapturePersistence()) { _, _ -> reportCalls++ }
            val cancelGuard = DictationCapturePersistenceGuard(RecordingCapturePersistence()) { _, _ -> reportCalls++ }

            rescueGuard.persistAudioSource(source, "raw", null, "failed", InlineCapturePersistReason.RESCUE)
            cancelGuard.persistAudioSource(source, "raw", null, "cancelled", InlineCapturePersistReason.USER_CANCELLED)

            assertEquals(0, reportCalls)
        }

    @Test
    fun `failed completed persist does not report a result`() =
        runTest {
            val delegate = RecordingCapturePersistence(failNextPersist = true)
            var reportCalls = 0
            val guard = DictationCapturePersistenceGuard(delegate) { _, _ -> reportCalls++ }

            runCatching {
                guard.persistAudioSource(source, "raw", "processed", null, InlineCapturePersistReason.COMPLETED)
            }

            assertEquals(0, reportCalls)
        }

    @Test
    fun `non-persist calls pass straight through to the delegate`() =
        runTest {
            val delegate = RecordingCapturePersistence()
            val guard = DictationCapturePersistenceGuard(delegate)

            guard.prepareAudioSource(source)
            assertEquals(source, delegate.preparedSource)

            guard.releasePendingAudioSource()
            assertNull(delegate.preparedSource)

            guard.discardSamples()
            assertEquals(1, delegate.discardSamplesCalls)

            guard.discardAudioSource(source)
            assertEquals(listOf<InlineAudioSource>(source), delegate.discardedSources)
        }

    private class RecordingCapturePersistence(
        var failNextPersist: Boolean = false,
    ) : InlineCapturePersistence {
        data class PersistCall(
            val rawText: String?,
            val processedText: String?,
            val errorMessage: String?,
            val reason: InlineCapturePersistReason,
        )

        val persistCalls = mutableListOf<PersistCall>()
        val discardedSources = mutableListOf<InlineAudioSource>()
        var preparedSource: InlineAudioSource? = null
        var discardSamplesCalls = 0

        override fun prepareAudioSource(audioSource: InlineAudioSource) {
            preparedSource = audioSource
        }

        override fun releasePendingAudioSource() {
            preparedSource = null
        }

        override suspend fun persist(
            samples: FloatArray?,
            rawText: String?,
            processedText: String?,
            errorMessage: String?,
            reason: InlineCapturePersistReason,
        ) {
            recordPersist(rawText, processedText, errorMessage, reason)
        }

        override suspend fun persistAudioSource(
            audioSource: InlineAudioSource?,
            rawText: String?,
            processedText: String?,
            errorMessage: String?,
            reason: InlineCapturePersistReason,
        ) {
            recordPersist(rawText, processedText, errorMessage, reason)
        }

        override fun discardSamples() {
            discardSamplesCalls++
        }

        override fun discardAudioSource(audioSource: InlineAudioSource) {
            discardedSources.add(audioSource)
        }

        private fun recordPersist(
            rawText: String?,
            processedText: String?,
            errorMessage: String?,
            reason: InlineCapturePersistReason,
        ) {
            if (failNextPersist) {
                failNextPersist = false
                throw IllegalStateException("persist failed")
            }
            persistCalls.add(PersistCall(rawText, processedText, errorMessage, reason))
        }
    }
}
