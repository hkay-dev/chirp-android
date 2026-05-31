package dev.chirpboard.app.feature.recording.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.core.audio.AudioFocusManager
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.core.audio.RecordingStorageMonitor
import dev.chirpboard.app.core.audio.StorageCheckLevel
import dev.chirpboard.app.core.recording.RecordingPermissionGuard
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.audio.AudioSettingsStore
import dev.chirpboard.app.core.audio.RecordingOutputFormat
import dev.chirpboard.app.core.recording.RecordingStartResult
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityOutcome
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.recording.session.RecordingCapturePaths
import dev.chirpboard.app.feature.recording.session.RecordingCheckpointScheduler
import dev.chirpboard.app.feature.recording.session.RecordingSegmentRotator
import dev.chirpboard.app.feature.recording.session.RecordingSessionHeartbeat
import dev.chirpboard.app.feature.recording.session.RecordingSessionJournal
import dev.chirpboard.app.feature.recording.session.RecordingRecoveryStore
import dev.chirpboard.app.feature.recording.session.RecordingSessionReconciler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
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
    lateinit var transcriberProvider: dev.chirpboard.app.core.transcription.TranscriberProvider

    @Inject
    lateinit var audioSettingsStore: AudioSettingsStore

    @Inject
    lateinit var sessionJournal: RecordingSessionJournal

    @Inject
    lateinit var sessionReconciler: RecordingSessionReconciler

    @Inject
    lateinit var recoveryStore: RecordingRecoveryStore

    @Inject
    lateinit var capturePaths: RecordingCapturePaths

    @Inject
    lateinit var storageMonitor: RecordingStorageMonitor

    @Inject
    lateinit var inputDeviceSelector: AudioInputDeviceSelector

    @Inject
    lateinit var sessionHeartbeat: RecordingSessionHeartbeat

    @Inject
    lateinit var checkpointScheduler: RecordingCheckpointScheduler

    @Inject
    lateinit var segmentRotator: RecordingSegmentRotator

    @Inject
    lateinit var notificationFactory: RecordingNotificationFactory

    private lateinit var audioFocusManager: AudioFocusManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var segmentCapture: GaplessSegmentCaptureEngine? = null
    private var currentRecordingFile: File? = null
    private var currentProfileId: UUID? = null
    private var currentOrigin: RecordingOrigin = RecordingOrigin.APP
    private var currentSessionId: UUID? = null
    private var currentInProgressRecordingId: UUID? = null
    private var currentFinalAudioPath: File? = null

    private var durationUpdateJob: Job? = null
    private var amplitudeJob: Job? = null
    private var heartbeatJob: Job? = null
    private var storageCheckJob: Job? = null
    private var checkpointJob: Job? = null
    private var segmentRotationJob: Job? = null
    private val segmentTransitionMutex = Mutex()
    private val stopRequestGate = StopRequestGate()
    private var currentCorrelationId: String? = null
    private var stopRecordingJob: Job? = null
    private var startRecordingJob: Job? = null
    private val startGeneration = AtomicInteger(0)
    private val stopGeneration = AtomicInteger(0)
    private val startCancelMutex = Mutex()
    private val emergencyStopScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationFactory.ensureChannel(this)
        audioFocusManager = AudioFocusManager(getSystemService(AudioManager::class.java))
        audioFocusManager.onFocusLost = { lossKind ->
            when (lossKind) {
                AudioFocusManager.FocusLossKind.TRANSIENT -> pauseRecording()
                AudioFocusManager.FocusLossKind.PERMANENT -> stopRecording()
            }
        }
        val serviceRef = WeakReference(this)
        inputDeviceSelector.setOnActiveDeviceLostListener {
            serviceRef.get()?.let { service ->
                service.serviceScope.launch {
                    service.stopRecording()
                }
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (val command = RecordingServiceCommandRouter.commandFor(intent)) {
            is RecordingServiceCommand.Start -> startRecording(command.origin, command.profileId)
            RecordingServiceCommand.Pause -> pauseRecording()
            RecordingServiceCommand.Resume -> resumeRecording()
            RecordingServiceCommand.Stop -> stopRecording()
            RecordingServiceCommand.Cancel -> cancelRecording()
            is RecordingServiceCommand.Restart -> restartRecording(command.origin, command.profileId)
            null -> Unit
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        val state = recordingStateManager.state.value
        val destroyPlan =
            RecordingServiceLifecycleCleanup.prepareDestroy(
                state = state,
                stopInProgress = stopRequestGate.isInProgress(),
                cancelPeriodicJobs = {
                    durationUpdateJob?.cancel()
                    amplitudeJob?.cancel()
                    heartbeatJob?.cancel()
                    storageCheckJob?.cancel()
                    checkpointJob?.cancel()
                    segmentRotationJob?.cancel()
                },
                detachCallbacks = {
                    inputDeviceSelector.setOnActiveDeviceLostListener(null)
                    audioFocusManager.onFocusLost = null
                },
            )

        if (destroyPlan.scheduleEmergencyStop) {
            emergencyStopScope.launch {
                emergencyFinalizeActiveCapture()
            }
            serviceScope.cancel()
        } else if (stopRequestGate.isInProgress() && stopRecordingJob?.isActive == true) {
            stopRecordingJob?.invokeOnCompletion {
                serviceScope.cancel()
                emergencyStopScope.cancel()
            }
        } else {
            emergencyStopScope.cancel()
            serviceScope.cancel()
        }
    }

    private fun startRecording(
        origin: RecordingOrigin,
        profileId: UUID?,
    ) {
        if (!RecordingPermissionGuard.hasRecordAudioPermission(this)) {
            recordingStateManager.onRecordingError(
                RecordingPermissionGuard.PERMISSION_DENIED_MESSAGE,
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

        promoteToForegroundImmediately()

        val generation = startGeneration.incrementAndGet()
        startRecordingJob?.cancel()
        startRecordingJob =
            serviceScope.launch {
                startRecordingAfterLockAcquired(origin, profileId, generation)
            }
    }

    private fun promoteToForegroundImmediately() {
        ServiceCompat.startForeground(
            this,
            RecordingNotificationFactory.NOTIFICATION_ID,
            notificationFactory.createStartingNotification(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    private suspend fun startRecordingAfterLockAcquired(
        origin: RecordingOrigin,
        profileId: UUID?,
        startGenerationToken: Int,
    ) {
        fun ensureStartNotCancelled() {
            if (startGenerationToken != startGeneration.get()) {
                throw kotlinx.coroutines.CancellationException("Recording start cancelled")
            }
        }

        currentCorrelationId = ReliabilityEventLogger.newCorrelationId("record")
        currentOrigin = origin
        currentProfileId = profileId

        val storageCheck = storageMonitor.checkAvailableStorage()
        if (storageCheck.level == StorageCheckLevel.CRITICAL) {
            recordingStateManager.onRecordingError("Not enough storage to start recording")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        when (audioFocusManager.requestFocus()) {
            is AudioFocusManager.FocusResult.Denied -> {
                recordingStateManager.onRecordingError("Could not acquire audio focus for recording")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            is AudioFocusManager.FocusResult.Granted -> Unit
        }

        ReliabilityEventLogger.log(
            stage = ReliabilityStage.RECORDING_START,
            outcome = ReliabilityOutcome.STARTED,
            correlationId = currentCorrelationId!!,
            reasonCode = "service_start",
        )

        try {
            withContext(Dispatchers.Default) {
                transcriberProvider.release()
            }

            ensureStartNotCancelled()

            withContext(Dispatchers.IO) {
                startCancelMutex.withLock {
                    ensureStartNotCancelled()

                    sessionReconciler.reconcileCompletedSessions()

                    val recordingQualityConfig =
                        audioSettingsStore.currentRecordingQualityPreset().appRecordingConfig
                    val outputFormat = audioSettingsStore.currentOutputFormat()

                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val outputDir = File(filesDir, "recordings").apply { mkdirs() }
                    val finalFile = File(outputDir, "recording_$timestamp${outputFormat.fileExtension}")
                    val sessionId = UUID.randomUUID()
                    currentSessionId = sessionId
                    currentFinalAudioPath = finalFile
                    val firstSegment = capturePaths.durableSegmentFile(sessionId, 0)
                    currentRecordingFile = firstSegment

                    ensureStartNotCancelled()

                    val source =
                        when (origin) {
                            RecordingOrigin.APP -> RecordingSource.APP
                            RecordingOrigin.KEYBOARD -> RecordingSource.KEYBOARD
                            RecordingOrigin.WIDGET -> RecordingSource.WIDGET
                        }
                    val provisionalTitle =
                        SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date())
                    val inProgressRecording =
                        recordingRepository.createInProgressRecording(
                            title = provisionalTitle,
                            audioPath = finalFile.absolutePath,
                            source = source,
                            profileId = profileId,
                        )
                    currentInProgressRecordingId = inProgressRecording.id
                    recordingStateManager.onRecordingIdAssigned(inProgressRecording.id)

                    ensureStartNotCancelled()

                    sessionJournal.createSession(
                        sessionId = sessionId,
                        audioPath = firstSegment.absolutePath,
                        origin = origin,
                        profileId = profileId,
                        recordingId = inProgressRecording.id,
                        correlationId = currentCorrelationId,
                        finalAudioPath = finalFile.absolutePath,
                    )

                    startGaplessCapture(
                        segmentFile = firstSegment,
                        format = RecordingOutputFormat.WAV,
                        bitRate = recordingQualityConfig.bitRate,
                        sampleRate = recordingQualityConfig.sampleRate,
                    )
                }
            }
            ensureStartNotCancelled()
            recordingStateManager.onRecordingStarted(
                audioFilePath = currentRecordingFile!!.absolutePath,
                recordingId = currentInProgressRecordingId,
            )

            ReliabilityEventLogger.log(
                stage = ReliabilityStage.RECORDING_START,
                outcome = ReliabilityOutcome.SUCCESS,
                correlationId = currentCorrelationId!!,
                reasonCode = "recorder_started",
            )

            // Upgrade the starting notification to the live recording UI
            ServiceCompat.startForeground(
                this,
                RecordingNotificationFactory.NOTIFICATION_ID,
                notificationFactory.createRecordingNotification(this, recordingStateManager),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )

            startDurationUpdates()
            startAmplitudeCollection()
            startSessionHeartbeat()
            startStorageMonitoring()
            startCheckpointCopies()
            startSegmentRotation()
        } catch (e: kotlinx.coroutines.CancellationException) {
            currentInProgressRecordingId?.let { recordingRepository.deleteInProgressRecording(it) }
            currentInProgressRecordingId = null
            currentSessionId?.let { sessionJournal.markAbandoned(it) }
            currentSessionId?.let { capturePaths.deleteCaptureArtifacts(it) }
            currentSessionId = null
            audioFocusManager.abandonFocus()
            inputDeviceSelector.clearActiveDevice()
            segmentCapture?.releaseWithoutSave()
            segmentCapture = null
            currentRecordingFile?.let { file ->
                if (file.exists()) file.delete()
            }
            currentFinalAudioPath?.let { file ->
                if (file.exists()) file.delete()
            }
            currentRecordingFile = null
            currentFinalAudioPath = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            throw e
        } catch (e: Exception) {
            currentInProgressRecordingId?.let { recordingRepository.deleteInProgressRecording(it) }
            currentInProgressRecordingId = null
            currentSessionId?.let { sessionJournal.markAbandoned(it) }
            currentSessionId?.let { capturePaths.deleteCaptureArtifacts(it) }
            currentSessionId = null
            audioFocusManager.abandonFocus()
            inputDeviceSelector.clearActiveDevice()
            segmentCapture?.releaseWithoutSave()
            segmentCapture = null
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
            currentFinalAudioPath?.let { file ->
                if (file.exists()) file.delete()
            }
            currentRecordingFile = null
            currentFinalAudioPath = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun pauseRecording() {
        serviceScope.launch {
            try {
                segmentTransitionMutex.withLock {
                    if (recordingStateManager.state.value !is RecordingState.Recording) return@withLock
                    segmentRotationJob?.cancel()
                    amplitudeJob?.cancel()
                    amplitudeJob = null
                    recordingStateManager.pauseRecording()
                    recordingStateManager.updateAmplitude(0f)

                    val sessionId = currentSessionId ?: return@withLock
                    val completedFile =
                        withContext(Dispatchers.IO) {
                            segmentCapture?.cancelPendingRotation()
                            segmentCapture?.pauseAndFinalizeSegment()
                        }
                    segmentCapture = null
                    val finalized = completedFile ?: currentRecordingFile ?: return@withLock
                    currentRecordingFile = finalized

                    sessionJournal.commitPausedSegment(
                        sessionId = sessionId,
                        completedSegmentPath = finalized.absolutePath,
                        fileBytes = finalized.length(),
                    )
                    ReliabilityEventLogger.log(
                        stage = ReliabilityStage.RECORDING_STOP,
                        outcome = ReliabilityOutcome.SUCCESS,
                        correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                        reasonCode = "segment_saved_on_pause",
                    )
                }
                notificationFactory.updateRecordingNotification(this@RecordingService, recordingStateManager)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pause recording", e)
                recordingStateManager.onRecordingError("Failed to pause recording: ${e.message}", e)
            }
        }
    }

    private fun resumeRecording() {
        serviceScope.launch {
            try {
                segmentTransitionMutex.withLock {
                    val sessionId = currentSessionId ?: return@withLock
                    val entry = sessionJournal.findBySessionId(sessionId) ?: return@withLock
                    val nextSegment = capturePaths.durableSegmentFile(sessionId, entry.segmentPaths.size)
                    val recordingQualityConfig =
                        audioSettingsStore.currentRecordingQualityPreset().appRecordingConfig

                    withContext(Dispatchers.IO) {
                        segmentCapture =
                            GaplessSegmentCaptureFactory.create(
                                format = RecordingOutputFormat.WAV,
                                inputDeviceSelector = inputDeviceSelector,
                                sampleRate = recordingQualityConfig.sampleRate,
                                bitRate = recordingQualityConfig.bitRate,
                            )
                        segmentCapture!!.start(nextSegment)
                    }

                    currentRecordingFile = nextSegment
                    sessionJournal.beginNextSegment(sessionId, nextSegment.absolutePath)
                    recordingStateManager.resumeRecording(nextSegment.absolutePath)
                    ReliabilityEventLogger.log(
                        stage = ReliabilityStage.RECORDING_START,
                        outcome = ReliabilityOutcome.SUCCESS,
                        correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                        reasonCode = "segment_started_on_resume",
                        message = "segment=${entry.segmentPaths.size}",
                    )
                }
                startAmplitudeCollection()
                startSegmentRotation()
                notificationFactory.updateRecordingNotification(this@RecordingService, recordingStateManager)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                recordingStateManager.onRecordingError("Failed to resume recording: ${e.message}", e)
            }
        }
    }

    /**
     * Cancel the current recording — release MediaRecorder, delete the audio file,
     * do NOT save to database.
     */
    private fun cancelRecording() {
        startGeneration.incrementAndGet()
        stopGeneration.incrementAndGet()
        startRecordingJob?.cancel()
        startRecordingJob = null
        durationUpdateJob?.cancel()
        amplitudeJob?.cancel()
        heartbeatJob?.cancel()
        storageCheckJob?.cancel()
        checkpointJob?.cancel()
        segmentRotationJob?.cancel()

        serviceScope.launch {
            startCancelMutex.withLock {
                val inProgressId = currentInProgressRecordingId
                val abandonedSessionId = currentSessionId
                val fileToDelete = currentRecordingFile
                val finalFileToDelete = currentFinalAudioPath
                abandonedSessionId?.let { sessionId ->
                    if (sessionJournal.findBySessionId(sessionId) != null) {
                        sessionJournal.markAbandoned(sessionId)
                    }
                }
                currentSessionId = null
                currentInProgressRecordingId = null

                try {
                    withContext(Dispatchers.IO) {
                        segmentCapture?.releaseWithoutSave()
                    }
                } catch (_: Exception) {
                } finally {
                    segmentCapture = null
                    withContext(NonCancellable + Dispatchers.IO) {
                        inProgressId?.let { recordingRepository.deleteAbandonedInProgressRecording(it) }
                        abandonedSessionId?.let { capturePaths.deleteCaptureArtifacts(it) }
                        fileToDelete?.takeIf { it.exists() }?.delete()
                        finalFileToDelete?.takeIf { it.exists() }?.delete()
                    }
                    currentRecordingFile = null
                    currentFinalAudioPath = null
                    audioFocusManager.abandonFocus()
                    inputDeviceSelector.clearActiveDevice()
                    recordingStateManager.forceCancel()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }
    /**
     * Atomic restart: cancel current recording and immediately start a new one
     * without stopping the service in between.
     */
    private fun restartRecording(
        origin: RecordingOrigin,
        profileId: UUID?,
    ) {
        serviceScope.launch {
            stopGeneration.incrementAndGet()
            durationUpdateJob?.cancel()
            amplitudeJob?.cancel()
            heartbeatJob?.cancel()
            storageCheckJob?.cancel()
            checkpointJob?.cancel()
            segmentRotationJob?.cancel()

            val oldSessionId = currentSessionId
            val oldRecordingId = currentInProgressRecordingId
            val oldFile = currentRecordingFile
            val oldFinalFile = currentFinalAudioPath

            withContext(Dispatchers.IO) {
                segmentCapture?.releaseWithoutSave()
            }
            segmentCapture = null

            oldSessionId?.let { sessionJournal.markAbandoned(it) }
            oldSessionId?.let { capturePaths.deleteCaptureArtifacts(it) }
            oldRecordingId?.let { recordingRepository.deleteAbandonedInProgressRecording(it) }
            oldFile?.let { file ->
                if (file.exists()) file.delete()
            }
            oldFinalFile?.let { file ->
                if (file.exists()) file.delete()
            }
            currentRecordingFile = null
            currentFinalAudioPath = null
            currentSessionId = null
            currentInProgressRecordingId = null

            recordingStateManager.forceCancel()
            stopRequestGate.reset()
            startRecording(origin, profileId)
        }
    }

    private fun stopRecording() {
        if (!stopRequestGate.tryBegin()) {
            Log.d(TAG, "Ignoring duplicate stop request while stop is in progress")
            return
        }

        val stateAtStop = recordingStateManager.state.value
        val pendingStartJob = if (stateAtStop is RecordingState.Starting) startRecordingJob else null
        if (pendingStartJob != null) {
            startGeneration.incrementAndGet()
            pendingStartJob.cancel()
        }

        durationUpdateJob?.cancel()
        amplitudeJob?.cancel()
        heartbeatJob?.cancel()
        storageCheckJob?.cancel()
        checkpointJob?.cancel()
        segmentRotationJob?.cancel()

        val generation = stopGeneration.incrementAndGet()
        stopRecordingJob?.cancel()
        stopRecordingJob =
            serviceScope.launch {
                var snapshot: StopSnapshot? = null
                var sessionId: UUID? = null
                try {
                    pendingStartJob?.join()
                    if (pendingStartJob != null && startRecordingJob === pendingStartJob) {
                        startRecordingJob = null
                    }
                    sessionId = currentSessionId
                    snapshot = handoffCaptureToFinalizeQueue(sessionId, generation)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "Capture stop handoff failed", e)
                    withContext(Dispatchers.IO + NonCancellable) {
                        if (!RecordingFinalizeRecoveryPolicy.hasRecoverableArtifacts(
                                sessionJournal = sessionJournal,
                                sessionId = sessionId,
                                snapshot = snapshot,
                            )
                        ) {
                            RecordingFinalizeRecoveryPolicy.cleanupUnrecoverable(
                                sessionJournal = sessionJournal,
                                recordingRepository = recordingRepository,
                                sessionId = sessionId,
                                snapshot = snapshot,
                            )
                        }
                    }
                    recordingStateManager.onRecordingError("Failed to stop recording: ${e.message}", e)
                } finally {
                    finishStopLifecycle()
                }
            }
    }

    private suspend fun handoffCaptureToFinalizeQueue(
        sessionId: UUID?,
        generation: Int,
    ): StopSnapshot? =
        RecordingStopHandoff.handoff(
            sessionId = sessionId,
            stopCapture = { stopActiveCaptureForHandoff(generation) },
            captureSnapshot = {
                captureStopSnapshot()?.also { snapshot ->
                    ReliabilityEventLogger.log(
                        stage = ReliabilityStage.RECORDING_STOP,
                        outcome = ReliabilityOutcome.STARTED,
                        correlationId = snapshot.correlationId,
                        reasonCode = "stop_requested",
                    )
                }
            },
            markAbandoned = { abandonedSessionId, recordingId ->
                withContext(Dispatchers.IO + NonCancellable) {
                    abandonedSessionId?.let { sessionJournal.markAbandoned(it) }
                    recordingId?.let { recordingRepository.deleteAbandonedInProgressRecording(it) }
                }
            },
            markStopping = { stoppingSessionId -> sessionJournal.markStopping(stoppingSessionId) },
            enqueueFinalize = { finalizeSnapshot, finalizeSessionId ->
                RecordingFinalizeWorkRequest.enqueue(
                    context = this@RecordingService,
                    snapshot = finalizeSnapshot,
                    sessionId = finalizeSessionId,
                )
            },
            onCaptureStopHandoff = recordingStateManager::onCaptureStopHandoff,
        )

    private suspend fun stopActiveCaptureForHandoff(generation: Int) {
        val result =
            RecordingCaptureStopper.stopForHandoff(
                segmentTransitionMutex = segmentTransitionMutex,
                stopGeneration = stopGeneration,
                generation = generation,
                sessionId = currentSessionId,
                sessionJournal = sessionJournal,
                captureProvider = { segmentCapture },
                activeFileProvider = { currentRecordingFile },
                timeoutMs = CAPTURE_STOP_TIMEOUT_MS,
            )

        when (result) {
            is CaptureStopHandoffResult.Completed -> {
                currentRecordingFile = result.finalizedFile ?: currentRecordingFile
                segmentCapture = null
            }
            is CaptureStopHandoffResult.TimedOut -> {
                currentRecordingFile = result.fallbackFile ?: currentRecordingFile
                segmentCapture = null
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.RECORDING_STOP,
                    outcome = ReliabilityOutcome.FAILURE,
                    correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                    reasonCode = "capture_stop_timeout",
                    message = "Capture stop exceeded ${CAPTURE_STOP_TIMEOUT_MS}ms",
                )
            }
            is CaptureStopHandoffResult.Failed -> {
                currentRecordingFile = result.fallbackFile ?: currentRecordingFile
                segmentCapture = null
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.RECORDING_STOP,
                    outcome = ReliabilityOutcome.FAILURE,
                    correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                    reasonCode = "capture_stop_failed",
                    message = result.cause.message,
                )
            }
            CaptureStopHandoffResult.StaleGeneration -> Unit
        }
    }

    private fun captureStopSnapshot(): StopSnapshot? =
        StopSnapshotCapture.capture(
            recordingStateManager = recordingStateManager,
            currentRecordingFile = currentRecordingFile,
            currentProfileId = currentProfileId,
            currentOrigin = currentOrigin,
            currentInProgressRecordingId = currentInProgressRecordingId,
            currentCorrelationId = currentCorrelationId,
        )

    private suspend fun emergencyFinalizeActiveCapture() {
        if (!stopRequestGate.tryBegin()) {
            stopGeneration.incrementAndGet()
            segmentCapture = null
            return
        }

        val sessionId = currentSessionId
        val generation = stopGeneration.incrementAndGet()

        try {
            handoffCaptureToFinalizeQueue(sessionId, generation)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            recordingStateManager.onRecordingError("Failed to finalize recording during shutdown")
        } finally {
            finishStopLifecycle()
            emergencyStopScope.cancel()
        }
    }

    private fun finishStopLifecycle() {
        currentRecordingFile = null
        currentFinalAudioPath = null
        currentProfileId = null
        currentSessionId = null
        currentInProgressRecordingId = null
        currentCorrelationId = null
        stopRequestGate.reset()
        audioFocusManager.abandonFocus()
        inputDeviceSelector.clearActiveDevice()

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
                        notificationFactory.updateRecordingNotification(this@RecordingService, recordingStateManager)
                    }
                    // Don't update while paused — timer is frozen and notification already set
                    delay(1000)
                }
            }
    }

    /**
     * Collect audio amplitude for waveform visualization.
     * Updates at display-friendly cadence so the UI has enough real data to interpolate smoothly.
     */
    private fun startAmplitudeCollection() {
        amplitudeJob =
            serviceScope.launch {
                while (isActive) {
                    try {
                        val maxAmplitude = segmentCapture?.maxAmplitude ?: 0
                        val normalized = (maxAmplitude / 32767f).coerceIn(0f, 1f)
                        recordingStateManager.updateAmplitude(normalized)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        recordingStateManager.updateAmplitude(0f)
                    }
                    delay(100)
                }
            }
    }

    private fun startSessionHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob =
            sessionHeartbeat.start(
                scope = serviceScope,
                sessionIdProvider = { currentSessionId },
                activeFileProvider = { currentRecordingFile },
            )
    }

    private fun startCheckpointCopies() {
        checkpointJob?.cancel()
        checkpointJob =
            checkpointScheduler.start(
                scope = serviceScope,
                sessionIdProvider = { currentSessionId },
                activeFileProvider = { currentRecordingFile },
            )
    }

    private suspend fun startGaplessCapture(
        segmentFile: File,
        format: RecordingOutputFormat,
        bitRate: Int,
        sampleRate: Int,
    ) {
        segmentCapture?.releaseWithoutSave()
        segmentCapture =
            GaplessSegmentCaptureFactory.create(
                format = format,
                inputDeviceSelector = inputDeviceSelector,
                sampleRate = sampleRate,
                bitRate = bitRate,
            )
        segmentCapture!!.start(segmentFile)
    }

    private fun startSegmentRotation() {
        segmentRotationJob?.cancel()
        segmentRotationJob =
            serviceScope.launch {
                while (isActive) {
                    delay(RecordingSessionJournal.SEGMENT_ROTATION_INTERVAL_MS)
                    if (recordingStateManager.state.value is RecordingState.Paused) continue
                    if (stopRequestGate.isInProgress()) continue
                    try {
                        val outcome =
                            segmentRotator.rotateIfNeeded(
                                recordingStateManager = recordingStateManager,
                                stopRequestGate = stopRequestGate,
                                segmentTransitionMutex = segmentTransitionMutex,
                                sessionId = currentSessionId,
                                segmentCapture = segmentCapture,
                                currentRecordingFile = currentRecordingFile,
                                correlationId = currentCorrelationId,
                            )
                        outcome?.nextSegmentFile?.let { currentRecordingFile = it }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Gapless segment rotation failed", e)
                    }
                }
            }
    }

    private fun startStorageMonitoring() {
        storageCheckJob?.cancel()
        storageCheckJob =
            serviceScope.launch {
                while (isActive) {
                    delay(15_000)
                    when (storageMonitor.checkAvailableStorage().level) {
                        StorageCheckLevel.LOW ->
                            notificationFactory.updateRecordingNotification(this@RecordingService, recordingStateManager)
                        StorageCheckLevel.CRITICAL -> {
                            stopRecording()
                            break
                        }
                        StorageCheckLevel.OK -> Unit
                    }
                }
            }
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CAPTURE_STOP_TIMEOUT_MS = 30_000L
    }
}
