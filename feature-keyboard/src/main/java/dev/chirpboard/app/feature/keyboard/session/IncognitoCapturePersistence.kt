package dev.chirpboard.app.feature.keyboard.session

import dev.chirpboard.app.core.transcription.InlineAudioSource
import dev.chirpboard.app.core.transcription.InlineCapturePersistReason
import dev.chirpboard.app.core.transcription.InlineCapturePersistence

/**
 * Persistence wrapper for no-personalized-learning (incognito) sessions (IME-3).
 *
 * The IME_FLAG_NO_PERSONALIZED_LEARNING contract is "type normally, but never learn/store from
 * this text": dictation works, the transcript commits, but the session must leave no history.
 * This wrapper drops [InlineCapturePersistReason.COMPLETED] and
 * [InlineCapturePersistReason.USER_CANCELLED] persists (discarding their staged temp audio so the
 * capture file does not linger on disk) while forwarding [InlineCapturePersistReason.RESCUE]
 * untouched — the "never drop captured speech" guarantee for interrupted captures stays intact,
 * exactly as it does for the rest of the pipeline.
 */
internal class IncognitoCapturePersistence(
    private val delegate: InlineCapturePersistence,
) : InlineCapturePersistence {
    override fun prepareAudioSource(audioSource: InlineAudioSource) = delegate.prepareAudioSource(audioSource)

    override fun releasePendingAudioSource() = delegate.releasePendingAudioSource()

    override suspend fun persist(
        samples: FloatArray?,
        rawText: String?,
        processedText: String?,
        errorMessage: String?,
        reason: InlineCapturePersistReason,
    ) {
        if (reason == InlineCapturePersistReason.RESCUE) {
            delegate.persist(
                samples = samples,
                rawText = rawText,
                processedText = processedText,
                errorMessage = errorMessage,
                reason = reason,
            )
        }
    }

    override suspend fun persistAudioSource(
        audioSource: InlineAudioSource?,
        rawText: String?,
        processedText: String?,
        errorMessage: String?,
        reason: InlineCapturePersistReason,
    ) {
        if (reason == InlineCapturePersistReason.RESCUE) {
            delegate.persistAudioSource(
                audioSource = audioSource,
                rawText = rawText,
                processedText = processedText,
                errorMessage = errorMessage,
                reason = reason,
            )
        } else if (audioSource != null) {
            // Suppressed history persist still owns the staged capture: release the staged
            // reference and delete the temp audio so nothing of the session is retained.
            delegate.discardAudioSource(audioSource)
        }
    }

    override fun discardSamples() = delegate.discardSamples()

    override fun discardAudioSource(audioSource: InlineAudioSource) = delegate.discardAudioSource(audioSource)
}
