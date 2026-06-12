package dev.chirpboard.app.feature.keyboard.service

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

internal data class KeyboardInputCommitSession(
    val generation: Long,
)

/**
 * Identity of the editor a session was started against, used to recognize a same-editor
 * `restartInput` (IME-11): editors restart input for reasons that do not change the target field
 * (setText while focused, autofill drops, text-watcher rewrites), and a transcript finishing
 * inside that window can still safely commit into the same field.
 */
private data class KeyboardEditorIdentity(
    val fieldId: Int,
    val packageName: String?,
    val inputType: Int,
)

internal class KeyboardInputSessionGuard {
    private var generation: Long = 0L
    private var blockedInput = false
    private var learningSuppressed = false
    private var activeInput = false
    private var lastEditorIdentity: KeyboardEditorIdentity? = null

    /** True for blocked editors (password variants, null EditorInfo): dictation is refused. */
    val isSensitiveInput: Boolean
        get() = blockedInput

    /**
     * True when the editor set IME_FLAG_NO_PERSONALIZED_LEARNING (incognito/private browsing).
     * The keyboard stays fully functional, but the session's dictation history must not be
     * persisted (IME-3) — only the failure-rescue path may retain the capture.
     */
    val isLearningSuppressed: Boolean
        get() = learningSuppressed

    fun startInput(
        info: EditorInfo?,
        preserveSession: Boolean = false,
        restarting: Boolean = false,
    ) {
        val nowBlocked = info.isBlockedKeyboardInput()
        val identity =
            info?.let { KeyboardEditorIdentity(it.fieldId, it.packageName, it.inputType) }
        // IME-11: a restart that provably targets the same editor (same field id + package +
        // input type) preserves the session so an in-flight transcript still commits. Never for
        // blocked editors; the sensitive/null checks below still invalidate regardless.
        val sameEditorRestart =
            restarting && !nowBlocked && identity != null && identity == lastEditorIdentity
        val preserve = preserveSession || sameEditorRestart
        if (!preserve || nowBlocked || blockedInput || !activeInput) {
            generation += 1
        }
        blockedInput = nowBlocked
        learningSuppressed = info.isNoPersonalizedLearningInput()
        activeInput = !nowBlocked
        lastEditorIdentity = if (nowBlocked) null else identity
    }

    fun finishInput() {
        generation += 1
        blockedInput = false
        learningSuppressed = false
        activeInput = false
        lastEditorIdentity = null
    }

    fun captureCommitSession(): KeyboardInputCommitSession? =
        if (blockedInput || !activeInput) {
            null
        } else {
            KeyboardInputCommitSession(generation)
        }

    fun commitIfCurrent(
        session: KeyboardInputCommitSession,
        connection: InputConnection?,
        text: String,
    ): Boolean {
        if (blockedInput || !activeInput || session.generation != generation) {
            return false
        }
        val target = connection ?: return false
        // IME-12/IME-14/IME-23: clear any composing region a previous IME left behind (commitText
        // would replace it), fix up spacing against the surrounding text, and do it all inside one
        // batch edit so the editor sees a single atomic change.
        target.beginBatchEdit()
        return try {
            target.finishComposingText()
            val adjusted =
                resolveDictationCommitText(
                    before = target.getTextBeforeCursor(1, 0),
                    after = target.getTextAfterCursor(1, 0),
                    text = text,
                )
            target.commitText(adjusted, 1)
        } finally {
            target.endBatchEdit()
        }
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

/** Characters after which dictation must NOT insert a leading space (openers, joiners). */
private val LEADING_SPACE_SUPPRESSING_CHARS = setOf('(', '[', '{', '"', '\'', '“', '‘', '«', '¿', '¡', '/', '-', '@', '#')

/** Characters before which the dictation's trailing space is dropped (closers, punctuation). */
private val TRAILING_SPACE_ABSORBING_CHARS = setOf('.', ',', '!', '?', ':', ';', ')', ']', '}', '…', '"', '\'', '”', '’')

/**
 * Adjusts a dictation commit for its surroundings (IME-14): inserts a separating space when the
 * cursor sits directly after a word ("Hello|" + "world" -> "Hello world"), and drops the
 * pipeline's trailing space when the next character is whitespace or closing punctuation.
 */
internal fun resolveDictationCommitText(
    before: CharSequence?,
    after: CharSequence?,
    text: String,
): String {
    if (text.isEmpty()) return text
    var result = text
    val lastBefore = before?.lastOrNull()
    val needsLeadingSpace =
        lastBefore != null &&
            !lastBefore.isWhitespace() &&
            lastBefore !in LEADING_SPACE_SUPPRESSING_CHARS
    if (needsLeadingSpace && !result.first().isWhitespace()) {
        result = " $result"
    }
    val nextAfter = after?.firstOrNull()
    if (nextAfter != null &&
        result.last() == ' ' &&
        (nextAfter.isWhitespace() || nextAfter in TRAILING_SPACE_ABSORBING_CHARS)
    ) {
        result = result.dropLast(1)
    }
    return result
}

/**
 * Blocked editors where dictation is refused outright: password variants and a null EditorInfo
 * (fails closed). Incognito's no-personalized-learning flag is deliberately NOT blocked — typing
 * must keep working there (IME-3); see [EditorInfo.isNoPersonalizedLearningInput].
 */
internal fun EditorInfo?.isBlockedKeyboardInput(): Boolean {
    if (this == null) return true
    return inputType.isPasswordInputType()
}

/** No-learning (incognito) editors: keyboard fully functional, history persistence suppressed. */
internal fun EditorInfo?.isNoPersonalizedLearningInput(): Boolean =
    this != null &&
        (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0 &&
        !inputType.isPasswordInputType()

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
