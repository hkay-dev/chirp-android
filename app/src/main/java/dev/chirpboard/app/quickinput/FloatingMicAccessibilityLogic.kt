package dev.chirpboard.app.quickinput

/** Properties Chirp checks on the focused accessibility node and its parents. */
internal data class FocusedNodeTraits(
    val editable: Boolean,
    val focused: Boolean,
    val visible: Boolean,
    val password: Boolean,
    val supportsSetText: Boolean = false,
)

internal enum class FocusedEditorState {
    Absent,
    Safe,
    Sensitive,
}

/**
 * Classifies a focused node chain without reading editor text. Password status wins over every
 * other property so a partly exposed secure editor fails closed.
 */
internal fun focusedEditorState(nodes: List<FocusedNodeTraits>): FocusedEditorState {
    if (nodes.any(FocusedNodeTraits::password)) return FocusedEditorState.Sensitive
    val focusedNode = nodes.firstOrNull()
    val hasVisibleInputFocus = focusedNode?.focused == true && focusedNode.visible
    val hasVisibleEditor = nodes.any { it.visible && (it.editable || it.supportsSetText) }
    return if (hasVisibleInputFocus && hasVisibleEditor) {
        FocusedEditorState.Safe
    } else {
        FocusedEditorState.Absent
    }
}

internal fun shouldShowFloatingMic(
    enabled: Boolean,
    imeVisible: Boolean,
    editorWindowFocused: Boolean,
    editorState: FocusedEditorState,
): Boolean = enabled && imeVisible && editorWindowFocused && editorState == FocusedEditorState.Safe

internal fun shouldSuppressFloatingMic(
    launchPending: Boolean,
    captureSessionActive: Boolean,
): Boolean = launchPending || captureSessionActive
