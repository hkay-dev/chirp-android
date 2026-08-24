package dev.chirpboard.app.feature.keyboard.service

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

internal data class KeyboardInputCommitSession(
    val generation: Long,
    val editorIdentity: KeyboardEditorIdentity? = null,
    // Captured at session-capture time: the live guard flag resets on finishInput, and a commit
    // that outlives its editor must honor the incognito state of the session it was dictated in.
    val learningSuppressed: Boolean = false,
)

/**
 * How long a refused dictation commit stays pending for the editor it was captured against
 * (RELY-3). Long enough to cover an app-driven input restart plus the user's re-tap, short
 * enough that a stale transcript cannot surprise a much later session.
 */
internal const val DEFERRED_DICTATION_COMMIT_WINDOW_MS = 5_000L

/** Outcome of a dictation commit attempt, including post-commit verification (RELY-1). */
internal enum class KeyboardDictationCommitResult {
    COMMITTED,
    COMMITTED_AFTER_RETRY,
    REFUSED,
    VERIFICATION_FAILED,
    ;

    val committed: Boolean
        get() = this == COMMITTED || this == COMMITTED_AFTER_RETRY
}

/**
 * Identity of the editor a session was started against, used to recognize a same-editor
 * `restartInput` (IME-11): editors restart input for reasons that do not change the target field
 * (setText while focused, autofill drops, text-watcher rewrites), and a transcript finishing
 * inside that window can still safely commit into the same field.
 */
internal data class KeyboardEditorIdentity(
    val fieldId: Int,
    val packageName: String?,
    val inputType: Int,
)

/** A refused dictation commit held for a same-editor rebind within its window (RELY-3). */
private data class DeferredDictationCommit(
    val editorIdentity: KeyboardEditorIdentity,
    val text: String,
    val deadlineElapsedMs: Long,
    val learningSuppressed: Boolean,
)

/**
 * A streamed composing preview that the framework finalized into plain text when the input
 * session ended mid-dictation (RELY-4). Remembered so the same dictation's eventual commit into
 * the same editor can remove the finalized copy instead of appending after it.
 */
private data class FinalizedComposingPreview(
    val editorIdentity: KeyboardEditorIdentity,
    val text: String,
)

