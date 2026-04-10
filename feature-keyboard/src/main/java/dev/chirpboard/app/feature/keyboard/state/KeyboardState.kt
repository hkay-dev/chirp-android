package dev.chirpboard.app.feature.keyboard.state

import dev.chirpboard.app.core.recording.RecordingState

/**
 * Represents the current state of the keyboard IME.
 * 
 * This is a simplified view for keyboard UI purposes. Recording-related states
 * can be derived from [RecordingState] using [RecordingState.toKeyboardState].
 * 
 * Keyboard-specific states (Transcribing, Polishing, Downloading, ModelNotReady, LlmError)
 * are managed directly by the keyboard service.
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

/**
 * Maps RecordingState to KeyboardState.
 * KeyboardState is a simplified view for keyboard UI purposes.
 * 
 * Note: This mapping only covers recording-related states. Keyboard-specific states
 * like Transcribing, Polishing, Downloading, ModelNotReady, and LlmError are
 * managed directly by the keyboard service and not derived from RecordingState.
 */
fun RecordingState.toKeyboardState(): KeyboardState? {
    if (this.activeOrigin != null && this.activeOrigin != dev.chirpboard.app.core.recording.RecordingOrigin.KEYBOARD) {
        // If another origin is recording, don't show the keyboard as recording.
        // If we map to Idle, the keyboard just stays idle.
        return KeyboardState.Idle
    }

    return when (this) {
        is RecordingState.Idle -> KeyboardState.Idle
        is RecordingState.Starting -> KeyboardState.Recording // Show as recording during startup
        is RecordingState.Recording -> KeyboardState.Recording
        is RecordingState.Paused -> KeyboardState.Recording // Simplified: paused = still recording
        is RecordingState.Stopping -> KeyboardState.Transcribing // Stopping means transcription is happening
        is RecordingState.Error -> KeyboardState.Error(this.message)
    }
}
