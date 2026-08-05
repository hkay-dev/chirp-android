package dev.chirpboard.app.quickinput

import android.app.Activity
import android.os.SystemClock
import dev.chirpboard.app.RecognitionActivityResultChannel
import javax.inject.Inject
import javax.inject.Singleton

/** A metadata-only pointer to the X inline-reply editor last focused by the user. */
internal data class QuickInputFocusTarget(
    val packageName: String,
    val viewIdResourceName: String,
    val windowId: Int,
    val capturedAtUptimeMillis: Long,
)

/** Opaque token tying one recognition activity to the editor that launched it. */
internal data class QuickInputFocusRecoverySession(
    val id: Long,
)

/** One bounded request for the accessibility service to restore the captured editor's focus. */
internal data class QuickInputFocusRecoveryRequest(
    val sessionId: Long,
    val target: QuickInputFocusTarget,
    val deadlineUptimeMillis: Long,
)

/**
 * Bridges the quick-input activity and the opt-in accessibility service without carrying any
 * transcript content. A session snapshots a fresh X inline-reply target when SwiftKey opens the
 * recognizer, then becomes actionable only after a successful immediate activity result.
 */
@Singleton
class QuickInputFocusRecoveryCoordinator
    @Inject
    constructor() {
        internal fun interface Listener {
            fun onRecoveryRequested(request: QuickInputFocusRecoveryRequest)
        }

        private data class SessionState(
            val token: QuickInputFocusRecoverySession,
            val target: QuickInputFocusTarget,
        )

        private val lock = Any()
        private var nextSessionId = 0L
        private var latestTarget: QuickInputFocusTarget? = null
        private var currentSession: SessionState? = null
        private var armedRequest: QuickInputFocusRecoveryRequest? = null
        private var listener: Listener? = null

        /** Remembers only the package, resource ID, window ID, and time. No node or text is kept. */
        internal fun rememberTarget(
            packageName: String,
            viewIdResourceName: String,
            windowId: Int,
            nowUptimeMillis: Long = SystemClock.uptimeMillis(),
        ): Boolean {
            if (!isSupportedTarget(packageName, viewIdResourceName) || windowId < 0) {
                return false
            }
            synchronized(lock) {
                latestTarget =
                    QuickInputFocusTarget(
                        packageName = packageName,
                        viewIdResourceName = viewIdResourceName,
                        windowId = windowId,
                        capturedAtUptimeMillis = nowUptimeMillis,
                    )
            }
            return true
        }

        /** Clears a target when X moves input focus to a different editable control. */
        internal fun clearTarget() {
            synchronized(lock) {
                latestTarget = null
            }
        }

        /**
         * Starts a new recognition session and snapshots its launch editor. New sessions always
         * supersede older pending work, so an old result can never refocus a later composer.
         */
        internal fun beginSession(
            callingPackage: String?,
            nowUptimeMillis: Long = SystemClock.uptimeMillis(),
        ): QuickInputFocusRecoverySession? =
            synchronized(lock) {
                currentSession = null
                armedRequest = null
                if (callingPackage !in SUPPORTED_SWIFTKEY_PACKAGES) {
                    return@synchronized null
                }
                val target = latestTarget ?: return@synchronized null
                val targetAge = nowUptimeMillis - target.capturedAtUptimeMillis
                if (targetAge !in 0..TARGET_MAX_AGE_MS) {
                    latestTarget = null
                    return@synchronized null
                }
                val token = QuickInputFocusRecoverySession(id = ++nextSessionId)
                currentSession = SessionState(token = token, target = target)
                token
            }

        /**
         * Arms one focus recovery only for the successful, immediate activity-result path.
         * PendingIntent delivery is deliberately excluded because SwiftKey's deferred commit is
         * the activity-result behavior this mode repairs.
         */
        internal fun arm(
            session: QuickInputFocusRecoverySession?,
            resultChannel: RecognitionActivityResultChannel,
            resultCode: Int,
            finishImmediately: Boolean,
            nowUptimeMillis: Long = SystemClock.uptimeMillis(),
        ): Boolean {
            val dispatch =
                synchronized(lock) {
                    val state = currentSession
                    val eligible =
                        session != null &&
                            state?.token == session &&
                            resultCode == Activity.RESULT_OK &&
                            finishImmediately &&
                            resultChannel in ACTIVITY_RESULT_CHANNELS
                    if (!eligible) {
                        if (session != null && state?.token == session) {
                            currentSession = null
                            armedRequest = null
                        }
                        return@synchronized null
                    }
                    val request =
                        QuickInputFocusRecoveryRequest(
                            sessionId = session.id,
                            target = requireNotNull(state).target,
                            deadlineUptimeMillis = nowUptimeMillis + RECOVERY_WINDOW_MS,
                        )
                    armedRequest = request
                    listener to request
                } ?: return false

            dispatch.first?.onRecoveryRequested(dispatch.second)
            return true
        }

        /** Cancels an unarmed session. Armed work is owned by the service until completion. */
        internal fun cancel(session: QuickInputFocusRecoverySession?): Boolean =
            synchronized(lock) {
                if (session == null || currentSession?.token != session) {
                    return@synchronized false
                }
                currentSession = null
                armedRequest = null
                true
            }

        /** Completes or expires one request, rejecting stale callbacks from older sessions. */
        internal fun complete(sessionId: Long): Boolean =
            synchronized(lock) {
                if (armedRequest?.sessionId != sessionId) {
                    return@synchronized false
                }
                armedRequest = null
                currentSession = null
                true
            }

        /** Lets the service reject queued callbacks after a newer session supersedes them. */
        internal fun isArmed(sessionId: Long): Boolean =
            synchronized(lock) {
                armedRequest?.sessionId == sessionId
            }

        /** Attaches the live accessibility service and replays a still-valid armed request. */
        internal fun attachListener(
            listener: Listener,
            nowUptimeMillis: Long = SystemClock.uptimeMillis(),
        ) {
            val pending =
                synchronized(lock) {
                    this.listener = listener
                    val request = armedRequest
                    when {
                        request == null -> null
                        request.deadlineUptimeMillis >= nowUptimeMillis -> request
                        else -> {
                            armedRequest = null
                            currentSession = null
                            null
                        }
                    }
                }
            pending?.let(listener::onRecoveryRequested)
        }

        internal fun detachListener(listener: Listener) {
            synchronized(lock) {
                if (this.listener === listener) {
                    this.listener = null
                }
            }
        }

        companion object {
            internal const val TARGET_PACKAGE = "com.twitter.android"
            internal const val TARGET_VIEW_ID_SUFFIX = ":id/post-detail-reply-text-field"
            internal const val TARGET_MAX_AGE_MS = 5 * 60_000L
            internal const val RECOVERY_WINDOW_MS = 5_000L

            private val SUPPORTED_SWIFTKEY_PACKAGES =
                setOf(
                    "com.touchtype.swiftkey",
                    "com.touchtype.swiftkey.beta",
                )
            private val ACTIVITY_RESULT_CHANNELS =
                setOf(
                    RecognitionActivityResultChannel.ACTIVITY_RESULT,
                    RecognitionActivityResultChannel.ACTIVITY_RESULT_FALLBACK,
                )

            internal fun isSupportedTarget(
                packageName: String,
                viewIdResourceName: String,
            ): Boolean =
                packageName == TARGET_PACKAGE &&
                    viewIdResourceName.endsWith(TARGET_VIEW_ID_SUFFIX)
        }
    }
