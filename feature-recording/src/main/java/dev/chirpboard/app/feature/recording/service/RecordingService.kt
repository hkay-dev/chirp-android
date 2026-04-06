package dev.chirpboard.app.feature.recording.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingStartResult
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityOutcome
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.recording.R
import dev.chirpboard.app.feature.transcription.TranscriptionQueueManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * Foreground service for audio recording.
 *
 * Coordinates with RecordingStateManager to prevent simultaneous recordings
 * from App, Keyboard, or Widget.
 */
@AndroidEntryPoint
class RecordingService : Service() {
    @Inject
    lateinit var recordingStateManager: RecordingStateManager

    @Inject
    lateinit var recordingRepository: RecordingRepository

    @Inject
    lateinit var transcriptionQueueManager: TranscriptionQueueManager

    @Inject
    lateinit var stopOrchestrator: RecordingStopOrchestrator

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null
    private var currentProfileId: UUID? = null
    private var recordingStartTime: Long = 0

    /** Accumulated recording time from previous segments (before current pause/resume) */
    private var accumulatedDurationMs: Long = 0
    private var durationUpdateJob: Job? = null
    private var amplitudeJob: Job? = null
    private val stopRequestGate = StopRequestGate()
    private var currentCorrelationId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeInstanceForTest = this
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> {
                val originName = intent.getStringExtra(EXTRA_ORIGIN) ?: RecordingOrigin.APP.name
                val origin = RecordingOrigin.valueOf(originName)
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)?.let { UUID.fromString(it) }
                startRecording(origin, profileId)
            }

            ACTION_PAUSE_RECORDING -> {
                pauseRecording()
            }

            ACTION_RESUME_RECORDING -> {
                resumeRecording()
            }

            ACTION_STOP_RECORDING -> {
                stopRecording()
            }

            ACTION_CANCEL_RECORDING -> {
                cancelRecording()
            }

            ACTION_RESTART_RECORDING -> {
                val originName = intent?.getStringExtra(EXTRA_ORIGIN) ?: RecordingOrigin.APP.name
                val origin = RecordingOrigin.valueOf(originName)
                val profileId = intent?.getStringExtra(EXTRA_PROFILE_ID)?.let { UUID.fromString(it) }
                restartRecording(origin, profileId)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        val state = recordingStateManager.state.value
        if (!stopRequestGate.isInProgress() &&
            (state is RecordingState.Recording || state is RecordingState.Paused || state is RecordingState.Starting)
        ) {
            stopRecording()
        }

        durationUpdateJob?.cancel()
        amplitudeJob?.cancel()
        mediaRecorder?.release()
        mediaRecorder = null
        serviceScope.cancel()
        if (activeInstanceForTest === this) {
            activeInstanceForTest = null
        }
    }

    private fun startRecording(
        origin: RecordingOrigin,
        profileId: UUID?,
    ) {
        if (androidx.core.content.ContextCompat
                .checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            recordingStateManager.onRecordingError(
                "Permission denied: Microphone access is required.",
                SecurityException("RECORD_AUDIO permission missing"),
            )
            stopSelf()
            return
        }

        // Try to acquire recording lock
        when (val result = recordingStateManager.tryStartRecording(origin, profileId)) {
            is RecordingStartResult.AlreadyRecording -> {
                // Another recording is in progress
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.RECORDING_START,
                    outcome = ReliabilityOutcome.SKIPPED,
                    correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                    reasonCode = "already_recording",
                )
                return
            }

            is RecordingStartResult.Success -> {
                // Lock acquired, proceed with recording
            }
        }

        currentCorrelationId = ReliabilityEventLogger.newCorrelationId("record")
        ReliabilityEventLogger.log(
            stage = ReliabilityStage.RECORDING_START,
            outcome = ReliabilityOutcome.STARTED,
            correlationId = currentCorrelationId!!,
            reasonCode = "service_start",
        )

        currentProfileId = profileId

        try {
            // Create output file
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outputDir = File(filesDir, "recordings").apply { mkdirs() }
            currentRecordingFile = File(outputDir, "recording_$timestamp.m4a")

            // Initialize MediaRecorder
            val recorder =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(this)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
            mediaRecorder = recorder
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(currentRecordingFile!!.absolutePath)
                prepare()
                start()
            }
            recordingStartTime = System.currentTimeMillis()
            accumulatedDurationMs = 0
            recordingStateManager.onRecordingStarted(currentRecordingFile!!.absolutePath)

            ReliabilityEventLogger.log(
                stage = ReliabilityStage.RECORDING_START,
                outcome = ReliabilityOutcome.SUCCESS,
                correlationId = currentCorrelationId!!,
                reasonCode = "recorder_started",
            )

            // Start foreground with notification
            ServiceCompat.startForeground(this, NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

            // Start duration update job for notification
            startDurationUpdates()

            // Start amplitude collection for waveform visualization
            startAmplitudeCollection()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            try {
                mediaRecorder?.release()
            } catch (_: Exception) {
            }
            mediaRecorder = null
            ReliabilityEventLogger.log(
                stage = ReliabilityStage.RECORDING_START,
                outcome = ReliabilityOutcome.FAILURE,
                correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                reasonCode = "recorder_start_failed",
                message = e.message,
            )
            recordingStateManager.onRecordingError("Failed to start recording: ${e.message}", e)
            // Delete the audio file if it was created during setup
            currentRecordingFile?.let { file ->
                if (file.exists()) file.delete()
            }
            currentRecordingFile = null
            stopSelf()
        }
    }

    private fun pauseRecording() {
        try {
            mediaRecorder?.pause()
            // Accumulate time from this segment
            accumulatedDurationMs += System.currentTimeMillis() - recordingStartTime
            recordingStateManager.pauseRecording()
            // Stop amplitude collection while paused
            amplitudeJob?.cancel()
            amplitudeJob = null
            recordingStateManager.updateAmplitude(0f)
            updateNotification()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            recordingStateManager.onRecordingError("Failed to pause recording: ${e.message}", e)
        }
    }

    private fun resumeRecording() {
        try {
            mediaRecorder?.resume()
            recordingStartTime = System.currentTimeMillis()
            recordingStateManager.resumeRecording()
            // Restart amplitude collection
            startAmplitudeCollection()
            updateNotification()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            recordingStateManager.onRecordingError("Failed to resume recording: ${e.message}", e)
        }
    }

    /**
     * Cancel the current recording — release MediaRecorder, delete the audio file,
     * do NOT save to database.
     */
    private fun cancelRecording() {
        val isPaused = recordingStateManager.state.value is RecordingState.Paused

        recordingStateManager.forceCancel()
        durationUpdateJob?.cancel()
        amplitudeJob?.cancel()

        try {
            mediaRecorder?.apply {
                if (isPaused) resume() // Must resume before stop per MediaRecorder API
                stop()
                release()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // MediaRecorder may throw if in an inconsistent state; just release
            try {
                mediaRecorder?.release()
            } catch (_: Exception) {
            }
        }
        mediaRecorder = null

        // Delete the audio file — no database entry was created
        currentRecordingFile?.let { file ->
            if (file.exists()) file.delete()
        }
        currentRecordingFile = null
        accumulatedDurationMs = 0

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Atomic restart: cancel current recording and immediately start a new one
     * without stopping the service in between.
     */
    private fun restartRecording(
        origin: RecordingOrigin,
        profileId: UUID?,
    ) {
        val isPaused = recordingStateManager.state.value is RecordingState.Paused

        // Clean up current recording without stopping the service
        durationUpdateJob?.cancel()
        amplitudeJob?.cancel()

        try {
            mediaRecorder?.apply {
                if (isPaused) resume()
                stop()
                release()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            try {
                mediaRecorder?.release()
            } catch (_: Exception) {
            }
        }
        mediaRecorder = null

        // Delete the old audio file
        currentRecordingFile?.let { file ->
            if (file.exists()) file.delete()
        }
        currentRecordingFile = null
        accumulatedDurationMs = 0

        // Reset state manager lock so startRecording can acquire it
        recordingStateManager.forceCancel()
        stopRequestGate.reset()

        // Start fresh recording in the same service instance
        startRecording(origin, profileId)
    }

    private fun stopRecording() {
        if (!stopRequestGate.tryBegin()) {
            Log.d(TAG, "Ignoring duplicate stop request while stop is in progress")
            return
        }

        val snapshot = captureStopSnapshot()

        if (snapshot != null) {
            ReliabilityEventLogger.log(
                stage = ReliabilityStage.RECORDING_STOP,
                outcome = ReliabilityOutcome.STARTED,
                correlationId = snapshot.correlationId,
                reasonCode = "stop_requested",
            )
        }

        val timeoutJob = recordingStateManager.beginStopRecording()
        durationUpdateJob?.cancel()
        amplitudeJob?.cancel()

        try {
            releaseRecorderForStop(snapshot?.wasPaused == true)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to release recorder", e)
        }
        mediaRecorder = null

        serviceScope.launch {
            val result =
                try {
                    if (snapshot == null) {
                        StopPersistenceResult.NoAudioFile
                    } else {
                        withContext(Dispatchers.IO) {
                            stopOrchestrator.persistAndQueueRecording(snapshot)
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    StopPersistenceResult.PersistenceFailed("Failed to stop recording: ${e.message}", e)
                } finally {
                    timeoutJob?.cancel()
                }

            when (result) {
                is StopPersistenceResult.SavedAndQueued -> {
                    ReliabilityEventLogger.log(
                        stage = ReliabilityStage.RECORDING_STOP,
                        outcome = ReliabilityOutcome.SUCCESS,
                        correlationId = snapshot?.correlationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                        recordingId = result.recordingId,
                        reasonCode = "saved_and_enqueued",
                    )
                    recordingStateManager.onRecordingCompleted(recordingId = result.recordingId)
                }

                is StopPersistenceResult.SavedPendingRecovery -> {
                    Log.w(
                        TAG,
                        "Saved recording ${result.recordingId} but queue handoff failed. " +
                            "Marked for startup recovery.",
                    )
                    ReliabilityEventLogger.log(
                        stage = ReliabilityStage.QUEUE_ENQUEUE,
                        outcome = ReliabilityOutcome.FAILURE,
                        correlationId = snapshot?.correlationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                        recordingId = result.recordingId,
                        reasonCode = "queue_handoff_failed",
                        message = result.message,
                    )
                    recordingStateManager.onRecordingCompleted(recordingId = result.recordingId)
                }

                is StopPersistenceResult.PersistenceFailed -> {
                    ReliabilityEventLogger.log(
                        stage = ReliabilityStage.PERSISTENCE_SAVE,
                        outcome = ReliabilityOutcome.FAILURE,
                        correlationId = snapshot?.correlationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                        reasonCode = "persistence_failed",
                        message = result.message,
                    )
                    recordingStateManager.onRecordingError(result.message, result.cause)
                }

                StopPersistenceResult.NoAudioFile -> {
                    ReliabilityEventLogger.log(
                        stage = ReliabilityStage.RECORDING_STOP,
                        outcome = ReliabilityOutcome.SKIPPED,
                        correlationId = snapshot?.correlationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                        reasonCode = "missing_audio_file",
                    )
                    recordingStateManager.onRecordingCompleted()
                }
            }

            finishStopLifecycle()
        }
    }

    private fun captureStopSnapshot(): StopSnapshot? {
        val state = recordingStateManager.state.value
        val isPaused = state is RecordingState.Paused
        val totalDuration =
            if (isPaused) {
                accumulatedDurationMs
            } else {
                accumulatedDurationMs + (System.currentTimeMillis() - recordingStartTime)
            }
        val filePath = currentRecordingFile?.absolutePath

        return StopSnapshot(
            origin = state.activeOrigin ?: RecordingOrigin.APP,
            profileId = currentProfileId,
            audioFilePath = filePath,
            durationMs = totalDuration.coerceAtLeast(0L),
            stoppedAtEpochMs = System.currentTimeMillis(),
            wasPaused = isPaused,
            correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
        )
    }

    private fun releaseRecorderForStop(wasPaused: Boolean) {
        mediaRecorder?.apply {
            if (wasPaused) {
                resume()
            }
            stop()
            release()
        }
    }

    private fun finishStopLifecycle() {
        currentRecordingFile = null
        currentProfileId = null
        accumulatedDurationMs = 0
        currentCorrelationId = null
        stopRequestGate.reset()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startDurationUpdates() {
        durationUpdateJob?.cancel()
        durationUpdateJob =
            serviceScope.launch {
                while (isActive) {
                    val state = recordingStateManager.state.value
                    if (state is RecordingState.Recording) {
                        updateNotification()
                    }
                    // Don't update while paused — timer is frozen and notification already set
                    delay(1000)
                }
            }
    }

    /**
     * Collect audio amplitude for waveform visualization.
     * Updates at ~50ms intervals for smooth 60fps animation.
     */
    private fun startAmplitudeCollection() {
        amplitudeJob =
            serviceScope.launch {
                while (isActive) {
                    try {
                        val maxAmplitude = mediaRecorder?.maxAmplitude ?: 0
                        // Normalize from 0-32767 to 0-1 range
                        val normalized = (maxAmplitude / 32767f).coerceIn(0f, 1f)
                        recordingStateManager.updateAmplitude(normalized)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        // MediaRecorder may throw if not in a valid state
                        recordingStateManager.updateAmplitude(0f)
                    }
                    delay(50) // ~20 updates per second for smooth animation
                }
            }
    }

    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(): Notification {
        val state = recordingStateManager.state.value
        val isPaused = state is RecordingState.Paused
        val duration = recordingStateManager.getCurrentDurationMs()
        val durationText = formatDuration(duration)

        // Tap notification → open the app
        val launchIntent =
            packageManager.getLaunchIntentForPackage(packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        val contentPendingIntent =
            launchIntent?.let {
                PendingIntent.getActivity(
                    this,
                    0,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

        // Done action (always present)
        val doneIntent =
            Intent(this, RecordingService::class.java).apply {
                action = ACTION_STOP_RECORDING
            }
        val donePendingIntent =
            PendingIntent.getService(
                this,
                1,
                doneIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val builder =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_mic)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentPendingIntent)
                .setColorized(true)
                .setColor(android.graphics.Color.parseColor("#D32F2F")) // Material Red 700

        if (isPaused) {
            builder.setContentTitle("Recording paused")
            builder.setContentText(durationText)

            // Resume action
            val resumeIntent =
                Intent(this, RecordingService::class.java).apply {
                    action = ACTION_RESUME_RECORDING
                }
            val resumePendingIntent =
                PendingIntent.getService(
                    this,
                    2,
                    resumeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            builder.addAction(R.drawable.ic_notif_resume, "Resume", resumePendingIntent)
            builder.addAction(R.drawable.ic_notif_done, "Done", donePendingIntent)
        } else {
            builder.setContentTitle("Recording")
            builder.setContentText(durationText)
            // Use chronometer for live timer — setWhen to the effective start time
            builder.setUsesChronometer(true)
            builder.setWhen(System.currentTimeMillis() - duration)

            // Pause action
            val pauseIntent =
                Intent(this, RecordingService::class.java).apply {
                    action = ACTION_PAUSE_RECORDING
                }
            val pausePendingIntent =
                PendingIntent.getService(
                    this,
                    3,
                    pauseIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            builder.addAction(R.drawable.ic_notif_pause, "Pause", pausePendingIntent)
            builder.addAction(R.drawable.ic_notif_done, "Done", donePendingIntent)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Recording",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows when recording is in progress"
                setShowBadge(false)
                // No sound — this is a persistent status notification
                setSound(null, null)
                enableVibration(false)
            }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun formatDuration(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / 1000 / 60) % 60
        val hours = ms / 1000 / 3600

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recording_channel_v2"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_RECORDING = "dev.chirpboard.app.ACTION_START_RECORDING"
        const val ACTION_PAUSE_RECORDING = "dev.chirpboard.app.ACTION_PAUSE_RECORDING"
        const val ACTION_RESUME_RECORDING = "dev.chirpboard.app.ACTION_RESUME_RECORDING"
        const val ACTION_STOP_RECORDING = "dev.chirpboard.app.ACTION_STOP_RECORDING"
        const val ACTION_CANCEL_RECORDING = "dev.chirpboard.app.ACTION_CANCEL_RECORDING"
        const val ACTION_RESTART_RECORDING = "dev.chirpboard.app.ACTION_RESTART_RECORDING"
        const val EXTRA_ORIGIN = "extra_origin"
        const val EXTRA_PROFILE_ID = "extra_profile_id"

        @Volatile
        @VisibleForTesting
        var activeInstanceForTest: RecordingService? = null

        fun startRecording(
            context: Context,
            origin: RecordingOrigin = RecordingOrigin.APP,
            profileId: UUID? = null,
        ) {
            val intent =
                Intent(context, RecordingService::class.java).apply {
                    action = ACTION_START_RECORDING
                    putExtra(EXTRA_ORIGIN, origin.name)
                    profileId?.let { putExtra(EXTRA_PROFILE_ID, it.toString()) }
                }
            context.startForegroundService(intent)
        }

        fun pauseRecording(context: Context) {
            val intent =
                Intent(context, RecordingService::class.java).apply {
                    action = ACTION_PAUSE_RECORDING
                }
            context.startService(intent)
        }

        fun resumeRecording(context: Context) {
            val intent =
                Intent(context, RecordingService::class.java).apply {
                    action = ACTION_RESUME_RECORDING
                }
            context.startService(intent)
        }

        fun stopRecording(context: Context) {
            val intent =
                Intent(context, RecordingService::class.java).apply {
                    action = ACTION_STOP_RECORDING
                }
            context.startService(intent)
        }

        fun cancelRecording(context: Context) {
            val intent =
                Intent(context, RecordingService::class.java).apply {
                    action = ACTION_CANCEL_RECORDING
                }
            context.startService(intent)
        }

        fun restartRecording(
            context: Context,
            origin: RecordingOrigin = RecordingOrigin.APP,
            profileId: UUID? = null,
        ) {
            val intent =
                Intent(context, RecordingService::class.java).apply {
                    action = ACTION_RESTART_RECORDING
                    putExtra(EXTRA_ORIGIN, origin.name)
                    profileId?.let { putExtra(EXTRA_PROFILE_ID, it.toString()) }
                }
            context.startService(intent)
        }
    }
}
