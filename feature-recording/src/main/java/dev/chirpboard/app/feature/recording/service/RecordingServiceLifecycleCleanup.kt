package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.recording.RecordingState

internal data class RecordingServiceDestroyPlan(
    val scheduleEmergencyStop: Boolean,
)

internal object RecordingServiceLifecycleCleanup {
    fun prepareDestroy(
        state: RecordingState,
        stopInProgress: Boolean,
        cancelPeriodicJobs: () -> Unit,
        detachCallbacks: () -> Unit,
    ): RecordingServiceDestroyPlan {
        cancelPeriodicJobs()
        detachCallbacks()
        return RecordingServiceDestroyPlan(
            scheduleEmergencyStop = shouldScheduleEmergencyStop(state, stopInProgress),
        )
    }

    fun shouldScheduleEmergencyStop(
        state: RecordingState,
        stopInProgress: Boolean,
    ): Boolean =
        !stopInProgress &&
            (
                state is RecordingState.Starting ||
                    state is RecordingState.Recording ||
                    state is RecordingState.Paused
            )
}
