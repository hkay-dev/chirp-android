package dev.chirpboard.app.feature.keyboard.service

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

internal data class KeyboardInputCommitSession(
    val generation: Long,
)

internal class KeyboardInputSessionGuard {
    private var generation: Long = 0L
    private var sensitiveInput = false
    private var activeInput = false

    val isSensitiveInput: Boolean
        get() = sensitiveInput

    fun startInput(
        info: EditorInfo?,
        preserveSession: Boolean = false,
    ) {
        val nowSensitive = info.isSensitiveKeyboardInput()
        if (!preserveSession || nowSensitive || sensitiveInput || !activeInput) {
            generation += 1
        }
        sensitiveInput = nowSensitive
        activeInput = !nowSensitive
    }

    fun finishInput() {
        generation += 1
        sensitiveInput = false
        activeInput = false
    }

    fun captureCommitSession(): KeyboardInputCommitSession? =
        if (sensitiveInput || !activeInput) {
            null
        } else {
            KeyboardInputCommitSession(generation)
        }

    fun commitIfCurrent(
        session: KeyboardInputCommitSession,
        connection: InputConnection?,
        text: String,
    ): Boolean {
        if (sensitiveInput || !activeInput || session.generation != generation) {
            return false
        }
        return connection?.commitText(text, 1) == true
    }

    /**
     * Builds a commit-text provider for stops the IME does not initiate directly (for
     * example the max-duration limit). The provider must capture a fresh commit session
     * on EVERY invocation — at stop time — never once at registration: a provider that
     * cached the session would commit later transcripts against a stale input session.
     * Yields null when no commitable session is live at stop time.
     */
    fun commitTextProvider(commit: (KeyboardInputCommitSession, String) -> Boolean): () -> ((String) -> Boolean)? =
        {
            captureCommitSession()?.let { session ->
                { text: String -> commit(session, text) }
            }
        }
}

internal fun EditorInfo?.isSensitiveKeyboardInput(): Boolean {
    if (this == null) return true
    if ((imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) {
        return true
    }
    return inputType.isPasswordInputType()
}

private fun Int.isPasswordInputType(): Boolean {
    val inputClass = this and InputType.TYPE_MASK_CLASS
    val variation = this and InputType.TYPE_MASK_VARIATION
    return when (inputClass) {
        InputType.TYPE_CLASS_TEXT ->
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        InputType.TYPE_CLASS_NUMBER ->
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else -> false
    }
}
