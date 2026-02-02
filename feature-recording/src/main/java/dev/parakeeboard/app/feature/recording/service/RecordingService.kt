package dev.parakeeboard.app.feature.recording.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.parakeeboard.app.core.recording.RecordingOrigin
import dev.parakeeboard.app.core.recording.RecordingStartResult
import dev.parakeeboard.app.core.recording.RecordingStateManager
import dev.parakeeboard.app.data.model.RecordingSource
import dev.parakeeboard.app.data.model.RecordingStatus
import dev.parakeeboard.app.data.repository.RecordingRepository
import dev.parakeeboard.app.feature.transcription.TranscriptionQueueManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null
    private var currentRecordingId: UUID? = null
    private var currentProfileId: UUID? = null
    private var recordingStartTime: Long = 0
    private var durationUpdateJob: Job? = null
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> {
                val originName = intent.getStringExtra(EXTRA_ORIGIN) ?: RecordingOrigin.APP.name
                val origin = RecordingOrigin.valueOf(originName)
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)?.let { UUID.fromString(it) }
                startRecording(origin, profileId)
            }
            ACTION_STOP_RECORDING -> {
                stopRecording()
            }
        }
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        serviceScope.cancel()
    }
    
    private fun startRecording(origin: RecordingOrigin, profileId: UUID?) {
        // Try to acquire recording lock
        when (val result = recordingStateManager.tryStartRecording(origin, profileId)) {
            is RecordingStartResult.AlreadyRecording -> {
                // Another recording is in progress
                stopSelf()
                return
            }
            RecordingStartResult.Success -> {
                // Lock acquired, proceed with recording
            }
        }
        
        currentProfileId = profileId
        
        try {
            // Create output file
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outputDir = File(filesDir, "recordings").apply { mkdirs() }
            currentRecordingFile = File(outputDir, "recording_$timestamp.m4a")
            
            // Initialize MediaRecorder
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
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
            recordingStateManager.onRecordingStarted(currentRecordingFile!!.absolutePath)
            
            // Start foreground with notification
            startForeground(NOTIFICATION_ID, createNotification())
            
            // Start duration update job for notification
            startDurationUpdates()
            
        } catch (e: Exception) {
            recordingStateManager.onRecordingError("Failed to start recording: ${e.message}", e)
            stopSelf()
        }
    }
    
    private fun stopRecording() {
        recordingStateManager.beginStopRecording()
        durationUpdateJob?.cancel()
        
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            
            val file = currentRecordingFile
            val duration = System.currentTimeMillis() - recordingStartTime
            
            if (file != null && file.exists()) {
                // Create recording in database
                serviceScope.launch(Dispatchers.IO) {
                    val source = when (recordingStateManager.state.value.activeOrigin) {
                        RecordingOrigin.APP -> RecordingSource.APP
                        RecordingOrigin.KEYBOARD -> RecordingSource.KEYBOARD
                        RecordingOrigin.WIDGET -> RecordingSource.WIDGET
                        null -> RecordingSource.APP
                    }
                    
                    val title = SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date())
                    
                    val recording = recordingRepository.createRecording(
                        title = title,
                        audioPath = file.absolutePath,
                        source = source,
                        profileId = currentProfileId,
                        durationMs = duration
                    )
                    
                    // Enqueue for transcription
                    transcriptionQueueManager.enqueue(recording.id)
                    
                    recordingStateManager.onRecordingCompleted()
                }
            } else {
                recordingStateManager.onRecordingCompleted()
            }
            
        } catch (e: Exception) {
            recordingStateManager.onRecordingError("Failed to stop recording: ${e.message}", e)
        }
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    private fun startDurationUpdates() {
        durationUpdateJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                updateNotification()
            }
        }
    }
    
    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun createNotification(): Notification {
        val duration = System.currentTimeMillis() - recordingStartTime
        val durationText = formatDuration(duration)
        
        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP_RECORDING
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording in progress")
            .setContentText(durationText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop",
                stopPendingIntent
            )
            .build()
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when recording is in progress"
            setShowBadge(false)
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
        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 1001
        
        const val ACTION_START_RECORDING = "dev.parakeeboard.app.ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "dev.parakeeboard.app.ACTION_STOP_RECORDING"
        const val EXTRA_ORIGIN = "extra_origin"
        const val EXTRA_PROFILE_ID = "extra_profile_id"
        
        fun startRecording(
            context: Context,
            origin: RecordingOrigin = RecordingOrigin.APP,
            profileId: UUID? = null
        ) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_START_RECORDING
                putExtra(EXTRA_ORIGIN, origin.name)
                profileId?.let { putExtra(EXTRA_PROFILE_ID, it.toString()) }
            }
            context.startForegroundService(intent)
        }
        
        fun stopRecording(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_STOP_RECORDING
            }
            context.startService(intent)
        }
    }
}
