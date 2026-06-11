package dev.chirpboard.app.core.audio

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Manages audio focus for recording sessions.
 * Requests exclusive focus to pause other audio apps during recording.
 */
class AudioFocusManager(
    private val audioManager: AudioManager,
) {
    sealed class FocusResult {
        object Granted : FocusResult()

        object Denied : FocusResult()
    }

    enum class FocusLossKind {
        PERMANENT,
        TRANSIENT,
    }

    var onFocusLost: ((FocusLossKind) -> Unit)? = null

    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener(::handleFocusChange)

    fun requestFocus(): FocusResult {
        val attributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

        focusRequest =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()

        val result = audioManager.requestAudioFocus(focusRequest!!)

        return when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                hasFocus = true
                Log.d(TAG, "Audio focus granted")
                FocusResult.Granted
            }
            else -> {
                focusRequest = null
                hasFocus = false
                Log.w(TAG, "Audio focus denied: $result")
                FocusResult.Denied
            }
        }
    }

    fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        hasFocus = false
        Log.d(TAG, "Audio focus abandoned")
    }

    internal fun handleFocusChange(focusChange: Int) {
        if (!hasFocus) {
            return
        }
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                Log.d(TAG, "Audio focus lost permanently: $focusChange")
                onFocusLost?.invoke(FocusLossKind.PERMANENT)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                Log.d(TAG, "Audio focus lost transiently: $focusChange")
                onFocusLost?.invoke(FocusLossKind.TRANSIENT)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                Log.d(TAG, "Audio focus gained")
            }
        }
    }

    companion object {
        private const val TAG = "AudioFocusManager"
    }
}
