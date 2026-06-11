package dev.chirpboard.app.feature.transcription.inline

import dev.chirpboard.app.core.transcription.InlineAudioSource
import dev.chirpboard.app.core.transcription.InlineCapturePersistReason
import dev.chirpboard.app.core.transcription.InlineCapturePersistence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the [InlineCapturePersistence] interface defaults that protect cross-surface
 * audio ownership: the default [InlineCapturePersistence.discardAudioSource] must stay
 * identity-targeted and never fall back to the identity-blind discardSamples().
 */
class InlineCapturePersistenceDefaultsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `default discardAudioSource deletes only that source backing file`() {
        val persistence = MinimalPersistence()
        val target = temporaryFolder.newFile("target.f32pcm")
        val unrelated = temporaryFolder.newFile("unrelated.f32pcm")

        persistence.discardAudioSource(
            InlineAudioSource.PcmFloatFile(path = target.absolutePath, sampleCount = 1),
        )

        assertFalse(target.exists())
        assertTrue(unrelated.exists())
        // Fail closed: routing through discardSamples() would let one surface delete
        // whatever source another surface has staged.
        assertEquals(0, persistence.discardSamplesCalls)
    }

    @Test
    fun `default discardAudioSource never routes in-memory sources to discardSamples`() {
        val persistence = MinimalPersistence()

        persistence.discardAudioSource(InlineAudioSource.InMemory(floatArrayOf(0.1f)))

        assertEquals(0, persistence.discardSamplesCalls)
    }

    private class MinimalPersistence : InlineCapturePersistence {
        var discardSamplesCalls = 0

        override suspend fun persist(
            samples: FloatArray?,
            rawText: String?,
            processedText: String?,
            errorMessage: String?,
            reason: InlineCapturePersistReason,
        ) = Unit

        override fun discardSamples() {
            discardSamplesCalls++
        }
    }
}
