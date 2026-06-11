package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingStartResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the onStartCommand foreground contract: every start-command branch promotes the
 * service to the foreground before any other side effect (error reporting, shutdown,
 * beginning capture), because the command may have arrived via startForegroundService.
 */
class RecordingServiceStartContractTest {

    @Test
    fun `permission denied promotes before reporting and shutting down`() {
        val events = mutableListOf<String>()

        val outcome =
            runStartCommand(
                events = events,
                hasRecordPermission = false,
                lockResult = RecordingStartResult.Success,
                ownsCapture = false,
            )

        assertEquals(RecordingServiceStartContract.StartOutcome.PERMISSION_DENIED, outcome)
        assertEquals(listOf("promote-starting", "permission-denied", "shutdown"), events)
    }

    @Test
    fun `already recording owned by this service promotes and keeps running`() {
        val events = mutableListOf<String>()

        val outcome =
            runStartCommand(
                events = events,
                hasRecordPermission = true,
                lockResult = RecordingStartResult.AlreadyRecording(RecordingOrigin.APP),
                ownsCapture = true,
            )

        assertEquals(RecordingServiceStartContract.StartOutcome.ALREADY_RECORDING_KEEP_RUNNING, outcome)
        assertEquals(listOf("promote-active", "already-recording:owned"), events)
    }

    @Test
    fun `already recording not owned promotes then shuts down instead of lingering`() {
        val events = mutableListOf<String>()

        val outcome =
            runStartCommand(
                events = events,
                hasRecordPermission = true,
                lockResult = RecordingStartResult.AlreadyRecording(RecordingOrigin.KEYBOARD),
                ownsCapture = false,
            )

        assertEquals(RecordingServiceStartContract.StartOutcome.ALREADY_RECORDING_SHUTDOWN, outcome)
        assertEquals(listOf("promote-active", "already-recording:unowned", "shutdown"), events)
    }

    @Test
    fun `lock acquired promotes before beginning the start`() {
        val events = mutableListOf<String>()

        val outcome =
            runStartCommand(
                events = events,
                hasRecordPermission = true,
                lockResult = RecordingStartResult.Success,
                ownsCapture = false,
            )

        assertEquals(RecordingServiceStartContract.StartOutcome.BEGIN_START, outcome)
        assertEquals(listOf("acquire-lock", "promote-starting", "begin-start"), events)
    }

    @Test
    fun `commandless start with active owned capture promotes and keeps running`() {
        val events = mutableListOf<String>()

        RecordingServiceStartContract.runCommandlessStart(
            keepRunningForActiveCapture = true,
            promoteToForegroundStarting = { events += "promote-starting" },
            promoteToForegroundActive = { events += "promote-active" },
            shutDownService = { events += "shutdown" },
        )

        assertEquals(listOf("promote-active"), events)
    }

    @Test
    fun `cold commandless start promotes before shutting down`() {
        val events = mutableListOf<String>()

        RecordingServiceStartContract.runCommandlessStart(
            keepRunningForActiveCapture = false,
            promoteToForegroundStarting = { events += "promote-starting" },
            promoteToForegroundActive = { events += "promote-active" },
            shutDownService = { events += "shutdown" },
        )

        assertEquals(listOf("promote-starting", "shutdown"), events)
    }

    private fun runStartCommand(
        events: MutableList<String>,
        hasRecordPermission: Boolean,
        lockResult: RecordingStartResult,
        ownsCapture: Boolean,
    ): RecordingServiceStartContract.StartOutcome =
        RecordingServiceStartContract.runStartCommand(
            hasRecordPermission = hasRecordPermission,
            tryAcquireRecordingLock = {
                if (lockResult is RecordingStartResult.Success) {
                    events += "acquire-lock"
                }
                lockResult
            },
            serviceOwnsCapture = { ownsCapture },
            promoteToForegroundStarting = { events += "promote-starting" },
            promoteToForegroundActive = { events += "promote-active" },
            onPermissionDenied = { events += "permission-denied" },
            onAlreadyRecording = { owned ->
                events += if (owned) "already-recording:owned" else "already-recording:unowned"
            },
            beginStart = { events += "begin-start" },
            shutDownService = { events += "shutdown" },
        )
}
