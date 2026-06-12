package dev.chirpboard.app.core.audio

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Manages audio focus for recording sessions.
 * Requests exclusive focus to pause other audio apps during recording.
 *
 * Thread-safe: the recognition surfaces abandon focus from their IO teardown while requests
 * and [handleFocusChange] run on main, so a single monitor guards all mutable state (the
 * critical sections are tiny and uncontended). Loss/regain callbacks are invoked outside the
 * monitor so a listener may safely re-enter request/abandon from any thread.
 *
 * @param focusRequestFactory builds the platform [AudioFocusRequest] for [requestFocus];
 * constructor-injected so unit tests can exercise the request path without the framework
 * builders, which are not available in JVM tests.
 */
class AudioFocusManager(
    private val audioManager: AudioManager,
    private val focusRequestFactory:
        (AudioManager.OnAudioFocusChangeListener) -> AudioFocusRequest = ::defaultFocusRequest,
) {
    sealed class FocusResult {
        object Granted : FocusResult()

        object Denied : FocusResult()
    }

    enum class FocusLossKind {
        PERMANENT,
        TRANSIENT,
    }

    /** Guards [focusRequest], [hasFocus] and the callback vars; never held across callbacks. */
    private val lock = Any()

    var onFocusLost: ((FocusLossKind) -> Unit)? = null
        get() = synchronized(lock) { field }
        set(value) = synchronized(lock) { field = value }

    /**
     * Invoked when focus returns after a transient loss (AUDIOFOCUS_GAIN while this
     * manager still owns an outstanding request). Never fires after a permanent loss,
     * which already cleared ownership.
     */
    var onFocusRegained: (() -> Unit)? = null
        get() = synchronized(lock) { field }
        set(value) = synchronized(lock) { field = value }

    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener(::handleFocusChange)

    fun requestFocus(): FocusResult =
        synchronized(lock) {
            // Defense for re-request paths (the recording-service restart re-requests without
            // abandoning): drop any outstanding request first so its focus-stack entry can
            // never outlive the new one.
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
            hasFocus = false

            val request = focusRequestFactory(focusChangeListener)
            focusRequest = request

            val result = audioManager.requestAudioFocus(request)

            when (result) {
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
        synchronized(lock) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
            hasFocus = false
            Log.d(TAG, "Audio focus abandoned")
        }
    }

    internal fun handleFocusChange(focusChange: Int) {
        // Resolve the transition under the monitor, but invoke the resulting callback after
        // releasing it so listener code never runs lock-held.
        val notify: (() -> Unit)? =
            synchronized(lock) {
                if (!hasFocus) {
                    return
                }
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        hasFocus = false
                        Log.d(TAG, "Audio focus lost permanently: $focusChange")
                        onFocusLost?.let { listener -> { listener(FocusLossKind.PERMANENT) } }
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                    -> {
                        Log.d(TAG, "Audio focus lost transiently: $focusChange")
                        onFocusLost?.let { listener -> { listener(FocusLossKind.TRANSIENT) } }
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        hasFocus = true
                        Log.d(TAG, "Audio focus gained")
                        onFocusRegained
                    }
                    else -> null
                }
            }
        notify?.invoke()
    }

    companion object {
        private const val TAG = "AudioFocusManager"
    }
}

private fun defaultFocusRequest(listener: AudioManager.OnAudioFocusChangeListener): AudioFocusRequest {
    val attributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    return AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener(listener)
        .build()
}
