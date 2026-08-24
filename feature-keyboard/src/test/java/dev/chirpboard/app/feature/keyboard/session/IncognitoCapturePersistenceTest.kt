package dev.chirpboard.app.feature.keyboard.session

import dev.chirpboard.app.core.transcription.InlineAudioSource
import dev.chirpboard.app.core.transcription.InlineCapturePersistReason
import dev.chirpboard.app.core.transcription.InlineCapturePersistence
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class IncognitoCapturePersistenceTest {
    private class RecordingDelegate : InlineCapturePersistence {
        var persistCalls = 0
        var lastReason: InlineCapturePersistReason? = null
        var prepared: InlineAudioSource? = null
        var released = 0
        var discardedSamples = 0
        var discardedSource: InlineAudioSource? = null

        override fun prepareAudioSource(audioSource: InlineAudioSource) {
            prepared = audioSource
        }

        override fun releasePendingAudioSource() {
            released++
        }

        override suspend fun persist(
            samples: FloatArray?,
            rawText: String?,
            processedText: String?,
            errorMessage: String?,
            reason: InlineCapturePersistReason,
        ) {
            persistCalls++
            lastReason = reason
        }

        override fun discardSamples() {
            discardedSamples++
        }

        override fun discardAudioSource(audioSource: InlineAudioSource) {
            discardedSource = audioSource
        }
    }

    private val source = InlineAudioSource.InMemory(floatArrayOf(0.1f))

    @Test
    fun `completed persist is dropped and its audio discarded`() = runTest {
        // IME-3: no history may be retained for incognito sessions.
        val delegate = RecordingDelegate()
        val persistence = IncognitoCapturePersistence(delegate)

        persistence.persistAudioSource(
            audioSource = source,
            rawText = "secret",
            processedText = null,
            errorMessage = null,
            reason = InlineCapturePersistReason.COMPLETED,
        )

        assertEquals(0, delegate.persistCalls)
        assertSame(source, delegate.discardedSource)
    }

    @Test
    fun `sample persist is dropped and its staged capture discarded`() = runTest {
        // The samples overload owns the staged capture exactly like persistAudioSource: returning
        // without discarding left the temp audio of an incognito session on disk.
        val delegate = RecordingDelegate()
        val persistence = IncognitoCapturePersistence(delegate)

        persistence.persist(
            samples = floatArrayOf(0.2f),
            rawText = "secret",
            processedText = null,
            errorMessage = null,
            reason = InlineCapturePersistReason.COMPLETED,
        )

        assertEquals(0, delegate.persistCalls)
        assertEquals(1, delegate.discardedSamples)
    }

    @Test
    fun `user cancelled persist is dropped`() = runTest {
        val delegate = RecordingDelegate()
        val persistence = IncognitoCapturePersistence(delegate)

        persistence.persistAudioSource(
            audioSource = source,
            rawText = null,
            processedText = null,
            errorMessage = "Dictation cancelled",
            reason = InlineCapturePersistReason.USER_CANCELLED,
        )

        assertEquals(0, delegate.persistCalls)
        assertSame(source, delegate.discardedSource)
    }

    @Test
    fun `rescue persist passes through untouched`() = runTest {
        // The never-drop-captured-speech guarantee survives incognito: interrupted captures are
        // still rescued exactly like a normal session.
        val delegate = RecordingDelegate()
        val persistence = IncognitoCapturePersistence(delegate)

        persistence.persistAudioSource(
            audioSource = source,
            rawText = "partial",
            processedText = null,
            errorMessage = "interrupted",
            reason = InlineCapturePersistReason.RESCUE,
        )

        assertEquals(1, delegate.persistCalls)
        assertEquals(InlineCapturePersistReason.RESCUE, delegate.lastReason)
        assertEquals(null, delegate.discardedSource)
    }

    @Test
    fun `rescue persist without audio source still passes through`() = runTest {
        val delegate = RecordingDelegate()
        val persistence = IncognitoCapturePersistence(delegate)

        persistence.persistAudioSource(
            audioSource = null,
            rawText = null,
            processedText = null,
            errorMessage = "stop timed out",
            reason = InlineCapturePersistReason.RESCUE,
        )

        assertEquals(1, delegate.persistCalls)
    }

    @Test
    fun `staging operations delegate unchanged`() = runTest {
        val delegate = RecordingDelegate()
        val persistence = IncognitoCapturePersistence(delegate)

        persistence.prepareAudioSource(source)
        persistence.releasePendingAudioSource()
        persistence.discardSamples()

        assertSame(source, delegate.prepared)
        assertEquals(1, delegate.released)
        assertEquals(1, delegate.discardedSamples)
    }
}
