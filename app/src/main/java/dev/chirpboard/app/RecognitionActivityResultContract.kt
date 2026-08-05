package dev.chirpboard.app

import android.app.PendingIntent
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.os.BadParcelableException
import android.speech.RecognizerIntent

/**
 * The two result channels supported by [RecognizerIntent.ACTION_RECOGNIZE_SPEECH].
 *
 * Most callers use an activity result. A caller can instead supply
 * [RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT], in which case Android's contract says the
 * recognizer must send the result there and merge
 * [RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE] into the fill-in intent.
 */
internal enum class RecognitionActivityResultChannel {
    ACTIVITY_RESULT,
    PENDING_INTENT,
    ACTIVITY_RESULT_FALLBACK,
}

/** Builds the compatibility payload used by Android's built-in voice-input activity. */
internal fun buildRecognitionActivityResult(text: String): Intent =
    Intent().apply {
        putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(text))
        putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, floatArrayOf(1f))
        // Google's voice-input activity also includes the top hypothesis under the generic
        // search query key. Some older callers read that key instead of EXTRA_RESULTS.
        putExtra(SearchManager.QUERY, text)
    }

/**
 * Delivers one terminal recognition result through the channel selected by the caller.
 *
 * A cancelled PendingIntent falls back to the activity result. It is safe to do so because a
 * cancelled token cannot have delivered the fill-in intent, and preserving the transcript is
 * more important than silently dropping it. A live PendingIntent is authoritative and is never
 * followed by setResult, which would risk duplicate insertion in callers that observe both.
 */
internal fun deliverRecognitionActivityResult(
    context: Context,
    request: Intent?,
    resultCode: Int,
    data: Intent?,
    setActivityResult: (Int, Intent?) -> Unit,
): RecognitionActivityResultChannel {
    val pendingIntent = request.pendingRecognitionResultOrNull()
    if (pendingIntent == null) {
        setActivityResult(resultCode, data)
        return RecognitionActivityResultChannel.ACTIVITY_RESULT
    }

    val pendingData = data ?: Intent()
    request
        ?.getBundleExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE)
        ?.let(pendingData::putExtras)

    return try {
        pendingIntent.send(context, resultCode, pendingData)
        RecognitionActivityResultChannel.PENDING_INTENT
    } catch (_: PendingIntent.CanceledException) {
        setActivityResult(resultCode, data)
        RecognitionActivityResultChannel.ACTIVITY_RESULT_FALLBACK
    }
}

/** Treat malformed caller parcels as an absent optional channel, not an activity crash. */
private fun Intent?.pendingRecognitionResultOrNull(): PendingIntent? =
    try {
        this?.getParcelableExtra(
            RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT,
            PendingIntent::class.java,
        )
    } catch (_: BadParcelableException) {
        null
    } catch (_: ClassCastException) {
        null
    }
