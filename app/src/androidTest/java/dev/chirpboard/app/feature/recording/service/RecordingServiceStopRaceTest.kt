package dev.chirpboard.app.feature.recording.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingStartResult
import dev.chirpboard.app.data.model.RecordingSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RecordingServiceStopRaceTest {

    private val appContext: Context by lazy {
        ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        RecordingService.clearTestHooks()
        stopServiceForTest()
    }

    @Test
    fun teardownDuringStopStillPersistsRecordingMetadata() {
        val service = startServiceForTest()
        try {
            runBlocking { service.recordingRepository.deleteAll() }

            val audioFile = createAudioFile(service, "teardown")
            prepareServiceForStop(service, audioFile)

            val persistEntered = CountDownLatch(1)
            val allowPersistToFinish = CountDownLatch(1)
            RecordingService.afterPersistenceBeforeQueueHookForTest = {
                persistEntered.countDown()
                assertTrue(
                    "Timed out waiting for teardown signal",
                    allowPersistToFinish.await(5, TimeUnit.SECONDS)
                )
            }

            val stopThread = Thread {
                service.onStartCommand(stopIntent(service), 0, 1)
            }
            stopThread.start()

            assertTrue(
                "Stop flow did not reach persistence stage",
                persistEntered.await(5, TimeUnit.SECONDS)
            )

            appContext.stopService(recordingServiceIntent())
            allowPersistToFinish.countDown()

            stopThread.join(5_000)
            assertFalse("Stop flow thread should finish", stopThread.isAlive)

            val recordings = runBlocking { service.recordingRepository.getAllRecordings().first() }
            val savedRecording = recordings.singleOrNull { it.audioPath == audioFile.absolutePath }
            assertNotNull("Recording metadata should be persisted after teardown", savedRecording)

            val recording = savedRecording!!
            assertEquals(RecordingSource.APP, recording.source)
            assertTrue(
                "Expected persisted duration to be at least one second",
                recording.durationMs >= 1_000L
            )

            assertTrue(
                "Service should tear down after stop completes",
                waitForCondition(timeoutMs = 5_000) {
                    RecordingService.activeInstanceForTest == null
                }
            )
        } finally {
            stopServiceForTest()
        }
    }

    @Test
    fun enqueueFailureAfterSaveLeavesRecoverableStateAndReleasedLock() {
        val service = startServiceForTest()
        try {
            runBlocking { service.recordingRepository.deleteAll() }

            val audioFile = createAudioFile(service, "enqueue_failure")
            prepareServiceForStop(service, audioFile)

            RecordingService.enqueueOverrideForTest = { _, _ ->
                throw IllegalStateException("forced enqueue failure")
            }

            service.onStartCommand(stopIntent(service), 0, 2)

            val recordings = runBlocking { service.recordingRepository.getAllRecordings().first() }
            val savedRecording = recordings.singleOrNull { it.audioPath == audioFile.absolutePath }
            assertNotNull("Recording should still be saved when enqueue fails", savedRecording)

            val recording = savedRecording!!
            assertEquals(
                dev.chirpboard.app.data.model.RecordingStatus.PENDING_TRANSCRIPTION,
                recording.status
            )
            assertTrue(
                "Recording should carry recoverable queue handoff marker",
                recording.errorMessage?.startsWith("recoverable_queue_handoff:") == true
            )

            val restartResult = service.recordingStateManager.tryStartRecording(RecordingOrigin.APP, null)
            assertTrue(
                "Stop flow should release recording lock after enqueue failure",
                restartResult is RecordingStartResult.Success
            )
            service.recordingStateManager.forceCancel()
        } finally {
            stopServiceForTest()
        }
    }

    private fun startServiceForTest(): RecordingService {
        appContext.startService(recordingServiceIntent())
        assertTrue(
            "Timed out waiting for service startup",
            waitForCondition(timeoutMs = 5_000) {
                RecordingService.activeInstanceForTest != null
            }
        )
        return RecordingService.activeInstanceForTest!!
    }

    private fun stopServiceForTest() {
        appContext.stopService(recordingServiceIntent())
        waitForCondition(timeoutMs = 5_000) { RecordingService.activeInstanceForTest == null }
    }

    private fun prepareServiceForStop(service: RecordingService, audioFile: File) {
        service.recordingStateManager.forceCancel()
        val startResult = service.recordingStateManager.tryStartRecording(RecordingOrigin.APP, null)
        check(startResult is RecordingStartResult.Success) {
            "Expected recording state manager to acquire lock"
        }
        service.recordingStateManager.onRecordingStarted(audioFile.absolutePath)

        setPrivateField(service, "currentRecordingFile", audioFile)
        setPrivateField(service, "currentProfileId", null)
        setPrivateField(service, "recordingStartTime", System.currentTimeMillis() - 1_500L)
        setPrivateField(service, "accumulatedDurationMs", 1_000L)
        setPrivateField(service, "currentCorrelationId", "test-correlation-${System.nanoTime()}")
    }

    private fun createAudioFile(service: RecordingService, label: String): File {
        val recordingsDir = File(service.filesDir, "recordings").apply { mkdirs() }
        return File(recordingsDir, "${label}_${System.nanoTime()}.m4a").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
    }

    private fun stopIntent(service: RecordingService): Intent {
        return Intent(service, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP_RECORDING
        }
    }

    private fun recordingServiceIntent(): Intent {
        return Intent(appContext, RecordingService::class.java)
    }

    private fun waitForCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }

    private fun setPrivateField(service: RecordingService, fieldName: String, value: Any?) {
        val field = RecordingService::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        when (value) {
            is Long -> field.setLong(service, value)
            else -> field.set(service, value)
        }
    }
}