internal class KeyboardInputSessionGuard {
    private var generation: Long = 0L
    private var blockedInput = false
    private var learningSuppressed = false
    private var activeInput = false
    private var lastEditorIdentity: KeyboardEditorIdentity? = null
    private var deferredCommit: DeferredDictationCommit? = null
    private var composingPreviewShown = false
    private var composingPreviewPrefix: String? = null
    private var composingPreviewText: String? = null
    private var finalizedPreview: FinalizedComposingPreview? = null

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
            // RELY-4: a new editor never carries the previous editor's composing preview; a
            // preserved session keeps its preview state so the live text keeps updating.
            rememberFinalizedPreview()
            composingPreviewShown = false
            composingPreviewPrefix = null
            composingPreviewText = null
        }
        blockedInput = nowBlocked
        learningSuppressed = info.isNoPersonalizedLearningInput()
        activeInput = !nowBlocked
        lastEditorIdentity = if (nowBlocked) null else identity
    }

    fun finishInput() {
        // An incognito session's transcript must not outlive its editor, so a deferral captured
        // in a learning-suppressed session dies with the input instead of waiting out its window.
        if (deferredCommit?.learningSuppressed == true) {
            deferredCommit = null
        }
        // RELY-4: deliberately NOT clearing the editor's composing region here — if the session
        // dies mid-dictation, the editor keeping the streamed preview is the crash protection.
        // The framework finalizes that region into plain text; remember it so the transcript's
        // eventual commit can replace the finalized copy instead of appending after it.
        rememberFinalizedPreview()
        generation += 1
        blockedInput = false
        learningSuppressed = false
        activeInput = false
        lastEditorIdentity = null
        composingPreviewShown = false
        composingPreviewPrefix = null
        composingPreviewText = null
    }

    private fun rememberFinalizedPreview() {
        val identity = lastEditorIdentity ?: return
        val previewText = composingPreviewText?.takeIf { composingPreviewShown } ?: return
        finalizedPreview = FinalizedComposingPreview(identity, previewText)
    }

    fun captureCommitSession(): KeyboardInputCommitSession? =
        if (blockedInput || !activeInput) {
            null
        } else {
            KeyboardInputCommitSession(generation, lastEditorIdentity, learningSuppressed)
        }

    /**
     * Holds a refused dictation commit for [DEFERRED_DICTATION_COMMIT_WINDOW_MS] so it can still
     * land when the same editor rebinds (RELY-3): apps restart input around IME transitions, and
     * the transcript often finishes in exactly that gap. Requires the session to know which
     * editor it was captured against; returns false when it cannot defer.
     */
    fun deferCommit(
        session: KeyboardInputCommitSession,
        text: String,
        nowElapsedMs: Long,
    ): Boolean {
        val identity = session.editorIdentity ?: return false
        // Fail closed on degenerate field ids: Compose editors and id-less EditTexts all report
        // 0 / NO_ID, so identity cannot distinguish fields there — a deferral could land the
        // transcript in whichever same-typed field the user focuses next. Clipboard fallback only.
        if (identity.fieldId == 0 || identity.fieldId == -1) return false
        if (text.isBlank()) return false
        deferredCommit =
            DeferredDictationCommit(
                editorIdentity = identity,
                text = text,
                deadlineElapsedMs = nowElapsedMs + DEFERRED_DICTATION_COMMIT_WINDOW_MS,
                learningSuppressed = session.learningSuppressed,
            )
        return true
    }

    /**
     * The deferred transcript, if the current editor matches the one it was captured against and
     * the window has not expired. Does NOT consume the deferral — the caller clears it via
     * [clearDeferredCommit] only after the commit actually lands, so a failed attempt can retry
     * on the next rebind within the original window.
     */
    fun deferredCommitTextForCurrentEditor(nowElapsedMs: Long): String? {
        val pending = deferredCommit ?: return null
        if (nowElapsedMs > pending.deadlineElapsedMs) {
            deferredCommit = null
            // The finalized-preview memory exists only to dedup THIS transcript's commit; once
            // the transcript can no longer land, deleting matching editor text would be wrong.
            finalizedPreview = null
            return null
        }
        if (blockedInput || !activeInput) return null
        if (pending.editorIdentity != lastEditorIdentity) return null
        return pending.text
    }

    fun clearDeferredCommit() {
        deferredCommit = null
    }

    /** Whether a deferred dictation commit is still being held for a future rebind. */
    val hasDeferredCommit: Boolean
        get() = deferredCommit != null

    fun commitIfCurrent(
        session: KeyboardInputCommitSession,
        connection: InputConnection?,
        text: String,
    ): KeyboardDictationCommitResult {
        if (blockedInput || !activeInput || session.generation != generation) {
            return KeyboardDictationCommitResult.REFUSED
        }
        val target = connection ?: return KeyboardDictationCommitResult.REFUSED
        // IME-12/IME-14/IME-23: clear any composing region a previous IME left behind (commitText
        // would replace it), fix up spacing against the surrounding text, and do it all inside one
        // batch edit so the editor sees a single atomic change.
        val adjusted: String
        target.beginBatchEdit()
        val accepted =
            try {
                // RELY-4: our own streamed preview occupies the composing region; remove it so
                // the final commit replaces it instead of stacking after a finished copy of it.
                if (composingPreviewShown) {
                    composingPreviewShown = false
                    composingPreviewPrefix = null
                    composingPreviewText = null
                    target.setComposingText("", 1)
                } else {
                    // RELY-4: a session boundary let the framework finalize the preview into
                    // plain text. If that exact text still sits before the cursor in the same
                    // editor, remove it — this commit is the authoritative version of it.
                    finalizedPreview?.let { stale ->
                        if (stale.editorIdentity == lastEditorIdentity &&
                            target.getTextBeforeCursor(stale.text.length, 0)?.toString() == stale.text
                        ) {
                            target.deleteSurroundingText(stale.text.length, 0)
                        }
                    }
                }
                finalizedPreview = null
                target.finishComposingText()
                adjusted =
                    resolveDictationCommitText(
                        before = target.getTextBeforeCursor(1, 0),
                        after = target.getTextAfterCursor(1, 0),
                        text = text,
                    )
                target.commitText(adjusted, 1)
            } finally {
                target.endBatchEdit()
            }
        if (!accepted) return KeyboardDictationCommitResult.REFUSED
        // RELY-1: read the field back and confirm the text actually landed. Editors can report
        // success from commitText and still drop the change (a dying binder, a mid-restart race).
        // Only a readback proving the text fully absent earns one retry; anything ambiguous
        // (null readback, truncated context, partial presence) is accepted as committed, because
        // a second commit there could duplicate text.
        if (verifyReadback(target, adjusted) != DictationCommitVerification.MISSING) {
            return KeyboardDictationCommitResult.COMMITTED
        }
        target.beginBatchEdit()
        val retried =
            try {
                target.commitText(adjusted, 1)
            } finally {
                target.endBatchEdit()
            }
        if (retried && verifyReadback(target, adjusted) != DictationCommitVerification.MISSING) {
            return KeyboardDictationCommitResult.COMMITTED_AFTER_RETRY
        }
        return KeyboardDictationCommitResult.VERIFICATION_FAILED
    }

    /**
     * RELY-4: streams a best-effort partial transcript into the editor as composing text while
     * dictation is still recording, so a session that dies mid-flight loses only the tail —
     * the editor keeps whatever was composed. The leading-space prefix is decided once per
     * preview (before our own composing text pollutes the surrounding-context readback) and the
     * final [commitIfCurrent] replaces the whole composed region with the authoritative text.
     */
    fun updateComposingPreview(
        connection: InputConnection?,
        text: String,
    ): Boolean {
        if (blockedInput || !activeInput || text.isEmpty()) return false
        val target = connection ?: return false
        val prefix =
            composingPreviewPrefix ?: run {
                val before = target.getTextBeforeCursor(1, 0)
                (if (dictationNeedsLeadingSpace(before)) " " else "").also {
                    composingPreviewPrefix = it
                }
            }
        val previewText = prefix + text
        val shown = target.setComposingText(previewText, 1)
        if (shown) {
            composingPreviewShown = true
            composingPreviewText = previewText
        }
        return shown
    }

    /**
     * A new dictation is starting: any remembered finalized preview belongs to an older one,
     * and deleting editor text on its behalf during the new dictation's commit would drop text
     * the user chose to keep.
     */
    fun onDictationStarting() {
        finalizedPreview = null
    }

    /**
     * A typing key (backspace, space, cursor move, IME action) is about to run a raw
     * InputConnection action that finishes the composing region behind the guard's back.
     *
     * The preview must leave the editor first. Once the framework finalizes it, an action that
     * also edits the field (backspace shortens it, an editor action can clear the whole field)
     * leaves the guard unable to prove which characters are still its own, so the authoritative
     * commit would stack a second copy of the dictation after the finalized one. Removing the
     * preview costs nothing the user typed: the next partial re-streams it at the cursor the key
     * leaves behind, and the final commit carries the whole transcript regardless.
     */
    fun onExternalComposingFinish(connection: InputConnection?) {
        clearComposingPreview(connection)
    }

    /** Removes a live composing preview (user cancelled the dictation). Safe no-op otherwise. */
    fun clearComposingPreview(connection: InputConnection?) {
        // The user discarded the dictation, so a remembered finalized copy must never be
        // deleted out of the editor by a later commit either.
        finalizedPreview = null
        if (!composingPreviewShown) return
        composingPreviewShown = false
        composingPreviewPrefix = null
        composingPreviewText = null
        connection?.setComposingText("", 1)
    }

    private fun verifyReadback(
        target: InputConnection,
        committed: String,
    ): DictationCommitVerification =
        verifyDictationCommitReadback(
            readback = target.getTextBeforeCursor(committed.length + VERIFY_READBACK_SLACK, 0),
            committed = committed,
        )

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

