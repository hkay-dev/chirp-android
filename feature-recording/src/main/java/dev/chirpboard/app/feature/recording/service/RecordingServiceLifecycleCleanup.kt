package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.recording.RecordingState

internal data class RecordingServiceDestroyPlan(
    val scheduleEmergencyStop: Boolean,
)

internal object RecordingServiceLifecycleCleanup {
    fun prepareDestroy(
        state: RecordingState,
        stopInProgress: Boolean,
        serviceOwnsCapture: Boolean,
        cancelPeriodicJobs: () -> Unit,
        detachCallbacks: () -> Unit,
    ): RecordingServiceDestroyPlan {
        cancelPeriodicJobs()
        detachCallbacks()
        return RecordingServiceDestroyPlan(
            scheduleEmergencyStop = shouldScheduleEmergencyStop(state, stopInProgress, serviceOwnsCapture),
        )
    }

    /**
     * Emergency finalize is only for a capture this service instance actually owns. The
     * shared recording state can be active because of an in-process non-service capture
     * (keyboard quick capture, voice recognition); emergency-stopping a cold service in
     * that case would hand off a null recording id and clobber the live capture's state.
     */
    fun shouldScheduleEmergencyStop(
        state: RecordingState,
        stopInProgress: Boolean,
        serviceOwnsCapture: Boolean,
    ): Boolean =
        serviceOwnsCapture &&
            !stopInProgress &&
            (
                state is RecordingState.Starting ||
                    state is RecordingState.Recording ||
                    state is RecordingState.Paused
            )
}
