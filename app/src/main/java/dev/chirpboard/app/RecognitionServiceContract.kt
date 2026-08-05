package dev.chirpboard.app

import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.chirpboard.app.core.transcription.InlineTranscriptionPhase
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max

/**
 * Pure mapping helpers for the two system recognition surfaces ([ChirpRecognitionService]
 * and [VoiceRecognitionActivity]), extracted so the SpeechRecognizer/RecognizerIntent
 * contract behavior is unit-testable (IME-7/IME-15/IME-19/LIF-09, TST-005).
 */

/** BCP-47 tag of the only language the bundled Parakeet model transcribes (PIPE-08). */
internal const val SUPPORTED_RECOGNITION_LANGUAGE_TAG = "en-US"

/**
 * True when a caller-requested language tag is servable by the English-only model.
 * A missing/blank request means "device default", which this engine serves as English
 * rather than failing every extra-less caller. Tolerates both BCP-47 ("en-US") and
 * legacy java.util.Locale ("en_US") forms (IME-15).
 */
internal fun isRecognitionLanguageSupported(languageTag: String?): Boolean {
    if (languageTag.isNullOrBlank()) {
        return true
    }
    val language = Locale.forLanguageTag(languageTag.trim().replace('_', '-')).language
    // An unparsable tag yields an empty language; fail open to English rather than
    // rejecting callers that send malformed-but-well-meaning extras.
    return language.isEmpty() || language.equals("en", ignoreCase = true)
}

/**
 * Maps a SpeechRecognizer.ERROR_* code to the RecognizerIntent activity result code the
 * RECOGNIZE_SPEECH contract defines, so callers can distinguish no-match / audio failure /
 * server trouble from a genuine user cancel (LIF-09/IME-9). RESULT_CANCELED is reserved
 * for user-initiated dismissals and never produced here.
 */
internal fun recognizerIntentResultCodeFor(speechRecognizerErrorCode: Int): Int =
    when (speechRecognizerErrorCode) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> RecognizerIntent.RESULT_NO_MATCH

        SpeechRecognizer.ERROR_AUDIO -> RecognizerIntent.RESULT_AUDIO_ERROR

        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> RecognizerIntent.RESULT_NETWORK_ERROR

        SpeechRecognizer.ERROR_SERVER -> RecognizerIntent.RESULT_SERVER_ERROR

        else -> RecognizerIntent.RESULT_CLIENT_ERROR
    }

/**
 * What [ChirpRecognitionService] sends a client for a stopped capture (TST-005):
 * either a single-hypothesis results bundle or one terminal SpeechRecognizer error code.
 */
internal sealed interface ServiceRecognitionDelivery {
    data class Results(
        val text: String,
    ) : ServiceRecognitionDelivery

    data class Error(
        val errorCode: Int,
        /** Developer diagnostic for the service log; never user-facing. */
        val logReason: String,
    ) : ServiceRecognitionDelivery
}

/** Pure mapping from the shared process-owned transcription runner to the service callback. */
internal fun resolveServiceRecognitionDelivery(
    committedText: String,
    terminalPhase: InlineTranscriptionPhase,
    recognizerReady: Boolean,
): ServiceRecognitionDelivery {
    if (committedText.isNotBlank()) {
        return ServiceRecognitionDelivery.Results(committedText)
    }
    if (terminalPhase is InlineTranscriptionPhase.Error) {
        val reason = if (recognizerReady) "offline transcription failed" else "recognizer not ready"
        return ServiceRecognitionDelivery.Error(SpeechRecognizer.ERROR_SERVER, reason)
    }
    return ServiceRecognitionDelivery.Error(SpeechRecognizer.ERROR_NO_MATCH, "no speech detected")
}

/**
 * Builds the per-session end-of-speech/no-speech detector from a recognition request's
 * RecognizerIntent extras (IME-2). Used by both system recognition surfaces so a silent
 * session terminates identically whether it runs through [ChirpRecognitionService]
 * (terminal ERROR_SPEECH_TIMEOUT) or the [VoiceRecognitionActivity] dialog (gentle
 * retry state). A null intent yields the default configuration.
 */
internal fun recognizerIntentEndpointer(intent: Intent?): SpeechEndpointer =
    recognizerSessionEndpointer(
        clientCompleteSilenceMs =
            intent?.positiveDurationExtraMs(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS),
        clientMinimumLengthMs =
            intent?.positiveDurationExtraMs(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS),
    )

/** Reads a positive millisecond duration extra that callers may set as Int or Long. */
private fun Intent.positiveDurationExtraMs(key: String): Long? {
    val intValue = getIntExtra(key, -1)
    if (intValue > 0) {
        return intValue.toLong()
    }
    val longValue = getLongExtra(key, -1L)
    return longValue.takeIf { it > 0L }
}

/** Lower clamp so silence still produces a finite dB value. */
private const val MIN_RMS_AMPLITUDE = 1e-4f

/** dBFS span mapped onto the de-facto RecognitionListener range. */
private const val RMS_DBFS_FLOOR = -60f

/** De-facto RecognitionListener.onRmsChanged range used by platform engines. */
private const val RMS_DB_MIN = -2f
private const val RMS_DB_SPAN = 12f

/**
 * Converts the recorder's 0..1 mean-abs amplitude to the de-facto RMS dB range platform
 * engines emit (about -2..10), so client mic-level animations are not pegged at max for
 * any audible input (IME-19). Maps [-60dBFS..0dBFS] linearly onto [-2..10].
 */
internal fun amplitudeToRmsDb(amplitude: Float): Float {
    val dbfs = 20f * log10(max(amplitude, MIN_RMS_AMPLITUDE))
    val normalized = ((dbfs - RMS_DBFS_FLOOR) / -RMS_DBFS_FLOOR).coerceIn(0f, 1f)
    return RMS_DB_MIN + normalized * RMS_DB_SPAN
}
