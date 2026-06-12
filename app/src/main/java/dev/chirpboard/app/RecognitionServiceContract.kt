package dev.chirpboard.app

import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.chirpboard.app.core.audio.recorder.RecordingError
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
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
 * Maps a failed [TranscriptionOutcome] to the SpeechRecognizer error the platform
 * contract expects (IME-7): silence is a benign no-match, engine/model failures are
 * server-side errors — never ERROR_AUDIO, which clients render as "audio system broken".
 * Returns null for [TranscriptionOutcome.Success]; the caller delivers results instead.
 */
internal fun recognitionErrorCodeFor(outcome: TranscriptionOutcome): Int? =
    when (outcome) {
        is TranscriptionOutcome.Success -> null
        TranscriptionOutcome.NoSpeech -> SpeechRecognizer.ERROR_NO_MATCH
        is TranscriptionOutcome.ModelUnavailable -> SpeechRecognizer.ERROR_SERVER
        is TranscriptionOutcome.EngineError -> SpeechRecognizer.ERROR_SERVER
    }

/**
 * Maps a mid-capture [RecordingError] to a SpeechRecognizer error code. Genuine capture
 * failures are the one case that legitimately reports ERROR_AUDIO (IME-2/IME-7).
 */
internal fun recognitionErrorCodeFor(error: RecordingError): Int =
    when (error) {
        RecordingError.PermissionDenied -> SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
        else -> SpeechRecognizer.ERROR_AUDIO
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

/**
 * Pure decision for [ChirpRecognitionService]'s transcribe-and-deliver step (IME-7/TST-005):
 * an empty capture is benign silence (ERROR_NO_MATCH, not a broken audio system), a
 * recognizer that is not ready is a server-side failure, and failed outcomes map through
 * [recognitionErrorCodeFor]. [transcribe] is only invoked once both preconditions hold,
 * mirroring the service's sequencing.
 */
internal suspend fun resolveServiceRecognitionDelivery(
    samplesEmpty: Boolean,
    recognizerReady: Boolean,
    transcribe: suspend () -> TranscriptionOutcome,
): ServiceRecognitionDelivery {
    if (samplesEmpty) {
        return ServiceRecognitionDelivery.Error(SpeechRecognizer.ERROR_NO_MATCH, "no audio samples")
    }
    if (!recognizerReady) {
        return ServiceRecognitionDelivery.Error(SpeechRecognizer.ERROR_SERVER, "recognizer not ready")
    }
    return when (val outcome = transcribe()) {
        is TranscriptionOutcome.Success -> ServiceRecognitionDelivery.Results(outcome.text)
        else -> {
            val reason =
                when (outcome) {
                    is TranscriptionOutcome.ModelUnavailable -> outcome.reason
                    is TranscriptionOutcome.EngineError -> outcome.reason
                    else -> "no speech detected"
                }
            ServiceRecognitionDelivery.Error(
                errorCode = recognitionErrorCodeFor(outcome) ?: SpeechRecognizer.ERROR_SERVER,
                logReason = "${outcome::class.simpleName}: $reason",
            )
        }
    }
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
