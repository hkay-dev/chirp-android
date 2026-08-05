package dev.chirpboard.app.quickinput

import android.os.SystemClock
import dev.chirpboard.app.feature.transcription.QuickInputPasteHandler
import javax.inject.Inject
import javax.inject.Singleton

internal data class QuickInputNodeBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/** A short-lived, in-memory snapshot of the editor that launched SwiftKey voice input. */
internal data class QuickInputAccessibilityTarget(
    val packageName: String,
    val windowId: Int,
    val viewIdResourceName: String,
    val className: String,
    val bounds: QuickInputNodeBounds,
    val originalText: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val supportsSetText: Boolean,
    val capturedAtUptimeMillis: Long,
)

internal data class QuickInputAccessibilitySession(
    val id: Long,
)

internal data class QuickInputAccessibilityRequest(
    val sessionId: Long,
    val target: QuickInputAccessibilityTarget,
    val deliveredText: String,
    val rawText: String,
    val processedText: String?,
    val deadlineUptimeMillis: Long,
)

internal data class QuickInputAccessibilityAttempt(
    val request: QuickInputAccessibilityRequest,
    val text: String,
    val useProcessedText: Boolean,
    val userInitiated: Boolean,
    val attemptDeadlineUptimeMillis: Long,
)

internal data class QuickInputNodeCandidateTraits(
    val packageMonitored: Boolean,
    val visible: Boolean,
    val password: Boolean,
    val editable: Boolean,
    val focused: Boolean,
    val supportsSetText: Boolean,
)

internal fun isSafeQuickInputCandidate(traits: QuickInputNodeCandidateTraits): Boolean =
    traits.packageMonitored &&
        traits.visible &&
        !traits.password &&
        (traits.editable || traits.supportsSetText)

/** Chooses only an unambiguous editor, favoring the focused node and ACTION_SET_TEXT support. */
internal fun selectQuickInputCandidateIndex(candidates: List<QuickInputNodeCandidateTraits>): Int? {
    fun uniqueMatch(predicate: (QuickInputNodeCandidateTraits) -> Boolean): Int? {
        val indexes = candidates.indices.filter { index -> predicate(candidates[index]) }
        return indexes.singleOrNull()
    }

    return uniqueMatch { candidate -> candidate.focused && candidate.supportsSetText }
        ?: uniqueMatch { candidate -> candidate.focused }
        ?: uniqueMatch { candidate -> candidate.editable && candidate.supportsSetText }
        ?: uniqueMatch { candidate -> candidate.supportsSetText }
        ?: candidates.indices.singleOrNull()
}

/**
 * Keeps Android's normal recognition result authoritative, verifies whether it reached the editor,
 * and allows one user-requested accessibility paste only after verification failed.
 */
