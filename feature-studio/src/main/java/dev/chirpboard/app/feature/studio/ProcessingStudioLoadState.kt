package dev.chirpboard.app.feature.studio

/**
 * Terminal and in-flight states for Processing Studio route resolution.
 */
enum class ProcessingStudioLoadState {
    /** Valid UUID; observing repository for the recording row. */
    Loading,

    /** Route argument is missing or not a valid UUID. */
    InvalidId,

    /** Row never appeared or was removed after load. */
    NotFound,

    /** Recording row is available; normal studio UI. */
    Ready,
}

internal const val MISSING_RECORDING_GRACE_MS = 1_500L
