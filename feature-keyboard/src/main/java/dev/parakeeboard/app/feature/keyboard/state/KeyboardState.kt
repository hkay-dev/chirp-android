package dev.parakeeboard.app.feature.keyboard.state

/**
 * Represents the current state of the keyboard IME.
 */
sealed interface KeyboardState {
    data object Idle : KeyboardState
    data object Recording : KeyboardState
    data object Transcribing : KeyboardState
    data object Polishing : KeyboardState
    data class Downloading(val progress: Float) : KeyboardState
    data object ModelNotReady : KeyboardState
    data class Error(val message: String) : KeyboardState
    /** Shown briefly when LLM fails - displays error, inserts raw text anyway */
    data class LlmError(val message: String) : KeyboardState
}
