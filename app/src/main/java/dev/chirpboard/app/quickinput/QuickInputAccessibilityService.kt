package dev.chirpboard.app.quickinput

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
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
    private var activeEditorPasteText: String? = null
    private var activeEditorPasteDeadlineUptimeMillis = 0L
    private var activeEditorPasteTarget: QuickInputAccessibilityTarget? = null
    private var lastContentTreeScanUptimeMillis = 0L

    private val attemptListener =
        QuickInputAccessibilityCoordinator.Listener { attempt ->
            mainHandler.post { startAttempt(attempt) }
        }
    private val attemptPoll = Runnable { pollAttempt() }
    private val activeEditorPastePoll = Runnable { pollActiveEditorPaste() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        coordinator.attachListener(
            listener = attemptListener,
            refreshTarget = ::captureCurrentTarget,
            activeEditorPaste = { text -> mainHandler.post { startActiveEditorPaste(text) } },
        )
        captureCurrentTarget()
        Log.i(TAG, "Reliable quick-input accessibility enabled")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            -> {
                val scanDescendants =
                    event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                        reserveContentTreeScan()
                val captured =
                    event.source?.let { source ->
                        captureFromSource(source, scanDescendants)
                    } == true
                if (!captured && event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                    captureCurrentTarget()
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            -> captureCurrentTarget()
        }
    }

    override fun onInterrupt() {
        preservePendingFallback()
        preserveActiveEditorPaste()
        resetAttempt()
        resetActiveEditorPaste()
    }

    override fun onDestroy() {
        preservePendingFallback()
        preserveActiveEditorPaste()
        coordinator.detachListener(attemptListener)
        resetAttempt()
        resetActiveEditorPaste()
        super.onDestroy()
    }

    private fun captureCurrentTarget() {
        val activeRoot = runCatching { rootInActiveWindow }.getOrNull()
        if (activeRoot != null && captureBestTarget(activeRoot)) return

        val interactiveWindows = runCatching { windows }.getOrDefault(emptyList())
        for (window in interactiveWindows.sortedWith(compareByDescending { it.isActive })) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            if (captureBestTarget(root)) return
        }
    }

    private fun reserveContentTreeScan(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastContentTreeScanUptimeMillis < CONTENT_TREE_SCAN_INTERVAL_MS) return false
        lastContentTreeScanUptimeMillis = now
        return true
    }

    private fun captureFromSource(
        source: AccessibilityNodeInfo,
        scanDescendants: Boolean = true,
    ): Boolean {
        var current: AccessibilityNodeInfo? = source
        repeat(MAX_PARENT_SCAN) {
            val node = current ?: return@repeat
            if (captureTarget(node)) return true
            current = runCatching { node.parent }.getOrNull()
        }
        return scanDescendants && captureBestTarget(source)
    }

    private fun captureBestTarget(root: AccessibilityNodeInfo): Boolean {
        val focused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (focused != null && captureFromFocusedNode(focused)) return true

        val candidates = findSafeCandidates(root)
        val selectedIndex =
            selectQuickInputCandidateIndex(
                candidates.map { candidate -> candidate.quickInputTraits() },
            ) ?: return false
        return captureTarget(candidates[selectedIndex])
    }

    private fun captureFromFocusedNode(focused: AccessibilityNodeInfo): Boolean {
        if (captureTarget(focused)) return true
        var current = runCatching { focused.parent }.getOrNull()
        repeat(MAX_PARENT_SCAN) {
            val node = current ?: return@repeat
            if (captureTarget(node)) return true
            current = runCatching { node.parent }.getOrNull()
        }
        return captureBestDescendant(focused)
    }

    private fun captureBestDescendant(root: AccessibilityNodeInfo): Boolean {
        val candidates = findSafeCandidates(root, MAX_SOURCE_NODE_SCAN)
        val selectedIndex =
            selectQuickInputCandidateIndex(
                candidates.map { candidate -> candidate.quickInputTraits() },
            ) ?: return false
        return captureTarget(candidates[selectedIndex])
    }

    private fun findSafeCandidates(
        root: AccessibilityNodeInfo,
        maxNodes: Int = MAX_NODE_SCAN,
    ): List<AccessibilityNodeInfo> {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < maxNodes) {
            val node = pending.removeFirst()
            if (isSafeQuickInputCandidate(node.quickInputTraits())) candidates += node
            for (index in 0 until node.childCount) {
                runCatching { node.getChild(index) }.getOrNull()?.let(pending::addLast)
            }
        }
        return candidates
    }

    private fun captureTarget(node: AccessibilityNodeInfo): Boolean {
        val target = snapshotTarget(node) ?: return false
        val remembered = coordinator.rememberTarget(
            target,
        )
        if (remembered) {
            Log.d(
                TAG,
                "Editor target captured " +
                    "(package=${target.packageName}, class=${target.className}, " +
                    "window=${target.windowId}, setText=${target.supportsSetText}, " +
                    "hasViewId=${target.viewIdResourceName.isNotEmpty()})",
            )
        }
        return remembered
    }

    private fun snapshotTarget(node: AccessibilityNodeInfo): QuickInputAccessibilityTarget? {
        val packageName = runCatching { node.packageName?.toString() }.getOrNull() ?: return null
        val traits = node.quickInputTraits()
        if (!isSafeQuickInputCandidate(traits)) return null
        val bounds = Rect()
        runCatching { node.getBoundsInScreen(bounds) }.getOrElse { return null }
        return QuickInputAccessibilityTarget(
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
            originalText = runCatching { node.text?.toString().orEmpty() }.getOrDefault(""),
            selectionStart = runCatching { node.textSelectionStart }.getOrDefault(-1),
            selectionEnd = runCatching { node.textSelectionEnd }.getOrDefault(-1),
            supportsSetText = traits.supportsSetText,
            capturedAtUptimeMillis = SystemClock.uptimeMillis(),
        )
    }

    private fun AccessibilityNodeInfo.quickInputTraits(): QuickInputNodeCandidateTraits {
        val packageName = runCatching { packageName?.toString() }.getOrNull()
        val supportsSetText =
            runCatching {
                actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
            }.getOrDefault(false)
        return QuickInputNodeCandidateTraits(
            packageMonitored = packageName in QuickInputAccessibilityCoordinator.MONITORED_PACKAGES,
            visible = runCatching { isVisibleToUser }.getOrDefault(false),
            password = runCatching { isPassword }.getOrDefault(true),
            editable = runCatching { isEditable }.getOrDefault(false),
            focused = runCatching { isFocused }.getOrDefault(false),
            supportsSetText = supportsSetText,
        )
    }

    private fun startAttempt(attempt: QuickInputAccessibilityAttempt) {
        if (!coordinator.isArmed(attempt.request.sessionId)) return
        resetActiveEditorPaste()
        resetAttempt()
        activeAttempt = attempt
        if (attempt.userInitiated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            mainHandler.postDelayed(attemptPoll, NOTIFICATION_SHADE_SETTLE_MS)
        } else {
            mainHandler.post(attemptPoll)
        }
    }

    private fun startActiveEditorPaste(text: String) {
        val pasteText = text.trim()
        if (pasteText.isEmpty()) return
        resetAttempt()
        resetActiveEditorPaste()
        activeEditorPasteText = pasteText
        activeEditorPasteDeadlineUptimeMillis =
            SystemClock.uptimeMillis() + QuickInputAccessibilityCoordinator.USER_ATTEMPT_WINDOW_MS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            mainHandler.postDelayed(activeEditorPastePoll, NOTIFICATION_SHADE_SETTLE_MS)
        } else {
            mainHandler.post(activeEditorPastePoll)
        }
    }

    private fun pollActiveEditorPaste() {
        val pasteText = activeEditorPasteText ?: return
        if (SystemClock.uptimeMillis() > activeEditorPasteDeadlineUptimeMillis) {
            finishActiveEditorPasteFailure(pasteText)
            return
        }

        var target = activeEditorPasteTarget
        var node =
            if (target == null) {
                findCurrentPasteTarget()?.also { candidate ->
                    target = snapshotTarget(candidate)
                    activeEditorPasteTarget = target
                }
            } else {
                findTargetNode(target, pasteText)
            }
        val resolvedTarget = target
        if (node == null || resolvedTarget == null || !resolvedTarget.supportsSetText) {
            mainHandler.postDelayed(activeEditorPastePoll, POLL_INTERVAL_MS)
            return
        }

        val currentText = runCatching { node.text?.toString().orEmpty() }.getOrDefault("")
        if (quickInputInsertionConfirmed(resolvedTarget, pasteText, currentText)) {
            finishActiveEditorPasteSuccess(node)
            return
        }
        if (currentText != resolvedTarget.originalText) {
            finishActiveEditorPasteFailure(pasteText)
            return
        }
        if (!node.isFocused && !focusAttempted) {
            focusAttempted = true
            performNodeAction(node, AccessibilityNodeInfo.ACTION_FOCUS)
            mainHandler.postDelayed(activeEditorPastePoll, FOCUS_SETTLE_MS)
            return
        }
        if (!setTextAttempted) {
            val insertion =
                buildQuickInputInsertion(
                    originalText = currentText,
                    selectionStart = resolvedTarget.selectionStart,
                    selectionEnd = resolvedTarget.selectionEnd,
                    dictatedText = pasteText,
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
                finishActiveEditorPasteFailure(pasteText)
                return
            }
        }
        mainHandler.postDelayed(activeEditorPastePoll, VERIFY_SETTLE_MS)
    }

    private fun findCurrentPasteTarget(): AccessibilityNodeInfo? {
        val candidates =
            currentRoots()
                .flatMap { root -> findSafeCandidates(root) }
                .filter { candidate -> candidate.quickInputTraits().supportsSetText }
                .distinct()
        val selectedIndex =
            selectQuickInputCandidateIndex(
                candidates.map { candidate -> candidate.quickInputTraits() },
            ) ?: return null
        return candidates[selectedIndex]
    }

    private fun finishActiveEditorPasteSuccess(node: AccessibilityNodeInfo) {
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
        notificationPublisher.cancel()
        Toast.makeText(this, R.string.quick_input_accessibility_pasted, Toast.LENGTH_SHORT).show()
        Log.i(TAG, "Notification tap pasted into the active editor")
        resetActiveEditorPaste()
    }

    private fun finishActiveEditorPasteFailure(text: String) {
        copyInstead(text)
        notificationPublisher.cancel()
        Log.w(TAG, "Notification tap could not find one safe active editor; copied instead")
        resetActiveEditorPaste()
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
        return currentRoots().firstNotNullOfOrNull { root ->
            findTargetNodeInRoot(root, target, expectedText)
        }
    }

    private fun currentRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        runCatching { rootInActiveWindow }.getOrNull()?.let(roots::add)
        runCatching { windows }.getOrDefault(emptyList()).forEach { window ->
            runCatching { window.root }.getOrNull()?.let(roots::add)
        }
        return roots.distinct()
    }

    private fun findTargetNodeInRoot(
        root: AccessibilityNodeInfo,
        target: QuickInputAccessibilityTarget,
        expectedText: String,
    ): AccessibilityNodeInfo? {
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
        val traits = node.quickInputTraits()
        if (node.packageName?.toString() != packageName || node.windowId != windowId) {
            return false
        }
        if (!isSafeQuickInputCandidate(traits)) return false
        if (supportsSetText && !traits.supportsSetText) return false
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
                    pasteIntoActiveEditor = true,
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
            pasteIntoActiveEditor = true,
        )
    }

    private fun preserveActiveEditorPaste() {
        activeEditorPasteText?.let(::copyInstead)
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

    private fun resetActiveEditorPaste() {
        mainHandler.removeCallbacks(activeEditorPastePoll)
        activeEditorPasteText = null
        activeEditorPasteDeadlineUptimeMillis = 0L
        activeEditorPasteTarget = null
        setTextAttempted = false
        focusAttempted = false
        expectedInsertion = null
    }

    private companion object {
        const val TAG = "QuickInputAccessibility"
        const val POLL_INTERVAL_MS = 50L
        const val FOCUS_SETTLE_MS = 250L
        const val VERIFY_SETTLE_MS = 75L
        const val NOTIFICATION_SHADE_SETTLE_MS = 300L
        const val MAX_NODE_SCAN = 512
        const val MAX_SOURCE_NODE_SCAN = 128
        const val MAX_PARENT_SCAN = 8
        const val CONTENT_TREE_SCAN_INTERVAL_MS = 250L
    }
}