/** Extra readback characters requested beyond the committed length, absorbing editor fix-ups. */
private const val VERIFY_READBACK_SLACK = 2

/** Length of the committed-text tail probed for partial presence before declaring it missing. */
private const val VERIFY_PROBE_LENGTH = 12

/** What a post-commit readback of the field proved about the committed text (RELY-1). */
internal enum class DictationCommitVerification {
    VERIFIED,
    UNVERIFIABLE,
    MISSING,
}

/**
 * Classifies a post-commit `getTextBeforeCursor` readback. Deliberately conservative: only a
 * readback that proves the committed text fully absent returns [DictationCommitVerification.MISSING],
 * because the caller retries the commit on that verdict and a wrong MISSING duplicates text.
 * Editors that transform the commit (autocorrect, trimming) or truncate context land on
 * [DictationCommitVerification.UNVERIFIABLE], which the caller treats as committed.
 */
internal fun verifyDictationCommitReadback(
    readback: CharSequence?,
    committed: String,
): DictationCommitVerification {
    if (committed.isBlank()) return DictationCommitVerification.VERIFIED
    // A null readback means the editor cannot expose surrounding text; nothing is provable.
    val observed = readback?.toString() ?: return DictationCommitVerification.UNVERIFIABLE
    val expected = committed.trimEnd()
    val observedTail = observed.trimEnd()
    // Nothing (or only whitespace) sits before the cursor: the commit did not land.
    if (observedTail.isEmpty()) return DictationCommitVerification.MISSING
    if (observedTail.endsWith(expected)) return DictationCommitVerification.VERIFIED
    // The editor returned less context than the committed text (small field, maxLength):
    // the whole readback matching the committed tail is presence, not absence.
    if (expected.endsWith(observedTail)) return DictationCommitVerification.UNVERIFIABLE
    // Some tail chunk of the committed text is present but transformed or followed by editor
    // additions. Retrying here could duplicate; accept it.
    val probe = expected.takeLast(VERIFY_PROBE_LENGTH)
    if (observed.contains(probe)) return DictationCommitVerification.UNVERIFIABLE
    return DictationCommitVerification.MISSING
}

/** Whether dictated text landing after [before]'s last character needs a separating space. */
internal fun dictationNeedsLeadingSpace(before: CharSequence?): Boolean {
    val lastBefore = before?.lastOrNull() ?: return false
    return !lastBefore.isWhitespace() && lastBefore !in LEADING_SPACE_SUPPRESSING_CHARS
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
    if (dictationNeedsLeadingSpace(before) && !result.first().isWhitespace()) {
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
