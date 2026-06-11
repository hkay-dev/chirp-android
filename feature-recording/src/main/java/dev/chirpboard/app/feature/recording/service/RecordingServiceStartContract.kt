package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.recording.RecordingStartResult

/**
 * Branch logic for start commands delivered through startForegroundService.
 *
 * Contract: every path promotes the service to the foreground BEFORE any other side
 * effect, including shutdown. Returning from onStartCommand without startForeground after
 * a startForegroundService dispatch kills the whole process — which the recording service
 * shares with the keyboard IME.
 *
 * The shared recording lock is also claimed by in-process captures that never touch the
 * service (keyboard quick capture, voice recognition), so AlreadyRecording does not imply
 * this service instance owns the active capture. A cold instance must promote and then
 * shut down cleanly instead of lingering as a zombie foreground service whose notification
 * actions could clobber the live non-service capture's state.
 */
internal object RecordingServiceStartContract {
    internal enum class StartOutcome {
        PERMISSION_DENIED,
        ALREADY_RECORDING_KEEP_RUNNING,
        ALREADY_RECORDING_SHUTDOWN,
        BEGIN_START,
    }

    fun runStartCommand(
        hasRecordPermission: Boolean,
        tryAcquireRecordingLock: () -> RecordingStartResult,
        serviceOwnsCapture: () -> Boolean,
        promoteToForegroundStarting: () -> Unit,
        promoteToForegroundActive: () -> Unit,
        onPermissionDenied: () -> Unit,
        onAlreadyRecording: (ownedByThisService: Boolean) -> Unit,
        beginStart: () -> Unit,
        shutDownService: () -> Unit,
    ): StartOutcome {
        if (!hasRecordPermission) {
            promoteToForegroundStarting()
            onPermissionDenied()
            shutDownService()
            return StartOutcome.PERMISSION_DENIED
        }
        return when (tryAcquireRecordingLock()) {
            is RecordingStartResult.AlreadyRecording -> {
                // Still meet the startForegroundService contract for this command without
                // stopping a service that owns the active capture.
                promoteToForegroundActive()
                val ownedByThisService = serviceOwnsCapture()
                onAlreadyRecording(ownedByThisService)
                if (ownedByThisService) {
                    StartOutcome.ALREADY_RECORDING_KEEP_RUNNING
                } else {
                    shutDownService()
                    StartOutcome.ALREADY_RECORDING_SHUTDOWN
                }
            }
            is RecordingStartResult.Success -> {
                promoteToForegroundStarting()
                beginStart()
                StartOutcome.BEGIN_START
            }
        }
    }

    /**
     * Invoked for null intents (START_STICKY system restarts) and unknown actions. The
     * service may have been launched via startForegroundService, so promotion must always
     * run before deciding whether to keep running or shut down cleanly.
     */
    fun runCommandlessStart(
        keepRunningForActiveCapture: Boolean,
        promoteToForegroundStarting: () -> Unit,
        promoteToForegroundActive: () -> Unit,
        shutDownService: () -> Unit,
    ) {
        if (keepRunningForActiveCapture) {
            promoteToForegroundActive()
        } else {
            promoteToForegroundStarting()
            shutDownService()
        }
    }
}