@Singleton
class QuickInputAccessibilityCoordinator
    @Inject
    constructor() : QuickInputPasteHandler {
        internal fun interface Listener {
            fun onAttemptRequested(attempt: QuickInputAccessibilityAttempt)
        }

        private data class SessionState(
            val session: QuickInputAccessibilitySession,
            val target: QuickInputAccessibilityTarget,
        )

        private val lock = Any()
        private var nextSessionId = 0L
        private var latestTarget: QuickInputAccessibilityTarget? = null
        private var currentSession: SessionState? = null
        private var armedRequest: QuickInputAccessibilityRequest? = null
        private var listener: Listener? = null
        private var refreshTarget: (() -> Unit)? = null
        private var activeEditorPaste: ((String) -> Unit)? = null

        internal fun rememberTarget(target: QuickInputAccessibilityTarget): Boolean {
            if (
                target.packageName !in MONITORED_PACKAGES ||
                target.windowId < 0 ||
                target.selectionStart < -1 ||
                target.selectionEnd < -1
            ) {
                return false
            }
            synchronized(lock) {
                latestTarget = target
            }
            return true
        }

        internal fun beginSession(
            callingPackage: String?,
            nowUptimeMillis: Long = SystemClock.uptimeMillis(),
        ): QuickInputAccessibilitySession? {
            if (callingPackage !in SUPPORTED_SWIFTKEY_PACKAGES) return null
            val refresher =
                synchronized(lock) {
                    currentSession = null
                    armedRequest = null
                    if (listener == null) null else refreshTarget
                } ?: return null
            refresher()
            return synchronized(lock) {
                currentSession = null
                if (listener == null) return@synchronized null
                val target = latestTarget ?: return@synchronized null
                val targetAge = nowUptimeMillis - target.capturedAtUptimeMillis
                if (targetAge !in 0..TARGET_MAX_AGE_MS) {
                    latestTarget = null
                    return@synchronized null
                }
                latestTarget = null
                val session = QuickInputAccessibilitySession(id = ++nextSessionId)
                currentSession = SessionState(session = session, target = target)
                session
            }
        }

        /** Arms one request and dispatches it before the activity returns to its caller. */
        internal fun arm(
            session: QuickInputAccessibilitySession?,
            deliveredText: String,
            rawText: String,
            processedText: String?,
            nowUptimeMillis: Long = SystemClock.uptimeMillis(),
        ): QuickInputAccessibilityRequest? {
            val delivered = deliveredText.trim()
            val raw = rawText.trim()
            if (session == null || delivered.isEmpty() || raw.isEmpty()) return null
            val dispatch =
                synchronized(lock) {
                    val state = currentSession
                    val activeListener = listener
                    if (state?.session != session || activeListener == null) {
                        return@synchronized null
                    }
                    val processed = processedText?.trim()?.takeIf { it.isNotEmpty() && it != raw }
                    val request =
                        QuickInputAccessibilityRequest(
                            sessionId = session.id,
                            target = state.target,
                            deliveredText = delivered,
                            rawText = raw,
                            processedText = processed,
                            deadlineUptimeMillis = nowUptimeMillis + REQUEST_WINDOW_MS,
                        )
                    currentSession = null
                    armedRequest = request
                    val attempt =
                        QuickInputAccessibilityAttempt(
                            request = request,
                            text = delivered,
                            useProcessedText = processed != null && delivered == processed,
                            userInitiated = false,
                            attemptDeadlineUptimeMillis = nowUptimeMillis + AUTOMATIC_ATTEMPT_WINDOW_MS,
                        )
                    Triple(activeListener, attempt, request)
                } ?: return null
            dispatch.first.onAttemptRequested(dispatch.second)
            return dispatch.third
        }

    override fun requestPaste(
        sessionId: Long,
        useProcessedText: Boolean,
    ): Boolean =
        requestPasteAt(
            sessionId = sessionId,
            useProcessedText = useProcessedText,
            nowUptimeMillis = SystemClock.uptimeMillis(),
        )

    override fun requestPasteIntoActiveEditor(text: String): Boolean {
        val pasteText = text.trim()
        if (pasteText.isEmpty()) return false
        val handler = synchronized(lock) { activeEditorPaste } ?: return false
        handler(pasteText)
        return true
    }

    internal fun canPasteIntoActiveEditor(): Boolean =
        synchronized(lock) {
            activeEditorPaste != null
        }

    internal fun requestPasteAt(
        sessionId: Long,
        useProcessedText: Boolean,
        nowUptimeMillis: Long,
    ): Boolean {
        val dispatch =
            synchronized(lock) {
                val request = armedRequest
                val activeListener = listener
                if (
                    request?.sessionId != sessionId ||
                    request.deadlineUptimeMillis < nowUptimeMillis ||
                    !request.target.supportsSetText ||
                    activeListener == null
                ) {
                    return@synchronized null
                }
                val selectedText =
                    if (useProcessedText) {
                        request.processedText ?: request.rawText
                    } else {
                        request.rawText
                    }
                activeListener to
                    QuickInputAccessibilityAttempt(
                        request = request,
                        text = selectedText,
                        useProcessedText = useProcessedText && request.processedText != null,
                        userInitiated = true,
                        attemptDeadlineUptimeMillis = nowUptimeMillis + USER_ATTEMPT_WINDOW_MS,
                    )
            } ?: return false
        dispatch.first.onAttemptRequested(dispatch.second)
        return true
    }

    internal fun cancel(session: QuickInputAccessibilitySession?): Boolean =
        synchronized(lock) {
            if (session == null || currentSession?.session != session) return@synchronized false
            currentSession = null
            true
        }

    internal fun complete(sessionId: Long): Boolean =
        synchronized(lock) {
            if (armedRequest?.sessionId != sessionId) return@synchronized false
            armedRequest = null
            true
        }

    internal fun isArmed(sessionId: Long): Boolean =
        synchronized(lock) {
            armedRequest?.sessionId == sessionId
        }

    internal fun currentRequest(): QuickInputAccessibilityRequest? =
        synchronized(lock) {
            armedRequest
        }

    internal fun attachListener(
        listener: Listener,
        refreshTarget: () -> Unit = {},
        activeEditorPaste: ((String) -> Unit)? = null,
    ) {
        synchronized(lock) {
            this.listener = listener
            this.refreshTarget = refreshTarget
            this.activeEditorPaste = activeEditorPaste
        }
    }

    internal fun detachListener(listener: Listener) {
        synchronized(lock) {
            if (this.listener === listener) {
                this.listener = null
                refreshTarget = null
                activeEditorPaste = null
            }
            currentSession = null
        }
    }

    internal companion object {
        const val TARGET_MAX_AGE_MS = 5 * 60_000L
        const val AUTOMATIC_ATTEMPT_WINDOW_MS = 1_500L
        const val REQUEST_WINDOW_MS = AUTOMATIC_ATTEMPT_WINDOW_MS + 30_000L
        const val USER_ATTEMPT_WINDOW_MS = 3_000L

        val MONITORED_PACKAGES =
            setOf(
                "com.google.android.googlequicksearchbox",
                "com.google.android.apps.bard",
                "com.twitter.android",
                "org.telegram.messenger",
            )

        private val SUPPORTED_SWIFTKEY_PACKAGES =
            setOf(
                "com.touchtype.swiftkey",
                "com.touchtype.swiftkey.beta",
            )
    }
}

