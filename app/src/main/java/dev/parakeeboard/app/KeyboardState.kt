package dev.parakeeboard.app

sealed interface KeyboardState {
    data object Idle : KeyboardState
    data object Recording : KeyboardState
    data object Transcribing : KeyboardState
    data class Downloading(val progress: Float) : KeyboardState
    data object ModelNotReady : KeyboardState
    data class Error(val message: String) : KeyboardState
}
