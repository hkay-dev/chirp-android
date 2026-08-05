package dev.chirpboard.app.quickinput

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Opt-in X compatibility service for SwiftKey's deferred quick-input result.
 *
 * This service never reads node text and never inserts text. It remembers only the resource ID
 * and window ID of X's inline-reply editor, then performs at most one input-focus cycle after
 * Chirp has returned a successful activity result to SwiftKey.
 */
@AndroidEntryPoint
class QuickInputFocusRecoveryAccessibilityService : AccessibilityService() {
    @Inject lateinit var coordinator: QuickInputFocusRecoveryCoordinator

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeRequest: QuickInputFocusRecoveryRequest? = null
    private var clearAttempted = false
    private var focusAttempted = false
    private var focusAccepted = false
    private var focusedTargetSeenAtUptimeMillis: Long? = null

    private val recoveryListener =
        QuickInputFocusRecoveryCoordinator.Listener { request ->
            // Posting yields the main thread back to VoiceRecognitionActivity so finish() can
            // start restoring X's window before the first hierarchy lookup.
            mainHandler.post { startRecovery(request) }
        }

    private val recoveryPoll = Runnable { pollForTarget() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        coordinator.attachListener(recoveryListener)
        captureCurrentInputTarget()
        Log.i(TAG, "X quick-input focus recovery enabled")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            -> {
                val source = event.source ?: return
                if (
                    event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
                    activeRequest?.target?.matches(source) == true
                ) {
                    // A real tap has already done what recovery would do. End the request so the
                    // automated cycle cannot fight the user or briefly drop the new connection.
                    finishRecovery(activeRequest, "user restored target focus")
                }
                captureTarget(source, clearUnsupportedEditable = true)
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> captureCurrentInputTarget()
        }
    }

    override fun onInterrupt() {
        finishRecovery(activeRequest, "service interrupted")
    }

    override fun onDestroy() {
        coordinator.detachListener(recoveryListener)
        finishRecovery(activeRequest, "service destroyed")
        super.onDestroy()
    }

    private fun captureCurrentInputTarget() {
        val focusedNode =
            runCatching {
                rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            }.getOrNull() ?: return
        captureTarget(focusedNode, clearUnsupportedEditable = false)
    }

    private fun captureTarget(
        node: AccessibilityNodeInfo,
        clearUnsupportedEditable: Boolean,
    ) {
        val packageName = node.packageName?.toString() ?: return
        if (packageName != QuickInputFocusRecoveryCoordinator.TARGET_PACKAGE || !node.isEditable) {
            return
        }
        val viewId = node.viewIdResourceName.orEmpty()
        if (
            node.isVisibleToUser &&
            QuickInputFocusRecoveryCoordinator.isSupportedTarget(packageName, viewId)
        ) {
            coordinator.rememberTarget(
                packageName = packageName,
                viewIdResourceName = viewId,
                windowId = node.windowId,
            )
        } else if (clearUnsupportedEditable) {
            coordinator.clearTarget()
        }
    }

    private fun startRecovery(request: QuickInputFocusRecoveryRequest) {
        if (!coordinator.isArmed(request.sessionId)) {
            return
        }
        val previous = activeRequest
        if (previous?.sessionId != request.sessionId) {
            previous?.let { coordinator.complete(it.sessionId) }
        }
        mainHandler.removeCallbacks(recoveryPoll)
        activeRequest = request
        clearAttempted = false
        focusAttempted = false
        focusAccepted = false
        focusedTargetSeenAtUptimeMillis = null
        mainHandler.post(recoveryPoll)
    }

    private fun pollForTarget() {
        val request = activeRequest ?: return
        if (!coordinator.isArmed(request.sessionId)) {
            finishRecovery(request, "superseded by newer session")
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now > request.deadlineUptimeMillis) {
            finishRecovery(request, "target window timeout")
            return
        }

        val node = findTargetNode(request.target)
        if (node == null) {
            mainHandler.postDelayed(recoveryPoll, POLL_INTERVAL_MS)
            return
        }

        if (!clearAttempted && node.isFocused) {
            val firstSeen = focusedTargetSeenAtUptimeMillis
            if (firstSeen == null) {
                // Give a just-landed real tap time to emit TYPE_VIEW_CLICKED. That event cancels
                // recovery, preventing this compatibility path from clearing the user's focus.
                focusedTargetSeenAtUptimeMillis = now
                mainHandler.postDelayed(recoveryPoll, USER_FOCUS_GRACE_MS)
                return
            }
            clearAttempted = true
            val cleared = performNodeAction(node, AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
            if (cleared) {
                mainHandler.postDelayed(recoveryPoll, FOCUS_SETTLE_MS)
                return
            }
        } else {
            clearAttempted = true
        }

        if (!focusAttempted) {
            // Resolve the node again on the following frame before clicking. Accessibility nodes
            // can become stale as focus changes, and X rebuilds this Compose editor during resume.
            focusAttempted = true
            focusAccepted = performNodeAction(node, AccessibilityNodeInfo.ACTION_FOCUS)
            mainHandler.postDelayed(recoveryPoll, FOCUS_SETTLE_MS)
            return
        }

        // ACTION_CLICK mirrors the user's proven workaround and prompts X's Compose editor to
        // reopen its input session. Focus and click are each attempted only once.
        val clicked = performNodeAction(node, AccessibilityNodeInfo.ACTION_CLICK)
        finishRecovery(
            request,
            if (focusAccepted || clicked) "focus cycle accepted" else "focus cycle rejected",
        )
    }

    private fun findTargetNode(target: QuickInputFocusTarget): AccessibilityNodeInfo? {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        if (
            root.packageName?.toString() != target.packageName ||
            root.windowId != target.windowId
        ) {
            return null
        }
        return runCatching {
            root.findAccessibilityNodeInfosByViewId(target.viewIdResourceName).firstOrNull { node ->
                target.matches(node) && node.isEditable && node.isVisibleToUser
            }
        }.getOrNull()
    }

    private fun performNodeAction(
        node: AccessibilityNodeInfo,
        action: Int,
    ): Boolean = runCatching { node.performAction(action) }.getOrDefault(false)

    private fun finishRecovery(
        request: QuickInputFocusRecoveryRequest?,
        outcome: String,
    ) {
        request ?: return
        if (activeRequest?.sessionId != request.sessionId) {
            return
        }
        mainHandler.removeCallbacks(recoveryPoll)
        activeRequest = null
        clearAttempted = false
        focusAttempted = false
        focusAccepted = false
        focusedTargetSeenAtUptimeMillis = null
        coordinator.complete(request.sessionId)
        Log.i(TAG, "X quick-input focus recovery ended: $outcome")
    }

    private fun QuickInputFocusTarget.matches(node: AccessibilityNodeInfo): Boolean =
        node.packageName?.toString() == packageName &&
            node.viewIdResourceName == viewIdResourceName &&
            node.windowId == windowId

    private companion object {
        const val TAG = "QuickInputFocusRecovery"
        const val POLL_INTERVAL_MS = 50L
        const val FOCUS_SETTLE_MS = 32L
        const val USER_FOCUS_GRACE_MS = 100L
    }
}
