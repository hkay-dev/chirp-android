package dev.chirpboard.app.feature.recording.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.IBinder
import android.os.PowerManager
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
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.recording.R
import dev.chirpboard.app.feature.recording.session.RecordingCapturePaths
import dev.chirpboard.app.feature.recording.util.RecordingTitleFormatter
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
    lateinit var segmentRotator: RecordingSegmentRotator

    @Inject
    lateinit var notificationFactory: RecordingNotificationFactory

    @Inject
    lateinit var serviceEvents: RecordingServiceEvents

    @Inject
    lateinit var titleFormatter: RecordingTitleFormatter

    private lateinit var audioFocusManager: AudioFocusManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var segmentCapture: GaplessSegmentCaptureEngine? = null
    private var currentRecordingFile: File? = null
    private var currentProfileId: UUID? = null
    private var currentOrigin: RecordingOrigin = RecordingOrigin.APP
    private var currentSessionId: UUID? = null
    private var currentInProgressRecordingId: UUID? = null
    private var currentFinalAudioPath: File? = null

    private var amplitudeJob: Job? = null
    private var heartbeatJob: Job? = null
    private var storageCheckJob: Job? = null
    private var segmentRotationJob: Job? = null
    private val segmentTransitionMutex = Mutex()
    private val stopRequestGate = StopRequestGate()
    private val restartStopCoordinator = RestartStopCoordinator(stopRequestGate)
    private var currentCorrelationId: String? = null
    private var stopRecordingJob: Job? = null
    private var startRecordingJob: Job? = null
    private val startGeneration = AtomicInteger(0)
    private val stopGeneration = AtomicInteger(0)
    private val startCancelMutex = Mutex()
    private val emergencyStopScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Set in onDestroy before scopes wind down so capture-thread callbacks (which may
     * outlive the service object) can be dropped instead of touching dead state.
     */
    @Volatile
    private var destroyed = false

    /**
     * True while the current Paused state was entered because of a transient audio focus
     * loss (call/alarm/assistant), so focus regain may auto-resume. Manual pause always
     * clears it. Advisory only — never feeds stop/finalize decisions.
     */
    @Volatile
    private var pausedByFocusLoss = false

    /**
     * Timed partial wakelock held across the stop->finalize handoff (PRF-6). Capture
     * itself needs none (audioserver holds one for live AudioRecord streams), but the
     * moment the mic closes a lock-screen stop could be CPU-suspended mid-handoff.
     * Acquired when a gated stop starts, released in [finishStopLifecycle]; the timeout
     * guarantees release even if a release path is missed.
     */
    private var stopWakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationFactory.ensureChannel(this)
        audioFocusManager = AudioFocusManager(getSystemService(AudioManager::class.java))
        audioFocusManager.onFocusLost = { lossKind ->
            when (lossKind) {
                AudioFocusManager.FocusLossKind.TRANSIENT ->
                    pauseRecording(autoPauseReason = RecordingAutoPauseReason.FOCUS_LOST_TRANSIENT)
                AudioFocusManager.FocusLossKind.PERMANENT -> {
                    announceAutoStop(RecordingAutoStopReason.FOCUS_LOST)
                    stopRecording()
                }
            }
        }
        audioFocusManager.onFocusRegained = { resumeAfterFocusRegained() }
        val serviceRef = WeakReference(this)
        inputDeviceSelector.setOnActiveDeviceLostListener {
            serviceRef.get()?.let { service ->
                service.serviceScope.launch {
                    // The listener now survives for the whole service lifetime (it used to
                    // be silently dropped by clearActiveDevice after the first session), so
                    // it must only ever stop captures this service instance owns. In-process
                    // keyboard/recognition captures detect device death through their own
                    // AudioRecord read errors.
                    if (!service.recordingStateManager.state.value.isActive || !service.serviceOwnsCapture()) {
                        return@launch
                    }
                    service.announceAutoStop(RecordingAutoStopReason.INPUT_DEVICE_LOST)
                    service.stopRecording()
                }
            }
        }
    }

    /**
     * Records why the service is ending a session on its own: a persistent event for the
     * recording screens plus a transient system notification (the foreground notification
     * disappears with the stop, so this is the only surviving system-surface explanation).
     * Advisory only — the stop itself still flows through the unchanged gated stop path.
     */
    private fun announceAutoStop(reason: RecordingAutoStopReason) {
        serviceEvents.publishAutoStop(reason)
        runCatching { notificationFactory.notifyAutoStopped(this, reason) }
        ReliabilityEventLogger
            .scoped(
                stage = ReliabilityStage.RECORDING_STOP,
                correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
            ).started("auto_stop_${reason.name.lowercase()}")
    }

    /**
     * Auto-resume after a transient focus interruption ends (AUD-05): only when the
     * session is still Paused and that pause was focus-initiated — never over a manual
     * pause or after a permanent loss (which already stopped with save).
     */
    private fun resumeAfterFocusRegained() {
        if (!pausedByFocusLoss) return
        if (recordingStateManager.state.value !is RecordingState.Paused) return
        ReliabilityEventLogger
            .scoped(
                stage = ReliabilityStage.RECORDING_START,
                correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
            ).started("auto_resume_after_focus_regain")
        resumeRecording()
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
            null -> handleCommandlessStart()
        }
        return START_STICKY
    }

    /**
     * True when this service instance owns the capture lifecycle behind the shared
     * recording lock: a session is live, a start or stop is in flight, or teardown work
     * holds the start-cancel mutex. The shared lock can also be claimed by in-process
     * captures that never touch this service (keyboard quick capture, voice recognition),
     * in which case a cold instance must never stop, finalize, or clobber that capture's
     * state.
     */
    private fun serviceOwnsCapture(): Boolean =
        currentSessionId != null ||
            startRecordingJob?.isActive == true ||
            stopRecordingJob?.isActive == true ||
            stopRequestGate.isInProgress() ||
            startCancelMutex.isLocked

    /**
     * Invoked for null intents (START_STICKY system restarts) and unknown actions.
     * The service may have been launched via startForegroundService, so startForeground
     * must always run before deciding whether to keep running or shut down cleanly.
     */
    private fun handleCommandlessStart() {
        RecordingServiceStartContract.runCommandlessStart(
            keepRunningForActiveCapture =
                serviceOwnsCapture() &&
                    (recordingStateManager.state.value.isActive || stopRequestGate.isInProgress()),
            promoteToForegroundStarting = ::promoteToForegroundImmediately,
            promoteToForegroundActive = ::promoteToForegroundForActiveRecording,
            shutDownService = {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            },
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyed = true
        val state = recordingStateManager.state.value
        val destroyPlan =
            RecordingServiceLifecycleCleanup.prepareDestroy(
                state = state,
                stopInProgress = stopRequestGate.isInProgress(),
                serviceOwnsCapture = serviceOwnsCapture(),
                cancelPeriodicJobs = {
                    amplitudeJob?.cancel()
                    heartbeatJob?.cancel()
                    storageCheckJob?.cancel()
                    segmentRotationJob?.cancel()
                },
                detachCallbacks = {
                    inputDeviceSelector.setOnActiveDeviceLostListener(null)
                    audioFocusManager.onFocusLost = null
                    audioFocusManager.onFocusRegained = null
                    segmentCapture?.setCaptureErrorListener(null)
                    segmentCapture?.setSilenceListener(null)
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
        RecordingServiceStartContract.runStartCommand(
            hasRecordPermission = RecordingPermissionGuard.hasRecordAudioPermission(this),
            tryAcquireRecordingLock = { recordingStateManager.tryStartRecording(origin, profileId) },
            serviceOwnsCapture = ::serviceOwnsCapture,
            promoteToForegroundStarting = ::promoteToForegroundImmediately,
            promoteToForegroundActive = ::promoteToForegroundForActiveRecording,
            onPermissionDenied = {
                recordingStateManager.onRecordingError(
                    RecordingPermissionGuard.PERMISSION_DENIED_MESSAGE,
                    SecurityException("RECORD_AUDIO permission missing"),
                )
            },
            onAlreadyRecording = { ownedByThisService ->
                ReliabilityEventLogger
                    .scoped(
                        stage = ReliabilityStage.RECORDING_START,
                        correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                    ).skipped(if (ownedByThisService) "already_recording" else "already_recording_unowned")
            },
            beginStart = {
                val generation = startGeneration.incrementAndGet()
                startRecordingJob?.cancel()
                startRecordingJob =
                    serviceScope.launch {
                        startRecordingAfterLockAcquired(origin, profileId, generation)
                    }
            },
            shutDownService = {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            },
        )
    }

    private fun promoteToForegroundImmediately() {
        ServiceCompat.startForeground(
            this,
            RecordingNotificationFactory.NOTIFICATION_ID,
            notificationFactory.createStartingNotification(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    /**
     * Re-posts the live recording notification via startForeground so an extra
     * start command does not clobber the notification of the active capture.
     */
    private fun promoteToForegroundForActiveRecording() {
        ServiceCompat.startForeground(
            this,
            RecordingNotificationFactory.NOTIFICATION_ID,
            notificationFactory.createRecordingNotification(this, recordingStateManager),
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
        val startLog =
            ReliabilityEventLogger.scoped(
                stage = ReliabilityStage.RECORDING_START,
                correlationId = currentCorrelationId!!,
            )

        val storageCheck = withContext(Dispatchers.IO) { storageMonitor.checkAvailableStorage() }
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

        startLog.started("service_start")

        try {
            // LOAD-1: do NOT release the shared speech recognizer here. This service only
            // captures audio (transcription runs later in TranscriptionWorker, which loads the
            // model itself), so releasing the ~660MB Parakeet model on every record-start bought
            // no headroom for capture yet forced the next keyboard dictation to cold-reload the
            // model (the user's "it loads the model again" complaint). The recognizer is kept warm
            // while the keyboard is enabled and is released only under genuine memory pressure
            // (RecognizerManager.releaseRecognizer via the app's ComponentCallbacks2 trim hook).
            ensureStartNotCancelled()

            withContext(Dispatchers.IO) {
                startCancelMutex.withLock {
                    ensureStartNotCancelled()

                    sessionReconciler.reconcileCompletedSessions()

                    val recordingQualityConfig =
                        audioSettingsStore.currentRecordingQualityPreset().appRecordingConfig
                    val outputFormat = audioSettingsStore.currentOutputFormat()
                    val microphoneGain = audioSettingsStore.currentMicrophoneGain()

                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    // Second-granularity timestamps collide when two stops land in the same
                    // second (e.g. keyboard and recorder finalizing simultaneously), so append
                    // a short UUID fragment to keep final audio paths unique.
                    val uniqueSuffix = UUID.randomUUID().toString().take(FILENAME_UNIQUE_SUFFIX_LENGTH)
                    val outputDir = File(filesDir, "recordings").apply { mkdirs() }
                    val finalFile =
                        File(outputDir, "recording_${timestamp}_$uniqueSuffix${outputFormat.fileExtension}")
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
                            // Recognition surfaces never drive RecordingService capture; map to
                            // the KEYBOARD source for consistency with recognition history.
                            RecordingOrigin.RECOGNITION -> RecordingSource.KEYBOARD
                        }
                    val provisionalTitle = titleFormatter.format(System.currentTimeMillis())
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
                        sampleRate = recordingQualityConfig.sampleRate,
                        gainMultiplier = microphoneGain,
                    )
                }
            }
            ensureStartNotCancelled()
            recordingStateManager.onRecordingStarted(
                audioFilePath = currentRecordingFile!!.absolutePath,
                recordingId = currentInProgressRecordingId,
            )

            startLog.success("recorder_started")

            // Upgrade the starting notification to the live recording UI
            ServiceCompat.startForeground(
                this,
                RecordingNotificationFactory.NOTIFICATION_ID,
                notificationFactory.createRecordingNotification(this, recordingStateManager),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )

            // No per-second notification loop: the chronometer in the notification ticks
            // the timer in SystemUI with zero app work; re-posts happen only on state
            // transitions and warning changes (PRF-5).
            startAmplitudeCollection()
            startSessionHeartbeat()
            startStorageMonitoring()
            startSegmentRotation()
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (startGenerationToken != startGeneration.get()) {
                // A newer start, restart, cancel, or stop superseded this start and owns
                // all cleanup and service lifecycle. Every superseding initiator bumps the
                // start generation before cancelling this job; running this handler late
                // would abandon the new session's audio focus, wipe its fields, and
                // stopSelf() the service out from under the restarted recording.
                throw e
            }
            currentInProgressRecordingId?.let { recordingRepository.deleteInProgressRecording(it) }
            currentInProgressRecordingId = null
            currentSessionId?.let { sessionJournal.markAbandoned(it) }
            currentSessionId?.let { capturePaths.deleteCaptureArtifacts(it) }
            currentSessionId = null
            audioFocusManager.abandonFocus()
            inputDeviceSelector.clearActiveDevice()
            segmentCapture?.setCaptureErrorListener(null)
            segmentCapture?.setSilenceListener(null)
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
            segmentCapture?.setCaptureErrorListener(null)
            segmentCapture?.setSilenceListener(null)
            segmentCapture?.releaseWithoutSave()
            segmentCapture = null
            ReliabilityEventLogger
                .scoped(
                    stage = ReliabilityStage.RECORDING_START,
                    correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                ).failure("recorder_start_failed", e)
            recordingStateManager.onRecordingError("Couldn't start the recording", e)
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

    private fun pauseRecording(autoPauseReason: RecordingAutoPauseReason? = null) {
        serviceScope.launch {
            try {
                segmentTransitionMutex.withLock {
                    if (recordingStateManager.state.value !is RecordingState.Recording) return@withLock
                    segmentRotationJob?.cancel()
                    amplitudeJob?.cancel()
                    amplitudeJob = null
                    recordingStateManager.pauseRecording()
                    recordingStateManager.updateAmplitude(0f)
                    // Set inside the mutex, after the pause actually landed, so a manual
                    // pause racing a focus loss can never be auto-resumed later.
                    pausedByFocusLoss = autoPauseReason == RecordingAutoPauseReason.FOCUS_LOST_TRANSIENT
                    serviceEvents.setAutoPauseReason(autoPauseReason)
                    serviceEvents.setSilenceDetected(false)

                    val sessionId = currentSessionId ?: return@withLock
                    val completedFile =
                        withContext(Dispatchers.IO) {
                            segmentCapture?.setCaptureErrorListener(null)
                            segmentCapture?.setSilenceListener(null)
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
                    ReliabilityEventLogger
                        .scoped(
                            stage = ReliabilityStage.RECORDING_STOP,
                            correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                        ).success("segment_saved_on_pause")
                }
                refreshRecordingNotification()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pause recording", e)
                recordingStateManager.onRecordingError("Couldn't pause the recording", e)
            }
        }
    }

    private fun resumeRecording() {
        pausedByFocusLoss = false
        serviceEvents.setAutoPauseReason(null)
        serviceScope.launch {
            try {
                segmentTransitionMutex.withLock {
                    val sessionId = currentSessionId ?: return@withLock
                    val entry = sessionJournal.findBySessionId(sessionId) ?: return@withLock
                    val nextSegment = capturePaths.durableSegmentFile(sessionId, entry.segmentPaths.size)
                    val recordingQualityConfig =
                        audioSettingsStore.currentRecordingQualityPreset().appRecordingConfig
                    val microphoneGain = audioSettingsStore.currentMicrophoneGain()

                    withContext(Dispatchers.IO) {
                        segmentCapture =
                            createCaptureEngine(
                                sampleRate = recordingQualityConfig.sampleRate,
                                gainMultiplier = microphoneGain,
                            )
                        segmentCapture!!.start(nextSegment)
                    }

                    currentRecordingFile = nextSegment
                    sessionJournal.beginNextSegment(sessionId, nextSegment.absolutePath)
                    recordingStateManager.resumeRecording(nextSegment.absolutePath)
                    ReliabilityEventLogger
                        .scoped(
                            stage = ReliabilityStage.RECORDING_START,
                            correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                        ).success("segment_started_on_resume", message = "segment=${entry.segmentPaths.size}")
                }
                startAmplitudeCollection()
                startSegmentRotation()
                refreshRecordingNotification()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                recordingStateManager.onRecordingError("Couldn't resume the recording", e)
            }
        }
    }

    /**
     * Re-posts the live notification with the current transient status line (focus-pause
     * reason, silence warning, low-storage warning). Only posts while a session is live so
     * a late refresh can never resurrect a notification after stop removed it.
     */
    private fun refreshRecordingNotification() {
        val state = recordingStateManager.state.value
        if (state !is RecordingState.Recording && state !is RecordingState.Paused) return
        notificationFactory.updateRecordingNotification(this, recordingStateManager, currentRecordingStatusText())
    }

    private fun currentRecordingStatusText(): String? =
        when {
            recordingStateManager.state.value is RecordingState.Paused && pausedByFocusLoss ->
                getString(R.string.rec_notification_paused_focus)
            serviceEvents.silenceDetected.value -> getString(R.string.rec_notification_silence)
            serviceEvents.storageLow.value -> getString(R.string.rec_notification_storage_low)
            else -> null
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
        amplitudeJob?.cancel()
        heartbeatJob?.cancel()
        storageCheckJob?.cancel()
        segmentRotationJob?.cancel()

        serviceScope.launch {
            startCancelMutex.withLock {
                val inProgressId = currentInProgressRecordingId
                val abandonedSessionId = currentSessionId
                val fileToDelete = currentRecordingFile
                val finalFileToDelete = currentFinalAudioPath
                abandonedSessionId?.let { sessionId ->
                    withContext(NonCancellable) {
                        if (sessionJournal.findBySessionId(sessionId) != null) {
                            sessionJournal.markAbandoned(sessionId)
                        }
                    }
                }
                currentSessionId = null
                currentInProgressRecordingId = null

                try {
                    segmentCapture?.setCaptureErrorListener(null)
                    segmentCapture?.setSilenceListener(null)
                    withContext(Dispatchers.IO) {
                        segmentCapture?.releaseWithoutSave()
                    }
                } catch (_: Exception) {
                } finally {
                    detachSegmentCapture()
                    withContext(NonCancellable + Dispatchers.IO) {
                        inProgressId?.let { recordingRepository.deleteAbandonedInProgressRecording(it) }
                        abandonedSessionId?.let { capturePaths.deleteCaptureArtifacts(it) }
                        fileToDelete?.takeIf { it.exists() }?.delete()
                        finalFileToDelete?.takeIf { it.exists() }?.delete()
                    }
                    currentRecordingFile = null
                    currentFinalAudioPath = null
                    pausedByFocusLoss = false
                    serviceEvents.resetSessionState()
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
     * Atomic restart: discard the current recording and immediately start a new one
     * without stopping the service in between.
     *
     * Restart claims [stopRequestGate] (via [restartStopCoordinator]) for the duration of
     * the teardown so it can never interleave with an in-flight stop: if a stop already
     * holds the gate the restart is refused, the stop wins, and the user is told via a
     * transient notification. The gate is reset only for the claim the restart itself
     * acquired — a pending stop is never clobbered. A stop request arriving while the
     * restart holds the gate is honored after the teardown by not starting a new
     * recording.
     */
    private fun restartRecording(
        origin: RecordingOrigin,
        profileId: UUID?,
    ) {
        if (!restartStopCoordinator.tryBeginRestart()) {
            Log.d(TAG, "Refusing restart while a stop is in progress")
            ReliabilityEventLogger
                .scoped(
                    stage = ReliabilityStage.RECORDING_START,
                    correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                ).skipped("restart_during_stop")
            notificationFactory.notifyRestartRefused(this)
            return
        }

        // Invalidate any in-flight start and any stale capture-stop work for the old session.
        startGeneration.incrementAndGet()
        stopGeneration.incrementAndGet()
        startRecordingJob?.cancel()
        startRecordingJob = null
        amplitudeJob?.cancel()
        heartbeatJob?.cancel()
        storageCheckJob?.cancel()
        segmentRotationJob?.cancel()

        serviceScope.launch {
            try {
                startCancelMutex.withLock {
                    discardActiveSessionForRestart()
                }
            } finally {
                restartStopCoordinator.finishRestart()
            }
            if (destroyed) {
                Log.w(TAG, "Skipping post-restart start; service destroyed during teardown")
                return@launch
            }
            if (restartStopCoordinator.consumeStopRequestedDuringRestart()) {
                honorStopRequestedDuringRestart()
                return@launch
            }
            startRecording(origin, profileId)
        }
    }

    /**
     * A stop arrived while the restart held the gate. The old session was already
     * discarded (that was the restart's irrevocable intent), so honoring the stop means
     * not starting the new recording and shutting the service down cleanly.
     */
    private fun honorStopRequestedDuringRestart() {
        Log.i(TAG, "Honoring stop received during restart teardown; not starting a new recording")
        ReliabilityEventLogger
            .scoped(
                stage = ReliabilityStage.RECORDING_STOP,
                correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
            ).success("stop_honored_after_restart")
        currentProfileId = null
        currentCorrelationId = null
        audioFocusManager.abandonFocus()
        inputDeviceSelector.clearActiveDevice()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun discardActiveSessionForRestart() {
        val oldSessionId = currentSessionId
        val oldRecordingId = currentInProgressRecordingId
        val oldFile = currentRecordingFile
        val oldFinalFile = currentFinalAudioPath
        currentRecordingFile = null
        currentFinalAudioPath = null
        currentSessionId = null
        currentInProgressRecordingId = null

        try {
            segmentCapture?.setCaptureErrorListener(null)
            segmentCapture?.setSilenceListener(null)
            // NonCancellable so a destroy-triggered scope cancellation mid-restart can
            // never leave the engine's audio resources held while the reference is
            // dropped by detachSegmentCapture below.
            withContext(NonCancellable + Dispatchers.IO) {
                segmentCapture?.releaseWithoutSave()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        } finally {
            detachSegmentCapture()
            withContext(NonCancellable + Dispatchers.IO) {
                oldSessionId?.let { sessionJournal.markAbandoned(it) }
                oldSessionId?.let { capturePaths.deleteCaptureArtifacts(it) }
                oldRecordingId?.let { recordingRepository.deleteAbandonedInProgressRecording(it) }
                oldFile?.takeIf { it.exists() }?.delete()
                oldFinalFile?.takeIf { it.exists() }?.delete()
            }
            pausedByFocusLoss = false
            serviceEvents.resetSessionState()
            recordingStateManager.forceCancel()
        }
    }

    private fun stopRecording() {
        if (recordingStateManager.state.value.activeOrigin == RecordingOrigin.KEYBOARD && !serviceOwnsCapture()) {
            // The active capture is an in-process keyboard capture that never touches this
            // service; running the gated stop here would hand off a null recording id and
            // force the live capture's state to Idle. Keyboard stops are routed through
            // the keyboard stop bridge instead.
            Log.w(TAG, "Ignoring stop for a keyboard capture this service instance does not own")
            ReliabilityEventLogger
                .scoped(
                    stage = ReliabilityStage.RECORDING_STOP,
                    correlationId = ReliabilityEventLogger.newCorrelationId("record"),
                ).skipped("stop_ignored_unowned_capture")
            return
        }
        if (!stopRequestGate.tryBegin()) {
            when (restartStopCoordinator.classifyRejectedStop()) {
                RestartStopCoordinator.RejectedStop.QUEUED_BEHIND_RESTART -> {
                    Log.i(TAG, "Stop requested during restart teardown; restart will stop instead of starting anew")
                    ReliabilityEventLogger
                        .scoped(
                            stage = ReliabilityStage.RECORDING_STOP,
                            correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                        ).started("stop_queued_during_restart")
                }
                RestartStopCoordinator.RejectedStop.DUPLICATE_STOP ->
                    Log.d(TAG, "Ignoring duplicate stop request while stop is in progress")
            }
            return
        }
        launchGatedStop()
    }

    /**
     * Single hardened stop entry point. The caller must have claimed [stopRequestGate] via
     * tryBegin() before invoking; this bumps the stop generation, halts periodic work, and
     * hands the capture off to the finalize queue. Internal error-triggered stops that need
     * stop-with-save semantics should claim the gate and reuse this path.
     */
    private fun launchGatedStop() {
        // PRF-6: keep the CPU awake across the stop->handoff window (lock-screen stops
        // would otherwise suspend mid-save until the next screen-on). Timed + released in
        // finishStopLifecycle; never alters any stop-gate or journal decision.
        acquireStopWakeLock()
        val stateAtStop = recordingStateManager.state.value
        val pendingStartJob = if (stateAtStop is RecordingState.Starting) startRecordingJob else null
        if (pendingStartJob != null) {
            startGeneration.incrementAndGet()
            pendingStartJob.cancel()
        }

        amplitudeJob?.cancel()
        heartbeatJob?.cancel()
        storageCheckJob?.cancel()
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
                    if (generation == stopGeneration.get()) {
                        recordingStateManager.onRecordingError("Couldn't stop the recording", e)
                    }
                } finally {
                    finishStopLifecycle(generation)
                }
            }
    }

    private suspend fun handoffCaptureToFinalizeQueue(
        sessionId: UUID?,
        generation: Int,
    ): StopSnapshot? =
        RecordingStopHandoff.handoff(
            sessionId = sessionId,
            generation = generation,
            stopGeneration = stopGeneration,
            stopCapture = { stopActiveCaptureForHandoff(generation) },
            captureSnapshot = {
                captureStopSnapshot()?.also { snapshot ->
                    ReliabilityEventLogger
                        .scoped(
                            stage = ReliabilityStage.RECORDING_STOP,
                            correlationId = snapshot.correlationId,
                        ).started("stop_requested")
                }
            },
            markAbandoned = { abandonedSessionId, recordingId ->
                withContext(Dispatchers.IO + NonCancellable) {
                    abandonedSessionId?.let { sessionJournal.markAbandoned(it) }
                    recordingId?.let { recordingRepository.deleteAbandonedInProgressRecording(it) }
                }
            },
            markStopping = { stoppingSessionId ->
                withContext(NonCancellable) {
                    sessionJournal.markStopping(stoppingSessionId)
                }
            },
            enqueueFinalize = { finalizeSnapshot, finalizeSessionId ->
                withContext(NonCancellable) {
                    RecordingFinalizeWorkRequest.enqueue(
                        context = this@RecordingService,
                        snapshot = finalizeSnapshot,
                        sessionId = finalizeSessionId,
                    )
                }
            },
            onCaptureStopHandoff = recordingStateManager::onCaptureStopHandoff,
            onStaleHandoff = {
                ReliabilityEventLogger
                    .scoped(
                        stage = ReliabilityStage.RECORDING_STOP,
                        correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                    ).skipped("stop_handoff_stale_generation")
            },
        )

    /**
     * Stops the active capture inside the segment-transition mutex and returns the verdict
     * unchanged so [RecordingStopHandoff] can react to it once. On a stale verdict no service
     * state is touched (a superseding cancel/restart owns it); every non-stale verdict adopts
     * the finalized/fallback file and detaches the engine before the handoff proceeds.
     */
    private suspend fun stopActiveCaptureForHandoff(generation: Int): CaptureStopHandoffResult {
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
                detachSegmentCapture()
            }
            is CaptureStopHandoffResult.TimedOut -> {
                currentRecordingFile = result.fallbackFile ?: currentRecordingFile
                detachSegmentCapture()
                ReliabilityEventLogger
                    .scoped(
                        stage = ReliabilityStage.RECORDING_STOP,
                        correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                    ).failure("capture_stop_timeout", message = "Capture stop exceeded ${CAPTURE_STOP_TIMEOUT_MS}ms")
            }
            is CaptureStopHandoffResult.Failed -> {
                currentRecordingFile = result.fallbackFile ?: currentRecordingFile
                detachSegmentCapture()
                ReliabilityEventLogger
                    .scoped(
                        stage = ReliabilityStage.RECORDING_STOP,
                        correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                    ).failure("capture_stop_failed", message = result.cause.message)
            }
            CaptureStopHandoffResult.StaleGeneration -> Unit
        }
        return result
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
            detachSegmentCapture()
            return
        }

        acquireStopWakeLock()
        val sessionId = currentSessionId
        val generation = stopGeneration.incrementAndGet()

        try {
            handoffCaptureToFinalizeQueue(sessionId, generation)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            recordingStateManager.onRecordingError("Failed to finalize recording during shutdown")
        } finally {
            finishStopLifecycle(generation)
            emergencyStopScope.cancel()
        }
    }

    private fun finishStopLifecycle(generation: Int) {
        if (generation != stopGeneration.get()) {
            // A cancel superseded this stop and now owns session state and service lifecycle.
            // Releasing the gate is the only thing left to do for this stop claim; clearing
            // the session fields here could wipe a newer session that started afterwards.
            stopRequestGate.reset()
            releaseStopWakeLock()
            return
        }
        currentRecordingFile = null
        currentFinalAudioPath = null
        currentProfileId = null
        currentSessionId = null
        currentInProgressRecordingId = null
        currentCorrelationId = null
        stopRequestGate.reset()
        pausedByFocusLoss = false
        serviceEvents.resetSessionState()
        audioFocusManager.abandonFocus()
        inputDeviceSelector.clearActiveDevice()
        releaseStopWakeLock()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireStopWakeLock() {
        if (stopWakeLock?.isHeld == true) return
        stopWakeLock =
            runCatching {
                getSystemService(PowerManager::class.java)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, STOP_WAKELOCK_TAG)
                    ?.apply {
                        setReferenceCounted(false)
                        acquire(STOP_WAKELOCK_TIMEOUT_MS)
                    }
            }.getOrNull()
    }

    private fun releaseStopWakeLock() {
        runCatching { stopWakeLock?.takeIf { it.isHeld }?.release() }
        stopWakeLock = null
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

    private suspend fun startGaplessCapture(
        segmentFile: File,
        sampleRate: Int,
        gainMultiplier: Float,
    ) {
        segmentCapture?.setCaptureErrorListener(null)
        segmentCapture?.setSilenceListener(null)
        segmentCapture?.releaseWithoutSave()
        segmentCapture = createCaptureEngine(sampleRate = sampleRate, gainMultiplier = gainMultiplier)
        segmentCapture!!.start(segmentFile)
    }

    /**
     * Creates a capture engine with the mid-recording error callback already attached, so a
     * capture-thread death (e.g. AudioRecord ERROR_DEAD_OBJECT) is observed from the moment
     * capture starts. The listener is bound to this specific engine instance so late
     * callbacks from a discarded engine can be recognized and dropped.
     */
    private fun createCaptureEngine(
        sampleRate: Int,
        gainMultiplier: Float,
    ): GaplessSegmentCaptureEngine {
        val engine =
            GaplessSegmentCaptureFactory.create(
                inputDeviceSelector = inputDeviceSelector,
                sampleRate = sampleRate,
                gainMultiplier = gainMultiplier,
            )
        engine.setCaptureErrorListener(
            GaplessCaptureErrorListener { error -> onCaptureEngineError(engine, error) },
        )
        engine.setSilenceListener(
            GaplessSilenceListener { silenced -> onCaptureSilenceChanged(engine, silenced) },
        )
        return engine
    }

    /**
     * Sustained-silence transitions from the capture thread (AUD-02). Pure-zero input
     * means the platform silenced this client (another app owns the mic, or the mic
     * privacy toggle is off) while reads keep succeeding — the session would record
     * nothing while looking live. Advisory only: surfaces a warning on the notification
     * and the recording screens; capture is never stopped or paused by it.
     */
    private fun onCaptureSilenceChanged(
        engine: GaplessSegmentCaptureEngine,
        silenced: Boolean,
    ) {
        if (destroyed) return
        serviceScope.launch {
            if (segmentCapture !== engine) return@launch
            if (serviceEvents.silenceDetected.value == silenced) return@launch
            serviceEvents.setSilenceDetected(silenced)
            ReliabilityEventLogger
                .scoped(
                    stage = ReliabilityStage.RECORDING_START,
                    correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                ).let { log ->
                    if (silenced) {
                        log.failure("capture_silence_detected", message = "Mic delivering pure silence — possibly in use elsewhere")
                    } else {
                        log.success("capture_silence_recovered")
                    }
                }
            refreshRecordingNotification()
        }
    }

    /**
     * Entry point for the engine error callback. Fires on the engine's capture thread, so it
     * only hops to the service main thread; all state decisions happen in
     * [handleCaptureEngineError]. By callback time the engine has already finalized the
     * partial segment and released its audio resources.
     */
    private fun onCaptureEngineError(
        engine: GaplessSegmentCaptureEngine,
        error: GaplessCaptureError,
    ) {
        if (destroyed) {
            Log.w(TAG, "Dropping capture error after service destroy: ${error.message}")
            return
        }
        // serviceScope is Dispatchers.Main and is cancelled during destroy, so this launch
        // is a no-op once the service is gone — callbacks can never touch dead state.
        serviceScope.launch {
            handleCaptureEngineError(engine, error)
        }
    }

    /**
     * Main-thread half of the capture error callback. Routes a mid-recording capture death
     * through the single hardened stop entry point with stop-with-save semantics: whatever
     * audio the engine already finalized is handed to the finalize queue, never discarded.
     * If a stop already owns the gate, or the engine is no longer the active capture, the
     * error is informational only — that other path owns finalization. The decision order
     * lives in [CaptureEngineErrorRouting] so it stays unit-testable.
     */
    private fun handleCaptureEngineError(
        engine: GaplessSegmentCaptureEngine,
        error: GaplessCaptureError,
    ) {
        val detail = captureErrorDetail(error)
        val decision =
            CaptureEngineErrorRouting.decide(
                destroyed = destroyed,
                engineIsActive = segmentCapture === engine,
                claimStopGate = stopRequestGate::tryBegin,
            )
        when (decision) {
            CaptureEngineErrorRouting.Decision.DROP_DESTROYED ->
                Log.w(TAG, "Dropping capture error after service destroy: $detail")
            CaptureEngineErrorRouting.Decision.DROP_STALE_ENGINE ->
                // The session already moved past this engine (paused, stopped, restarted);
                // its audio is owned by whichever path replaced it.
                Log.w(TAG, "Ignoring capture error from a discarded engine: $detail")
            CaptureEngineErrorRouting.Decision.INFORMATIONAL_STOP_IN_FLIGHT -> {
                Log.w(TAG, "Capture error while a stop is already in flight: $detail")
                ReliabilityEventLogger
                    .scoped(
                        stage = ReliabilityStage.RECORDING_STOP,
                        correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                    ).skipped("capture_error_during_stop", message = detail)
            }
            CaptureEngineErrorRouting.Decision.STOP_WITH_SAVE -> {
                Log.e(TAG, "Capture engine died mid-recording; stopping with save: $detail")
                ReliabilityEventLogger
                    .scoped(
                        stage = ReliabilityStage.RECORDING_STOP,
                        correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                    ).failure("capture_engine_error", message = detail)
                announceAutoStop(RecordingAutoStopReason.CAPTURE_ERROR)
                launchGatedStop()
            }
        }
    }

    private fun captureErrorDetail(error: GaplessCaptureError): String {
        val codeSuffix = error.audioRecordErrorCode?.let { " (audioRecordErrorCode=$it)" }.orEmpty()
        return "${error.message}$codeSuffix"
    }

    /**
     * Clears the error callback on the active engine and forgets the reference. Call only
     * from a path that owns the engine's end of life; late callbacks already in flight are
     * dropped by the identity and gate checks in [handleCaptureEngineError].
     */
    private fun detachSegmentCapture() {
        segmentCapture?.setCaptureErrorListener(null)
        segmentCapture?.setSilenceListener(null)
        segmentCapture = null
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
                    // StatFs is disk-class work; keep it off the Main-dispatcher service scope.
                    val level = withContext(Dispatchers.IO) { storageMonitor.checkAvailableStorage() }.level
                    when (level) {
                        StorageCheckLevel.LOW ->
                            if (!serviceEvents.storageLow.value) {
                                serviceEvents.setStorageLow(true)
                                refreshRecordingNotification()
                            }
                        StorageCheckLevel.CRITICAL -> {
                            announceAutoStop(RecordingAutoStopReason.STORAGE_CRITICAL)
                            stopRecording()
                            break
                        }
                        StorageCheckLevel.OK ->
                            if (serviceEvents.storageLow.value) {
                                serviceEvents.setStorageLow(false)
                                refreshRecordingNotification()
                            }
                    }
                }
            }
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CAPTURE_STOP_TIMEOUT_MS = 30_000L
        private const val FILENAME_UNIQUE_SUFFIX_LENGTH = 8
        private const val STOP_WAKELOCK_TAG = "chirpboard:stop-finalize"

        /** Capture-stop budget plus margin; the timed acquire guarantees release. */
        private const val STOP_WAKELOCK_TIMEOUT_MS = CAPTURE_STOP_TIMEOUT_MS + 30_000L
    }
}
