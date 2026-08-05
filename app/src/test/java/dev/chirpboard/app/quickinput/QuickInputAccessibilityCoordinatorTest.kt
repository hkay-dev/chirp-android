package dev.chirpboard.app.quickinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickInputAccessibilityCoordinatorTest {
    @Test
    fun `supported SwiftKey session verifies a recent monitored editor`() {
        val attempts = mutableListOf<QuickInputAccessibilityAttempt>()
        val coordinator = coordinatorWithListener(attempts)
        assertTrue(coordinator.rememberTarget(target(now = 1_000L)))

        val session = coordinator.beginSession(SWIFTKEY, nowUptimeMillis = 1_001L)
        val request =
            coordinator.arm(
                session = session,
                deliveredText = "Polished words.",
                rawText = "raw words",
                processedText = "Polished words.",
                nowUptimeMillis = 1_002L,
            )

        assertNotNull(session)
        assertNotNull(request)
        assertEquals(1, attempts.size)
        assertEquals("Polished words.", attempts.single().text)
        assertFalse(attempts.single().userInitiated)
        assertEquals(
            1_002L + QuickInputAccessibilityCoordinator.REQUEST_WINDOW_MS,
            request?.deadlineUptimeMillis,
        )
    }

    @Test
    fun `session requires SwiftKey an enabled service and a monitored editor`() {
        val coordinator = QuickInputAccessibilityCoordinator()
        assertFalse(coordinator.rememberTarget(target(packageName = "example.app", now = 1_000L)))
        assertTrue(coordinator.rememberTarget(target(now = 1_001L)))
        assertNull(coordinator.beginSession(SWIFTKEY, nowUptimeMillis = 1_002L))

        coordinator.attachListener(listener = { })
        assertNull(coordinator.beginSession("com.google.android.inputmethod.latin", nowUptimeMillis = 1_003L))
    }

    @Test
    fun `stale editor is not reused`() {
        val coordinator = coordinatorWithListener()
        assertTrue(coordinator.rememberTarget(target(now = 1_000L)))

        assertNull(
            coordinator.beginSession(
                SWIFTKEY,
                nowUptimeMillis = 1_000L + QuickInputAccessibilityCoordinator.TARGET_MAX_AGE_MS + 1,
            ),
        )
    }

    @Test
    fun `notification tap requests the selected text once the editor failed verification`() {
        val attempts = mutableListOf<QuickInputAccessibilityAttempt>()
        val coordinator = coordinatorWithListener(attempts)
        coordinator.rememberTarget(target(now = 1_000L))
        val session = coordinator.beginSession(SWIFTKEY, nowUptimeMillis = 1_001L)
        val request =
            requireNotNull(
                coordinator.arm(
                    session = session,
                    deliveredText = "Polished words.",
                    rawText = "raw words",
                    processedText = "Polished words.",
                    nowUptimeMillis = 1_002L,
                ),
            )

        assertTrue(
            coordinator.requestPasteAt(
                sessionId = request.sessionId,
                useProcessedText = true,
                nowUptimeMillis = 1_100L,
            ),
        )
        assertEquals(2, attempts.size)
        assertEquals("Polished words.", attempts.last().text)
        assertTrue(attempts.last().userInitiated)
        assertTrue(attempts.last().useProcessedText)
    }

    @Test
    fun `paste request fails closed for expired or unsupported targets`() {
        val coordinator = coordinatorWithListener()
        coordinator.rememberTarget(target(supportsSetText = false, now = 1_000L))
        val session = coordinator.beginSession(SWIFTKEY, nowUptimeMillis = 1_001L)
        val request =
            requireNotNull(
                coordinator.arm(
                    session = session,
                    deliveredText = "words",
                    rawText = "words",
                    processedText = null,
                    nowUptimeMillis = 1_002L,
                ),
            )

        assertFalse(coordinator.requestPasteAt(request.sessionId, false, 1_100L))
        assertFalse(
            coordinator.requestPasteAt(
                request.sessionId,
                false,
                request.deadlineUptimeMillis + 1,
            ),
        )
    }

    @Test
    fun `insertion preserves selection and adds only needed word boundaries`() {
        assertEquals(
            QuickInputInsertion(text = "Hello brave world", cursor = 12),
            buildQuickInputInsertion(
                originalText = "Hello world",
                selectionStart = 6,
                selectionEnd = 6,
                dictatedText = "brave",
            ),
        )
        assertEquals(
            QuickInputInsertion(text = "Hello there!", cursor = 11),
            buildQuickInputInsertion(
                originalText = "Hello world!",
                selectionStart = 6,
                selectionEnd = 11,
                dictatedText = "there",
            ),
        )
    }

    @Test
    fun `verification accepts exact insertion and rejects unchanged text`() {
        val target = target(originalText = "Hello", selectionStart = 5, selectionEnd = 5)

        assertTrue(quickInputInsertionConfirmed(target, "world", "Hello world"))
        assertFalse(quickInputInsertionConfirmed(target, "world", "Hello"))
    }

    @Test
    fun `compose editor with set text support is safe even when not marked editable`() {
        assertTrue(
            isSafeQuickInputCandidate(
                candidateTraits(editable = false, supportsSetText = true),
            ),
        )
    }

    @Test
    fun `hidden password and read only nodes are never safe paste targets`() {
        assertFalse(isSafeQuickInputCandidate(candidateTraits(visible = false)))
        assertFalse(isSafeQuickInputCandidate(candidateTraits(password = true)))
        assertFalse(
            isSafeQuickInputCandidate(
                candidateTraits(editable = false, supportsSetText = false),
            ),
        )
    }

    @Test
    fun `candidate selection favors the focused set text editor`() {
        val candidates =
            listOf(
                candidateTraits(focused = false),
                candidateTraits(focused = true, editable = false, supportsSetText = true),
            )

        assertEquals(1, selectQuickInputCandidateIndex(candidates))
    }

    @Test
    fun `candidate selection rejects ambiguous unfocused editors`() {
        val candidates =
            listOf(
                candidateTraits(focused = false),
                candidateTraits(focused = false),
            )

        assertNull(selectQuickInputCandidateIndex(candidates))
    }

    @Test
    fun `active editor paste is offered only while the accessibility service is attached`() {
        val pasted = mutableListOf<String>()
        val coordinator = QuickInputAccessibilityCoordinator()
        assertFalse(coordinator.requestPasteIntoActiveEditor("words"))

        coordinator.attachListener(
            listener = { },
            activeEditorPaste = pasted::add,
        )

        assertTrue(coordinator.canPasteIntoActiveEditor())
        assertTrue(coordinator.requestPasteIntoActiveEditor(" words "))
        assertEquals(listOf("words"), pasted)
    }

    private fun coordinatorWithListener(
        attempts: MutableList<QuickInputAccessibilityAttempt> = mutableListOf(),
    ): QuickInputAccessibilityCoordinator =
        QuickInputAccessibilityCoordinator().also { coordinator ->
            coordinator.attachListener(attempts::add)
        }

    private fun target(
        packageName: String = "com.twitter.android",
        originalText: String = "",
        selectionStart: Int = originalText.length,
        selectionEnd: Int = selectionStart,
        supportsSetText: Boolean = true,
        now: Long = 1_000L,
    ): QuickInputAccessibilityTarget =
        QuickInputAccessibilityTarget(
            packageName = packageName,
            windowId = 7,
            viewIdResourceName = "$packageName:id/reply",
            className = "android.widget.EditText",
            bounds = QuickInputNodeBounds(0, 100, 1_000, 240),
            originalText = originalText,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            supportsSetText = supportsSetText,
            capturedAtUptimeMillis = now,
        )

    private fun candidateTraits(
        packageMonitored: Boolean = true,
        visible: Boolean = true,
        password: Boolean = false,
        editable: Boolean = true,
        focused: Boolean = true,
        supportsSetText: Boolean = true,
    ): QuickInputNodeCandidateTraits =
        QuickInputNodeCandidateTraits(
            packageMonitored = packageMonitored,
            visible = visible,
            password = password,
            editable = editable,
            focused = focused,
            supportsSetText = supportsSetText,
        )

    private companion object {
        const val SWIFTKEY = "com.touchtype.swiftkey"
    }
}
