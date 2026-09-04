package dev.chirpboard.app.quickinput

import dev.chirpboard.app.core.preferences.DEFAULT_FLOATING_BUBBLE_Y_FRACTION
import dev.chirpboard.app.core.preferences.FloatingBubblePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingMicAccessibilityLogicTest {
    @Test
    fun defaultBubblePositionAvoidsTrailingEditorControls() {
        val position = FloatingBubblePosition()

        assertFalse(position.onRight)
        assertEquals(0.35f, DEFAULT_FLOATING_BUBBLE_Y_FRACTION)
    }

    @Test
    fun safeEditorNeedsEveryVisibleInputProperty() {
        assertEquals(
            FocusedEditorState.Safe,
            focusedEditorState(
                listOf(
                    FocusedNodeTraits(
                        editable = true,
                        focused = true,
                        visible = true,
                        password = false,
                    ),
                ),
            ),
        )
    }

    @Test
    fun passwordInParentChainFailsClosed() {
        assertEquals(
            FocusedEditorState.Sensitive,
            focusedEditorState(
                listOf(
                    FocusedNodeTraits(true, true, true, false),
                    FocusedNodeTraits(false, false, true, true),
                ),
            ),
        )
    }

    @Test
    fun passwordChangeOverridesAnEarlierSafeClassification() {
        val safe = FocusedNodeTraits(true, true, true, false)
        val password = safe.copy(password = true)

        assertEquals(FocusedEditorState.Safe, focusedEditorState(listOf(safe)))
        assertEquals(FocusedEditorState.Sensitive, focusedEditorState(listOf(password)))
    }

    @Test
    fun composeEditorCanExposeSetTextWithoutEditableFlag() {
        assertEquals(
            FocusedEditorState.Safe,
            focusedEditorState(
                listOf(
                    FocusedNodeTraits(
                        editable = false,
                        focused = true,
                        visible = true,
                        password = false,
                        supportsSetText = true,
                    ),
                ),
            ),
        )
    }

    @Test
    fun focusedChildCanUseEditableParent() {
        assertEquals(
            FocusedEditorState.Safe,
            focusedEditorState(
                listOf(
                    FocusedNodeTraits(false, true, true, false),
                    FocusedNodeTraits(true, false, true, false),
                ),
            ),
        )
    }

    @Test
    fun missingEditorPropertyIsAbsent() {
        listOf(
            FocusedNodeTraits(editable = false, focused = true, visible = true, password = false),
            FocusedNodeTraits(editable = true, focused = false, visible = true, password = false),
            FocusedNodeTraits(editable = true, focused = true, visible = false, password = false),
        ).forEach { traits ->
            assertEquals(FocusedEditorState.Absent, focusedEditorState(listOf(traits)))
        }
    }

    @Test
    fun bubbleNeedsPreferenceImeAndSafeEditor() {
        assertTrue(
            shouldShowFloatingMic(
                enabled = true,
                imeVisible = true,
                editorWindowFocused = true,
                editorState = FocusedEditorState.Safe,
            ),
        )
        assertFalse(shouldShowFloatingMic(false, true, true, FocusedEditorState.Safe))
        assertFalse(shouldShowFloatingMic(true, false, true, FocusedEditorState.Safe))
        assertFalse(shouldShowFloatingMic(true, true, false, FocusedEditorState.Safe))
        assertFalse(shouldShowFloatingMic(true, true, true, FocusedEditorState.Sensitive))
        assertFalse(shouldShowFloatingMic(true, true, true, FocusedEditorState.Absent))
    }

    @Test
    fun activeCaptureSessionSuppressesNestedBubble() {
        assertTrue(shouldSuppressFloatingMic(launchPending = false, captureSessionActive = true))
        assertTrue(shouldSuppressFloatingMic(launchPending = true, captureSessionActive = false))
        assertFalse(shouldSuppressFloatingMic(launchPending = false, captureSessionActive = false))
    }
}
