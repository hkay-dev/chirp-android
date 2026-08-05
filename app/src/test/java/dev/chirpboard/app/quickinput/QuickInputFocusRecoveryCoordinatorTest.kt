package dev.chirpboard.app.quickinput

import android.app.Activity
import dev.chirpboard.app.RecognitionActivityResultChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickInputFocusRecoveryCoordinatorTest {
    @Test
    fun `successful SwiftKey activity result arms captured X reply target`() {
        val coordinator = coordinatorWithTarget(now = 1_000L)
        val requests = mutableListOf<QuickInputFocusRecoveryRequest>()
        coordinator.attachListener(requests::add, nowUptimeMillis = 1_001L)
        val session = coordinator.beginSession(SWIFTKEY, nowUptimeMillis = 1_002L)

        val armed =
            coordinator.arm(
                session = session,
                resultChannel = RecognitionActivityResultChannel.ACTIVITY_RESULT,
                resultCode = Activity.RESULT_OK,
                finishImmediately = true,
                nowUptimeMillis = 1_003L,
            )

        assertTrue(armed)
        assertEquals(1, requests.size)
        assertEquals(session?.id, requests.single().sessionId)
        assertEquals(X_REPLY_VIEW_ID, requests.single().target.viewIdResourceName)
        assertEquals(
            1_003L + QuickInputFocusRecoveryCoordinator.RECOVERY_WINDOW_MS,
            requests.single().deadlineUptimeMillis,
        )
    }

    @Test
    fun `beta SwiftKey caller is supported`() {
        val coordinator = coordinatorWithTarget(now = 1_000L)

        assertNotNull(coordinator.beginSession("com.touchtype.swiftkey.beta", nowUptimeMillis = 1_001L))
    }

    @Test
    fun `wrong caller or unsupported target cannot start recovery`() {
        val coordinator = QuickInputFocusRecoveryCoordinator()
        assertFalse(
            coordinator.rememberTarget(
                packageName = "com.twitter.android",
                viewIdResourceName = "com.twitter.android:id/tweet-composer",
                windowId = 4,
                nowUptimeMillis = 1_000L,
            ),
        )
        assertNull(coordinator.beginSession(SWIFTKEY, nowUptimeMillis = 1_001L))

        coordinator.rememberTarget(
            packageName = "com.twitter.android",
            viewIdResourceName = X_REPLY_VIEW_ID,
            windowId = 4,
            nowUptimeMillis = 1_002L,
        )
        assertNull(coordinator.beginSession("com.google.android.inputmethod.latin", nowUptimeMillis = 1_003L))
    }

    @Test
    fun `stale target cannot start recovery`() {
        val coordinator = coordinatorWithTarget(now = 1_000L)

        val session =
            coordinator.beginSession(
                SWIFTKEY,
                nowUptimeMillis = 1_000L + QuickInputFocusRecoveryCoordinator.TARGET_MAX_AGE_MS + 1,
            )

        assertNull(session)
    }

    @Test
    fun `pending intent errors cancellations and animated finishes do not arm`() {
        val rejectedCases =
            listOf(
                Triple(RecognitionActivityResultChannel.PENDING_INTENT, Activity.RESULT_OK, true),
                Triple(RecognitionActivityResultChannel.ACTIVITY_RESULT, Activity.RESULT_CANCELED, true),
                Triple(RecognitionActivityResultChannel.ACTIVITY_RESULT, Activity.RESULT_OK, false),
            )

        rejectedCases.forEachIndexed { index, (channel, resultCode, immediate) ->
            val now = 10_000L + index * 100L
            val coordinator = coordinatorWithTarget(now)
            val requests = mutableListOf<QuickInputFocusRecoveryRequest>()
            coordinator.attachListener(requests::add, nowUptimeMillis = now)
            val session = coordinator.beginSession(SWIFTKEY, nowUptimeMillis = now + 1)

            assertFalse(
                coordinator.arm(
                    session = session,
                    resultChannel = channel,
                    resultCode = resultCode,
                    finishImmediately = immediate,
                    nowUptimeMillis = now + 2,
                ),
            )
            assertTrue(requests.isEmpty())
        }
    }

    @Test
    fun `new session supersedes older token`() {
        val coordinator = coordinatorWithTarget(now = 1_000L)
        val oldSession = coordinator.beginSession(SWIFTKEY, nowUptimeMillis = 1_001L)
        val newSession = coordinator.beginSession(SWIFTKEY, nowUptimeMillis = 1_002L)

        assertFalse(
            coordinator.arm(
                session = oldSession,
                resultChannel = RecognitionActivityResultChannel.ACTIVITY_RESULT,
                resultCode = Activity.RESULT_OK,
                finishImmediately = true,
                nowUptimeMillis = 1_003L,
            ),
        )
        assertTrue(
            coordinator.arm(
                session = newSession,
                resultChannel = RecognitionActivityResultChannel.ACTIVITY_RESULT_FALLBACK,
                resultCode = Activity.RESULT_OK,
                finishImmediately = true,
                nowUptimeMillis = 1_004L,
            ),
        )
    }

    @Test
    fun `new session disarms older queued recovery`() {
        val coordinator = coordinatorWithTarget(now = 1_000L)
        val oldSession = coordinator.beginSession(SWIFTKEY, nowUptimeMillis = 1_001L)
        coordinator.arm(
            session = oldSession,
            resultChannel = RecognitionActivityResultChannel.ACTIVITY_RESULT,
            resultCode = Activity.RESULT_OK,
            finishImmediately = true,
            nowUptimeMillis = 1_002L,
        )
        assertTrue(coordinator.isArmed(requireNotNull(oldSession).id))

        coordinator.beginSession(SWIFTKEY, nowUptimeMillis = 1_003L)

        assertFalse(coordinator.isArmed(oldSession.id))
    }

    @Test
    fun `late service attachment receives one live request and completion is idempotent`() {
        val coordinator = coordinatorWithTarget(now = 1_000L)
        val session = coordinator.beginSession(SWIFTKEY, nowUptimeMillis = 1_001L)
        assertTrue(
            coordinator.arm(
                session = session,
                resultChannel = RecognitionActivityResultChannel.ACTIVITY_RESULT,
                resultCode = Activity.RESULT_OK,
                finishImmediately = true,
                nowUptimeMillis = 1_002L,
            ),
        )
        val requests = mutableListOf<QuickInputFocusRecoveryRequest>()

        coordinator.attachListener(requests::add, nowUptimeMillis = 1_003L)

        val request = requests.single()
        assertTrue(coordinator.complete(request.sessionId))
        assertFalse(coordinator.complete(request.sessionId))
    }

    @Test
    fun `service attachment during recognition keeps unarmed session`() {
        val coordinator = coordinatorWithTarget(now = 1_000L)
        val session = coordinator.beginSession(SWIFTKEY, nowUptimeMillis = 1_001L)
        val requests = mutableListOf<QuickInputFocusRecoveryRequest>()
        coordinator.attachListener(requests::add, nowUptimeMillis = 1_002L)

        assertTrue(
            coordinator.arm(
                session = session,
                resultChannel = RecognitionActivityResultChannel.ACTIVITY_RESULT,
                resultCode = Activity.RESULT_OK,
                finishImmediately = true,
                nowUptimeMillis = 1_003L,
            ),
        )
        assertEquals(1, requests.size)
    }

    @Test
    fun `expired request is not replayed to restarted service`() {
        val coordinator = coordinatorWithTarget(now = 1_000L)
        val session = coordinator.beginSession(SWIFTKEY, nowUptimeMillis = 1_001L)
        coordinator.arm(
            session = session,
            resultChannel = RecognitionActivityResultChannel.ACTIVITY_RESULT,
            resultCode = Activity.RESULT_OK,
            finishImmediately = true,
            nowUptimeMillis = 1_002L,
        )
        val requests = mutableListOf<QuickInputFocusRecoveryRequest>()

        coordinator.attachListener(
            requests::add,
            nowUptimeMillis = 1_002L + QuickInputFocusRecoveryCoordinator.RECOVERY_WINDOW_MS + 1,
        )

        assertTrue(requests.isEmpty())
    }

    private fun coordinatorWithTarget(now: Long): QuickInputFocusRecoveryCoordinator =
        QuickInputFocusRecoveryCoordinator().also { coordinator ->
            assertTrue(
                coordinator.rememberTarget(
                    packageName = "com.twitter.android",
                    viewIdResourceName = X_REPLY_VIEW_ID,
                    windowId = 4,
                    nowUptimeMillis = now,
                ),
            )
        }

    private companion object {
        const val SWIFTKEY = "com.touchtype.swiftkey"
        const val X_REPLY_VIEW_ID = "com.twitter.android:id/post-detail-reply-text-field"
    }
}
