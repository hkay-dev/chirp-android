package dev.chirpboard.app.quickinput

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.R
import dev.chirpboard.app.feature.transcription.QuickInputResultNotificationPublisher
import java.util.ArrayDeque
import javax.inject.Inject

/**
 * Opt-in, package-limited quick-input bridge for editors that lose SwiftKey's result connection.
 * Text and node snapshots stay in memory, every action is tied to one recent session, and changed
 * or sensitive fields fail closed to the notification/clipboard fallback.
 */
@AndroidEntryPoint
class QuickInputAccessibilityService : AccessibilityService() {
    @Inject lateinit var coordinator: QuickInputAccessibilityCoordinator

    @Inject lateinit var notificationPublisher: QuickInputResultNotificationPublisher

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeAttempt: QuickInputAccessibilityAttempt? = null
    private var setTextAttempted = false
    private var focusAttempted = false
    private var expectedInsertion: QuickInputInsertion? = null

    private val attemptListener =
        QuickInputAccessibilityCoordinator.Listener { attempt ->
            mainHandler.post { startAttempt(attempt) }
        }
    private val attemptPoll = Runnable { pollAttempt() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        coordinator.attachListener(attemptListener, ::captureCurrentTarget)
        captureCurrentTarget()
        Log.i(TAG, "Reliable quick-input accessibility enabled")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            -> event.source?.let(::captureTarget)

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            -> captureCurrentTarget()
        }
    }

    override fun onInterrupt() {
        preservePendingFallback()
        resetAttempt()
    }

    override fun onDestroy() {
        preservePendingFallback()
        coordinator.detachListener(attemptListener)
        resetAttempt()
        super.onDestroy()
    }

    private fun captureCurrentTarget() {
        val activeRoot = runCatching { rootInActiveWindow }.getOrNull()
        val activeNode = runCatching { activeRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (activeNode != null && captureTarget(activeNode)) return

        val interactiveWindows = runCatching { windows }.getOrDefault(emptyList())
        for (window in interactiveWindows.sortedByDescending { window -> window.isActive }) {
            val node =
                runCatching {
                    window.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                }.getOrNull() ?: continue
            if (captureTarget(node)) return
        }
    }

    private fun captureTarget(node: AccessibilityNodeInfo): Boolean {
        val packageName = node.packageName?.toString() ?: return false
        if (
            packageName !in QuickInputAccessibilityCoordinator.MONITORED_PACKAGES ||
            !node.isEditable ||
            !node.isVisibleToUser ||
            node.isPassword
        ) {
            return false
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return coordinator.rememberTarget(
            QuickInputAccessibilityTarget(
                packageName = packageName,
                windowId = node.windowId,
                viewIdResourceName = node.viewIdResourceName.orEmpty(),
                className = node.className?.toString().orEmpty(),
                bounds =
                    QuickInputNodeBounds(
                        left = bounds.left,
                        top = bounds.top,
                        right = bounds.right,
                        bottom = bounds.bottom,
                    ),
                originalText = node.text?.toString().orEmpty(),
                selectionStart = node.textSelectionStart,
                selectionEnd = node.textSelectionEnd,
                supportsSetText =
                    node.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SET_TEXT },
                capturedAtUptimeMillis = SystemClock.uptimeMillis(),
            ),
        )
    }

    private fun startAttempt(attempt: QuickInputAccessibilityAttempt) {
        if (!coordinator.isArmed(attempt.request.sessionId)) return
        resetAttempt()
        activeAttempt = attempt
        mainHandler.post(attemptPoll)
    }

    private fun pollAttempt() {
        val attempt = activeAttempt ?: return
        if (!coordinator.isArmed(attempt.request.sessionId)) {
            resetAttempt()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now > attempt.attemptDeadlineUptimeMillis) {
            finishFailure(attempt)
            return
        }
        val node = findTargetNode(attempt.request.target, attempt.text)
        if (node == null) {
            mainHandler.postDelayed(attemptPoll, POLL_INTERVAL_MS)
            return
        }
        val currentText = node.text?.toString().orEmpty()
        if (quickInputInsertionConfirmed(attempt.request.target, attempt.text, currentText)) {
            finishSuccess(attempt, node)
            return
        }
        if (!attempt.userInitiated) {
            mainHandler.postDelayed(attemptPoll, POLL_INTERVAL_MS)
            return
        }
        if (currentText != attempt.request.target.originalText) {
            finishFailure(attempt)
            return
        }

        if (attempt.userInitiated && !node.isFocused && !focusAttempted) {
            focusAttempted = true
            performNodeAction(node, AccessibilityNodeInfo.ACTION_FOCUS)
            mainHandler.postDelayed(attemptPoll, FOCUS_SETTLE_MS)
            return
        }
        if (!setTextAttempted) {
            val insertion =
                buildQuickInputInsertion(
                    originalText = currentText,
                    selectionStart = attempt.request.target.selectionStart,
                    selectionEnd = attempt.request.target.selectionEnd,
                    dictatedText = attempt.text,
                )
            val arguments =
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        insertion.text,
                    )
                }
            setTextAttempted = true
            expectedInsertion = insertion
            if (!performNodeAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                finishFailure(attempt)
                return
            }
        }
        mainHandler.postDelayed(attemptPoll, VERIFY_SETTLE_MS)
    }

    private fun findTargetNode(
        target: QuickInputAccessibilityTarget,
        expectedText: String,
    ): AccessibilityNodeInfo? {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        if (root.packageName?.toString() != target.packageName || root.windowId != target.windowId) return null
        val focused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (focused != null && target.hasSameIdentity(focused)) return focused
        if (target.viewIdResourceName.isNotEmpty()) {
            val byId =
                runCatching { root.findAccessibilityNodeInfosByViewId(target.viewIdResourceName) }
                    .getOrDefault(emptyList())
                    .firstOrNull { node -> target.hasSameIdentity(node) }
            if (byId != null) return byId
        }
        return findMatchingNode(root, target, expectedText)
    }

    private fun findMatchingNode(
        root: AccessibilityNodeInfo,
        target: QuickInputAccessibilityTarget,
        expectedText: String,
    ): AccessibilityNodeInfo? {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        val compatible = mutableListOf<AccessibilityNodeInfo>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < MAX_NODE_SCAN) {
            val node = pending.removeFirst()
            if (target.hasSameIdentity(node)) {
                if (target.hasSameBounds(node)) return node
                val text = node.text?.toString().orEmpty()
                if (
                    text == target.originalText ||
                    quickInputInsertionConfirmed(target, expectedText, text)
                ) {
                    compatible += node
                }
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(pending::addLast)
            }
        }
        return compatible.singleOrNull()
    }

    private fun QuickInputAccessibilityTarget.hasSameIdentity(node: AccessibilityNodeInfo): Boolean {
        if (
            node.packageName?.toString() != packageName ||
            node.windowId != windowId ||
            !node.isEditable ||
            !node.isVisibleToUser ||
            node.isPassword
        ) {
            return false
        }
        if (viewIdResourceName.isNotEmpty()) return node.viewIdResourceName == viewIdResourceName
        return className.isEmpty() || node.className?.toString() == className
    }

    private fun QuickInputAccessibilityTarget.hasSameBounds(node: AccessibilityNodeInfo): Boolean {
        val nodeBounds = Rect()
        node.getBoundsInScreen(nodeBounds)
        return nodeBounds.left == bounds.left &&
            nodeBounds.top == bounds.top &&
            nodeBounds.right == bounds.right &&
            nodeBounds.bottom == bounds.bottom
    }

    private fun finishSuccess(
        attempt: QuickInputAccessibilityAttempt,
        node: AccessibilityNodeInfo,
    ) {
        expectedInsertion?.let { insertion ->
            performNodeAction(
                node,
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, insertion.cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, insertion.cursor)
                },
            )
        }
        coordinator.complete(attempt.request.sessionId)
        notificationPublisher.cancel()
        if (attempt.userInitiated) {
            Toast.makeText(this, R.string.quick_input_accessibility_pasted, Toast.LENGTH_SHORT).show()
        }
        resetAttempt()
    }

    private fun finishFailure(attempt: QuickInputAccessibilityAttempt) {
        when {
            attempt.userInitiated -> {
                copyInstead(attempt.text)
                coordinator.complete(attempt.request.sessionId)
                notificationPublisher.cancel()
            }

            else -> {
                notificationPublisher.show(
                    rawText = attempt.request.rawText,
                    processedText = attempt.request.processedText,
                    pasteSessionId =
                        attempt.request.sessionId.takeIf {
                            attempt.request.target.supportsSetText
                        },
                )
                if (!attempt.request.target.supportsSetText) {
                    coordinator.complete(attempt.request.sessionId)
                }
            }
        }
        resetAttempt()
    }

    private fun copyInstead(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (clipboard != null) {
            clipboard.setPrimaryClip(
                ClipData.newPlainText(getString(R.string.quick_input_accessibility_clip_label), text),
            )
            Toast.makeText(this, R.string.quick_input_accessibility_copied_instead, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.quick_input_accessibility_paste_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun preservePendingFallback() {
        val request = coordinator.currentRequest() ?: return
        notificationPublisher.show(
            rawText = request.rawText,
            processedText = request.processedText,
            pasteSessionId = request.sessionId.takeIf { request.target.supportsSetText },
        )
    }

    private fun performNodeAction(
        node: AccessibilityNodeInfo,
        action: Int,
        arguments: Bundle? = null,
    ): Boolean = runCatching { node.performAction(action, arguments) }.getOrDefault(false)

    private fun resetAttempt() {
        mainHandler.removeCallbacks(attemptPoll)
        activeAttempt = null
        setTextAttempted = false
        focusAttempted = false
        expectedInsertion = null
    }

    private companion object {
        const val TAG = "QuickInputAccessibility"
        const val POLL_INTERVAL_MS = 50L
        const val FOCUS_SETTLE_MS = 250L
        const val VERIFY_SETTLE_MS = 75L
        const val MAX_NODE_SCAN = 512
    }
}