internal data class QuickInputInsertion(
    val text: String,
    val cursor: Int,
)

/** Builds one plain-text replacement while preserving the target's existing text and selection. */
internal fun buildQuickInputInsertion(
    originalText: String,
    selectionStart: Int,
    selectionEnd: Int,
    dictatedText: String,
): QuickInputInsertion {
    val start = selectionStart.takeIf { it in 0..originalText.length } ?: originalText.length
    val end = selectionEnd.takeIf { it in start..originalText.length } ?: start
    val spoken = dictatedText.trim()
    val before = originalText.substring(0, start)
    val after = originalText.substring(end)
    val prefix = if (needsWordBoundary(before.lastOrNull(), spoken.firstOrNull())) " " else ""
    val suffix = if (needsWordBoundary(spoken.lastOrNull(), after.firstOrNull())) " " else ""
    val insertion = prefix + spoken + suffix
    return QuickInputInsertion(
        text = before + insertion + after,
        cursor = before.length + insertion.length,
    )
}

private fun needsWordBoundary(
    left: Char?,
    right: Char?,
): Boolean = left?.isLetterOrDigit() == true && right?.isLetterOrDigit() == true

internal fun quickInputInsertionConfirmed(
    target: QuickInputAccessibilityTarget,
    dictatedText: String,
    currentText: String,
): Boolean {
    val expected =
        buildQuickInputInsertion(
            originalText = target.originalText,
            selectionStart = target.selectionStart,
            selectionEnd = target.selectionEnd,
            dictatedText = dictatedText,
        ).text
    if (normalizedQuickInputText(currentText) == normalizedQuickInputText(expected)) return true
    if (currentText == target.originalText) return false
    val inserted = insertedTextDelta(target.originalText, currentText)
    return normalizedQuickInputText(inserted).contains(normalizedQuickInputText(dictatedText))
}

private fun insertedTextDelta(
    before: String,
    after: String,
): String {
    var prefix = 0
    while (prefix < before.length && prefix < after.length && before[prefix] == after[prefix]) {
        prefix++
    }
    var suffix = 0
    while (
        suffix < before.length - prefix &&
        suffix < after.length - prefix &&
        before[before.lastIndex - suffix] == after[after.lastIndex - suffix]
    ) {
        suffix++
    }
    return after.substring(prefix, after.length - suffix)
}

private fun normalizedQuickInputText(value: String): String =
    value.trim().replace(Regex("\\s+"), " ")
