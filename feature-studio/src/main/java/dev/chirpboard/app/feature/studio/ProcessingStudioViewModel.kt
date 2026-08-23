package dev.chirpboard.app.feature.studio

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.WordReplacementRepository
import dev.chirpboard.app.feature.llm.client.LlmClient
import dev.chirpboard.app.feature.llm.client.TranscriptPassageAction
import dev.chirpboard.app.feature.llm.settings.LlmPreferences
import dev.chirpboard.app.core.playback.RecordingPlaybackController
import dev.chirpboard.app.core.transcription.RecoveryDiagnostics
import dev.chirpboard.app.core.transcription.TranscriptionRecovery
import dev.chirpboard.app.core.transcription.toUserMessage
import dev.chirpboard.app.core.util.DurableFiles
import java.io.IOException
import dev.chirpboard.app.core.ui.components.transcriptionProgressKind
import dev.chirpboard.app.core.ui.motion.ChirpMotion
import dev.chirpboard.app.core.ui.R as CoreUiR
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ProcessingStudioViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val savedStateHandle: SavedStateHandle,
        private val repository: RecordingRepository,
        private val llmClient: LlmClient,
        private val llmPreferences: LlmPreferences,
        private val wordReplacementRepository: WordReplacementRepository,
        private val transcriptionRecovery: TranscriptionRecovery,
        private val playbackController: RecordingPlaybackController,
    ) : ViewModel() {
        /**
         * Dispatcher used to build the word-level timed transcript off the main thread (DATA-3).
         * Overridable from same-module tests so the test scheduler governs the build.
         */
        internal var transcriptBuildDispatcher: CoroutineDispatcher = Dispatchers.Default

        /** Dispatcher for oversized-draft side-file IO (LIF-05); overridable from tests. */
        internal var draftFileDispatcher: CoroutineDispatcher = Dispatchers.IO

        /**
         * Serializes oversized-draft side-file IO. Cancelling the persist job is cooperative
         * and the atomic write has no suspension point, so a cancelled write can still be
         * running when its replacement (or the delete branch) starts; both derive the same
         * staging path, and DurableFiles requires callers to serialize per target.
         */
        private val draftFileMutex = Mutex()

        private var currentRecordingId: UUID? = null
        private var currentTranscript: Transcript? = null
        private var recordingObservationJob: Job? = null
        private var missingRecordingJob: Job? = null

        // Suppresses the NotFound transition between repository.delete() and the deferred
        // onDeleted() navigation, so a confirmed delete never flashes the error screen.
        private var isDeleting = false
        private var recoveryDiagnosticsJob: Job? = null
        private var lastScheduledRecoveryKey: RecoveryDiagnosticsRefreshKey? = null
        private var playbackRevealJob: Job? = null
        private var lastScheduledPlaybackRevealKey: PlaybackRevealKey? = null

        private val _message = MutableStateFlow<String?>(null)
        val message: StateFlow<String?> = _message.asStateFlow()

        fun clearMessage() {
            _message.value = null
        }

        private val _uiState = MutableStateFlow(ProcessingStudioState())
        val uiState: StateFlow<ProcessingStudioState> = _uiState.asStateFlow()

        private val _playbackTick = MutableStateFlow(StudioPlaybackTick())
        val playbackTick: StateFlow<StudioPlaybackTick> = _playbackTick.asStateFlow()

        private var cachedTranscriptInputs: TranscriptBuildInputs? = null

        // The collector re-fires on every recording/transcript/timings/snapshot emission, and
        // hashing the whole transcript on the main thread each time is measurable on long
        // dictations; the text only changes when the pipeline actually writes.
        private var cachedRevisionSource: String? = null
        private var cachedRevision: String = ""

        private fun memoizedTranscriptRevision(text: String): String {
            if (cachedRevisionSource != text) {
                cachedRevisionSource = text
                cachedRevision = text.structuredOutcomeRevision()
            }
            return cachedRevision
        }
        private var cachedTranscript: BuiltTranscript = EMPTY_BUILT_TRANSCRIPT

        private val structuredOutcomeGenerationInFlight = MutableStateFlow(false)

        // Count of chat requests awaiting a reply. Main-thread confined (viewModelScope).
        private var chatExchangesInFlight = 0

        /**
         * PLH-7: after a saved manual correction reduces to a single word/phrase replacement,
         * the screen offers to promote it to a global Word Replacement via an actionable
         * snackbar. Null when no offer is pending.
         */
        private val _promotionPrompt = MutableStateFlow<TranscriptCorrectionPromotionPrompt?>(null)
        val promotionPrompt: StateFlow<TranscriptCorrectionPromotionPrompt?> = _promotionPrompt.asStateFlow()

        fun clearPromotionPrompt() {
            _promotionPrompt.value = null
        }

        /**
         * LIF-05: mid-edit state captured before process death, applied once after the first
         * recording emission so a restored screen reopens in edit mode with the draft intact.
         */
        private var pendingDraftRestoration: StudioDraftRestoration? = null

        private fun readDraftRestoration(): StudioDraftRestoration? {
            val isEditingTranscript = savedStateHandle.get<Boolean>(KEY_IS_EDITING_TRANSCRIPT) ?: false
            val transcriptDraft = savedStateHandle.get<String>(KEY_TRANSCRIPT_DRAFT)
            val transcriptDraftInFile = savedStateHandle.get<Boolean>(KEY_TRANSCRIPT_DRAFT_IN_FILE) ?: false
            val isEditingTitle = savedStateHandle.get<Boolean>(KEY_IS_EDITING_TITLE) ?: false
            val editedTitle = savedStateHandle.get<String>(KEY_EDITED_TITLE)
            val isEditingNotes = savedStateHandle.get<Boolean>(KEY_IS_EDITING_NOTES) ?: false
            val editedNotes = savedStateHandle.get<String>(KEY_EDITED_NOTES)
            val chatDraft = savedStateHandle.get<String>(KEY_CHAT_DRAFT)
            if (!isEditingTranscript && !isEditingTitle && !isEditingNotes && chatDraft.isNullOrEmpty()) return null
            return StudioDraftRestoration(
                isEditingTranscript = isEditingTranscript,
                transcriptDraft = transcriptDraft,
                transcriptDraftInFile = transcriptDraftInFile,
                isEditingTitle = isEditingTitle,
                editedTitle = editedTitle,
                isEditingNotes = isEditingNotes,
                editedNotes = editedNotes,
                chatDraft = chatDraft,
            )
        }

        /**
         * LIF-05: offer the mid-edit state captured before process death. Oversized transcript
         * drafts are not bundle-mirrored; their text is loaded back from the side file first so
         * edit mode returns with the draft instead of silently dropping both.
         */
        private fun prepareDraftRestoration() {
            val restoration = readDraftRestoration()
            if (restoration?.transcriptDraftInFile != true) {
                // No file-backed draft to restore: remove any stale side file left behind by an
                // earlier process death, so a full transcript never lingers in filesDir.
                viewModelScope.launch(draftFileDispatcher) {
                    draftFileMutex.withLock { runCatching { oversizedDraftFile()?.delete() } }
                }
                pendingDraftRestoration = restoration
                return
            }
            viewModelScope.launch {
                val draft =
                    withContext(draftFileDispatcher) {
                        draftFileMutex.withLock {
                            runCatching { oversizedDraftFile()?.takeIf(File::exists)?.readText() }.getOrNull()
                        }
                    }
                if (draft == null) {
                    // The process died before the debounced side-file write landed. Reopening in
                    // edit mode on the saved base text (applyDraftRestoration falls back to the
                    // effective transcript) beats silently returning to read mode, and the
                    // message keeps the loss honest.
                    _message.value = context.getString(R.string.rec_msg_transcript_draft_lost)
                }
                offerDraftRestoration(
                    if (draft != null) restoration.copy(transcriptDraft = draft) else restoration,
                )
            }
        }

        /**
         * Applies a restoration directly when the first load already happened (the file-backed
         * draft read can finish after the collector's first emission), otherwise parks it for
         * the collector to consume exactly once.
         */
        private fun offerDraftRestoration(restoration: StudioDraftRestoration) {
            val state = _uiState.value
            if (state.loadState == ProcessingStudioLoadState.Ready) {
                _uiState.value = refreshTranscriptInteractionState(state.applyDraftRestoration(restoration))
            } else {
                pendingDraftRestoration = restoration
            }
        }

        private fun oversizedDraftFile(): File? =
            currentRecordingId?.let { File(context.filesDir, "studio-draft-$it.txt") }

        private fun writeOversizedDraft(draft: String) {
            val file = oversizedDraftFile() ?: return
            DurableFiles.writeTextAtomically(file, draft)
        }

        private var oversizedDraftPersistJob: Job? = null

        private fun mirrorTranscriptEditState(
            isEditing: Boolean,
            draft: String?,
        ) {
            val wasOversized = savedStateHandle.get<Boolean>(KEY_TRANSCRIPT_DRAFT_IN_FILE) ?: false
            savedStateHandle[KEY_IS_EDITING_TRANSCRIPT] = isEditing
            // Bundles have a hard size budget; very large drafts go to a side file (debounced)
            // instead of risking a TransactionTooLargeException on every lifecycle save.
            val mirroredDraft = draft?.takeIf { it.length <= MAX_MIRRORED_DRAFT_CHARS }
            val oversized = draft != null && mirroredDraft == null
            savedStateHandle[KEY_TRANSCRIPT_DRAFT] = mirroredDraft
            savedStateHandle[KEY_TRANSCRIPT_DRAFT_IN_FILE] = oversized
            oversizedDraftPersistJob?.cancel()
            when {
                oversized && draft != null ->
                    oversizedDraftPersistJob =
                        viewModelScope.launch {
                            // The first oversize transition writes immediately: from this point the
                            // bundle carries no draft, so until the file exists a process death
                            // loses everything. Later keystrokes debounce as usual.
                            if (wasOversized) delay(OVERSIZED_DRAFT_PERSIST_DEBOUNCE_MS)
                            withContext(draftFileDispatcher) {
                                draftFileMutex.withLock { runCatching { writeOversizedDraft(draft) } }
                            }
                        }

                !isEditing ->
                    viewModelScope.launch(draftFileDispatcher) {
                        draftFileMutex.withLock { runCatching { oversizedDraftFile()?.delete() } }
                    }
            }
        }

        private fun mirrorTitleEditState(
            isEditing: Boolean,
            editedTitle: String?,
        ) {
            savedStateHandle[KEY_IS_EDITING_TITLE] = isEditing
            savedStateHandle[KEY_EDITED_TITLE] = editedTitle
        }

        private fun mirrorNotesEditState(
            isEditing: Boolean,
            editedNotes: String?,
        ) {
            savedStateHandle[KEY_IS_EDITING_NOTES] = isEditing
            savedStateHandle[KEY_EDITED_NOTES] = editedNotes?.takeIf { it.length <= MAX_MIRRORED_DRAFT_CHARS }
        }

        val playbackState: StateFlow<dev.chirpboard.app.core.playback.RecordingPlaybackState> = playbackController.state

        init {
            val recordingIdStr = savedStateHandle.get<String>("recordingId")
            when {
                recordingIdStr.isNullOrEmpty() || recordingIdStr == "-1" -> {
                    markInvalidRecordingId()
                }

                else -> {
                    val parsedId = runCatching { UUID.fromString(recordingIdStr) }.getOrNull()
                    if (parsedId == null) {
                        markInvalidRecordingId()
                    } else {
                        loadRecording(parsedId)
                    }
                }
            }

            prepareDraftRestoration()

            viewModelScope.launch {
                llmPreferences.llmEnabled.collect { enabled ->
                    _uiState.value = _uiState.value.copy(llmProcessingEnabled = enabled)
                }
            }

            viewModelScope.launch {
                playbackController.state.collect { playback ->
                    val screenRecordingId = currentRecordingId ?: return@collect
                    if (playback.recordingId == screenRecordingId) {
                        // Single uiState write per tick: position + active segment are hoisted into
                        // the separate playbackTick flow, so a 10 Hz ticker no longer revs the
                        // screen-wide state object. Play/pause state is read straight from
                        // playbackController.state by the UI; only the duration is mirrored here.
                        val current = _uiState.value
                        val nextDurationMs = if (playback.durationMs > 0) playback.durationMs else current.durationMs
                        if (current.durationMs != nextDurationMs) {
                            _uiState.value = current.copy(durationMs = nextDurationMs)
                        }
                        updatePlaybackPosition(playback.positionMs)
                    }
                }
            }
        }

        private fun markInvalidRecordingId() {
            currentRecordingId = null
            _uiState.value =
                ProcessingStudioState(
                    loadState = ProcessingStudioLoadState.InvalidId,
                    isLoading = false,
                )
            cancelRecordingObservation()
        }

        private fun markRecordingNotFound() {
            currentRecordingId = null
            _uiState.value =
                ProcessingStudioState(
                    loadState = ProcessingStudioLoadState.NotFound,
                    isLoading = false,
                )
            cancelRecordingObservation()
        }

        private fun cancelRecordingObservation() {
            missingRecordingJob?.cancel()
            missingRecordingJob = null
            recordingObservationJob?.cancel()
            recordingObservationJob = null
            recoveryDiagnosticsJob?.cancel()
            recoveryDiagnosticsJob = null
            lastScheduledRecoveryKey = null
            cancelPlaybackReveal()
        }

        private fun loadRecording(id: UUID) {
            cancelRecordingObservation()
            isDeleting = false
            playbackController.pauseIfDifferentRecording(id)
            recordingObservationJob =
                viewModelScope.launch {
                currentRecordingId = id
                currentTranscript = null
                cachedTranscriptInputs = null
                cachedTranscript = EMPTY_BUILT_TRANSCRIPT
                _playbackTick.value = StudioPlaybackTick()
                var sawRecording = false

                _uiState.value =
                    ProcessingStudioState(
                        loadState = ProcessingStudioLoadState.Loading,
                        isLoading = true,
                        playerRevealReady = false,
                    )
                combine(
                    repository.getRecordingFlow(id),
                    repository.getTranscriptFlow(id),
                    repository.getTranscriptTimingsFlow(id),
                    repository.getStructuredOutcomeSnapshotFlow(id),
                    structuredOutcomeGenerationInFlight,
                ) { recordingState, transcriptState, timingsState, snapshotState, isStructuredOutcomeGenerating ->
                    val anyLoadError =
                        listOfNotNull(
                            recordingState.errorMessage,
                            transcriptState.errorMessage,
                            timingsState.errorMessage,
                            snapshotState.errorMessage,
                        ).isNotEmpty()
                    if (anyLoadError) {
                        // Raw exception text is a developer diagnostic (I18N-05); the user gets
                        // localized copy. _message is a StateFlow, so the fixed string also stops
                        // the retry-backoff loop from re-posting a snackbar per failed emission.
                        _message.value = context.getString(R.string.rec_msg_recording_load_failed)
                    }
                    StudioRecordingLoadState(
                        recording = recordingState.value,
                        transcript = transcriptState.value,
                        timings = timingsState.value,
                        structuredOutcomeSnapshot = snapshotState.value,
                        isStructuredOutcomeGenerating = isStructuredOutcomeGenerating,
                        recordingLoadFailed = recordingState.errorMessage != null,
                    )
                }.distinctUntilChanged().collectLatest { loadState ->
                    if (_uiState.value.loadState == ProcessingStudioLoadState.NotFound) {
                        return@collectLatest
                    }
                    val recording = loadState.recording
                    val transcript = loadState.transcript
                    val timings = loadState.timings
                    val structuredOutcomeSnapshot = loadState.structuredOutcomeSnapshot
                    val isStructuredOutcomeGenerating = loadState.isStructuredOutcomeGenerating
                    if (recording != null) {
                        missingRecordingJob?.cancel()
                        missingRecordingJob = null
                        sawRecording = true
                        currentTranscript = transcript
                        val effectiveTranscriptText = transcript?.effectiveText.orEmpty()
                        // Built BEFORE the state snapshot below: this call suspends (it hops to a
                        // background dispatcher when the transcript changed), and user input
                        // handlers write _uiState during that hop. A snapshot taken before the
                        // suspension would clobber those writes at the copy() further down,
                        // eating keystrokes typed while an enhancement result lands.
                        val (transcriptState, renderedTranscriptText) =
                            buildTimedTranscript(
                                rawText = effectiveTranscriptText,
                                timings = timings,
                            )
                        val currentState = _uiState.value
                        val wasEditingTranscript = currentState.isEditingTranscript
                        val transcriptChanged = effectiveTranscriptText != currentState.effectiveTranscriptText
                        // A pipeline write landing mid-edit (enhancement finishing, correction
                        // promotion) must not throw away the user's unsaved typing: keep the draft
                        // and stay in edit mode. Saving uses the then-current effective text as its
                        // correction source, so the user's words still win over the pipeline's.
                        if (wasEditingTranscript && transcriptChanged) {
                            _message.value = context.getString(R.string.rec_msg_transcript_updated_while_editing)
                        }
                        val isEditingTranscript = wasEditingTranscript
                        val renderedTranscriptChanged = renderedTranscriptText != currentState.renderedTranscriptText
                        var nextState =
                            currentState.copy(
                                loadState = ProcessingStudioLoadState.Ready,
                                isLoading = false,
                                status = recording.status,
                                errorMessage = recording.errorMessage,
                                transcript = transcriptState,
                                renderedTranscriptText = renderedTranscriptText,
                                effectiveTranscriptText = effectiveTranscriptText,
                                rawTranscriptText = transcript?.rawText.orEmpty(),
                                enhancedTranscriptText = transcript?.processedText.orEmpty(),
                                transcriptDraft = if (isEditingTranscript) currentState.transcriptDraft else effectiveTranscriptText,
                                isEditingTranscript = isEditingTranscript,
                                hasManualCorrection = transcript?.hasManualCorrection == true,
                                summary = transcript?.summary ?: "",
                                structuredOutcomeSection =
                                    buildStructuredOutcomeSectionState(
                                        recordingStatus = recording.status,
                                        effectiveTranscriptText = effectiveTranscriptText,
                                        snapshot = structuredOutcomeSnapshot,
                                        isGenerating = isStructuredOutcomeGenerating,
                                        currentRevision = memoizedTranscriptRevision(effectiveTranscriptText),
                                    ),
                                title = recording.title,
                                createdAt = recording.createdAt.time,
                                notes = recording.notes.orEmpty(),
                                audioPath = recording.audioPath,
                                source = recording.source,
                                // Seed the duration from the persisted row so the header pill and
                                // player total don't read 0:00 until Media3 loads the file (studio
                                // playback prepare is deferred, and skipped entirely while another
                                // recording owns the controller). Once playback reports a duration
                                // for this recording, that value stays authoritative.
                                durationMs =
                                    if (currentState.durationMs > 0) {
                                        currentState.durationMs
                                    } else {
                                        recording.durationMs
                                    },
                            )

                        if (renderedTranscriptChanged) {
                            nextState = nextState.exitTranscriptSelectionMode()
                        }

                        // LIF-05: re-enter the interrupted edit exactly once, after the first load.
                        pendingDraftRestoration?.let { restoration ->
                            pendingDraftRestoration = null
                            nextState = nextState.applyDraftRestoration(restoration)
                        }

                        val recoveryKey =
                            RecoveryDiagnosticsRefreshKey(
                                recordingId = recording.id,
                                status = recording.status,
                                errorMessage = recording.errorMessage,
                            )
                        val stateWithRecoveryActions =
                            nextState.copy(
                                recoveryActions =
                                    computeTranscriptionRecoveryActions(
                                        recording.status,
                                        currentState.recoveryDiagnostics.ownership,
                                    ),
                            )

                        _uiState.value = refreshTranscriptInteractionState(stateWithRecoveryActions)
                        scheduleRecoveryDiagnosticsRefresh(recoveryKey)

                        if (recording.status != RecordingStatus.RECORDING) {
                            scheduleDeferredStudioPlayback(
                                recordingId = recording.id,
                                title = recording.title,
                                audioPath = recording.audioPath,
                            )
                        }
                    } else {
                        when {
                            // The repository emits value=null with an error message when the Room
                            // flow throws, then retries with backoff. That null is a read failure,
                            // not a deletion: tearing the screen down to "Recording not found"
                            // here would also cancel the very collector that heals on retry.
                            loadState.recordingLoadFailed -> Unit
                            isDeleting -> Unit
                            sawRecording -> markRecordingNotFound()
                            else -> scheduleMissingRecordingCheck(id)
                        }
                    }
                }
            }
        }

        private fun scheduleMissingRecordingCheck(id: UUID) {
            if (missingRecordingJob?.isActive == true) return
            _uiState.value =
                _uiState.value.copy(
                    loadState = ProcessingStudioLoadState.Loading,
                    isLoading = true,
                )
            missingRecordingJob =
                viewModelScope.launch {
                    delay(MISSING_RECORDING_GRACE_MS)
                    if (currentRecordingId != id) return@launch
                    // ERR-18: a disk-level read failure must not crash the check; treat it
                    // as "not missing" and let the collector heal on the next emission.
                    val stillMissing =
                        try {
                            repository.getRecording(id) == null
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.e("ProcessingStudioVM", "Missing-recording check failed", e)
                            false
                        }
                    if (stillMissing) {
                        markRecordingNotFound()
                    }
                }
        }

        private fun scheduleDeferredStudioPlayback(
            recordingId: UUID,
            title: String,
            audioPath: String,
        ) {
            if (!isPlaybackAndShareReadyAudioPath(audioPath)) return
            val revealKey = PlaybackRevealKey(recordingId = recordingId, audioPath = audioPath)
            if (lastScheduledPlaybackRevealKey == revealKey) return

            playbackRevealJob?.cancel()
            lastScheduledPlaybackRevealKey = revealKey
            _uiState.value = _uiState.value.copy(playerRevealReady = false)
            playbackRevealJob = viewModelScope.launch {
                delay(ChirpMotion.RECORD_HANDOFF_MS)
                if (currentRecordingId != recordingId || _uiState.value.audioPath != audioPath) return@launch
                _uiState.value = _uiState.value.copy(playerRevealReady = true)
                playbackController.onStudioOpened(recordingId, title, audioPath)
            }
        }

        private fun cancelPlaybackReveal() {
            playbackRevealJob?.cancel()
            playbackRevealJob = null
            lastScheduledPlaybackRevealKey = null
        }

        fun togglePlayPause() {
            val recordingId = currentRecordingId ?: return
            val screen = _uiState.value
            if (!screen.isAudioReady) return
            val playback = playbackController.state.value
            if (playback.recordingId == recordingId) {
                playbackController.togglePlayPause()
            } else {
                playbackController.play(recordingId, screen.title, screen.audioPath)
            }
        }

        fun seekTo(positionMs: Long) {
            val recordingId = currentRecordingId ?: return
            val screen = _uiState.value
            if (!screen.isAudioReady) return
            val playback = playbackController.state.value
            if (playback.recordingId != recordingId) {
                // The controller may hold (and be playing) a different recording; a bare
                // seekTo would scrub that one audibly. Hand the position to prepare so the
                // seek lands on this recording once it is loaded.
                playbackController.prepare(recordingId, screen.title, screen.audioPath, positionMs)
            } else {
                playbackController.seekTo(positionMs)
            }
            updatePlaybackPosition(positionMs)
        }

        fun skipForward() = skipBy(STUDIO_SKIP_MS)

        fun skipBackward() = skipBy(-STUDIO_SKIP_MS)

        private fun skipBy(deltaMs: Long) {
            val recordingId = currentRecordingId ?: return
            val screen = _uiState.value
            if (!screen.isAudioReady) return
            val playback = playbackController.state.value
            if (playback.recordingId == recordingId) {
                if (deltaMs >= 0) playbackController.skipForward(deltaMs) else playbackController.skipBackward(-deltaMs)
            } else {
                // Same wrong-recording hazard as seekTo: skip within this recording from
                // its local position instead of nudging whatever the controller holds.
                val target = (_playbackTick.value.currentPositionMs + deltaMs).coerceAtLeast(0L)
                playbackController.prepare(recordingId, screen.title, screen.audioPath, target)
                updatePlaybackPosition(target)
            }
        }

        private fun updatePlaybackPosition(positionMs: Long) {
            val current = _uiState.value
            val nextActiveIndex = activeSegmentIndexFor(current, positionMs)
            val currentTick = _playbackTick.value
            if (currentTick.currentPositionMs == positionMs && currentTick.activeTranscriptSegmentIndex == nextActiveIndex) {
                return
            }
            _playbackTick.value =
                currentTick.copy(
                    currentPositionMs = positionMs,
                    activeTranscriptSegmentIndex = nextActiveIndex,
                )
        }

        private fun activeSegmentIndexFor(
            state: ProcessingStudioState,
            positionMs: Long,
        ): Int =
            if (state.canUseTranscriptInteractions()) {
                findActiveTranscriptSegmentIndex(
                    transcript = state.transcript,
                    positionMs = positionMs,
                )
            } else {
                -1
            }

        fun onWordClicked(timestamp: Long) {
            if (!_uiState.value.isAudioReady) return
            if (!_uiState.value.canUseTranscriptInteractions()) return
            seekTo(timestamp)
            val recordingId = currentRecordingId ?: return
            val screen = _uiState.value
            if (!playbackController.state.value.isPlaying) {
                playbackController.play(recordingId, screen.title, screen.audioPath)
            }
        }

        fun updateChatDraft(newText: String) {
            savedStateHandle[KEY_CHAT_DRAFT] = newText.takeIf { it.length <= MAX_MIRRORED_DRAFT_CHARS }
            _uiState.value = _uiState.value.copy(chatDraft = newText)
        }

        fun onSendChatMessage(text: String) {
            val trimmedText = text.trim()
            if (trimmedText.isBlank()) return

            val userMsg = createStudioChatMessage(trimmedText, isFromUser = true)
            savedStateHandle[KEY_CHAT_DRAFT] = null
            _uiState.value =
                _uiState.value.copy(
                    chatMessages = (_uiState.value.chatMessages + userMsg).toImmutableList(),
                    chatDraft = "",
                    isTyping = true,
                )

            // A counter, not a boolean: a second send while the first reply is pending must not
            // clear the typing indicator when only one of them completes.
            chatExchangesInFlight++
            viewModelScope.launch {
                try {
                    // hasApiKey() suspends (keystore-backed read on IO). Without a key the request
                    // is unwinnable, so un-send: drop the bubble, put the text back in the draft
                    // (unless the user already typed something new) and point at settings —
                    // the same actionable copy the other AI paths use.
                    if (!llmPreferences.hasApiKey()) {
                        _uiState.value =
                            _uiState.value.copy(
                                chatMessages =
                                    _uiState.value.chatMessages
                                        .filterNot { it.id == userMsg.id }
                                        .toImmutableList(),
                            )
                        if (_uiState.value.chatDraft.isBlank()) updateChatDraft(trimmedText)
                        _message.value = context.getString(R.string.rec_msg_chat_api_key_missing)
                        return@launch
                    }
                    val outcome =
                        completeStudioChatExchange(
                            context = context,
                            llmClient = llmClient,
                            transcriptText = _uiState.value.effectiveTranscriptText,
                            history = _uiState.value.chatMessages,
                        )
                    when (outcome) {
                        is StudioChatExchangeOutcome.Reply ->
                            // Append to the LIVE list: overwriting with a launch-time snapshot
                            // deleted any message the user sent while this reply was in flight.
                            _uiState.value =
                                _uiState.value.copy(
                                    chatMessages = (_uiState.value.chatMessages + outcome.message).toImmutableList(),
                                )
                        is StudioChatExchangeOutcome.Failure -> _message.value = outcome.displayMessage
                    }
                } finally {
                    chatExchangesInFlight--
                    if (chatExchangesInFlight == 0) {
                        _uiState.value = _uiState.value.copy(isTyping = false)
                    }
                }
            }
        }

        fun generateStructuredOutcomes() {
            viewModelScope.launch {
                val state = _uiState.value
                // hasApiKey() suspends (keystore-backed read on IO), so the in-flight flag
                // must be read AFTER it resumes: two rapid taps both pass the suspension,
                // and only the later read makes the second tap see the first one's claim.
                val hasApiKey = llmPreferences.hasApiKey()
                val validationMessage =
                    validateStructuredOutcomeGenerationRequest(
                        recordingStatus = state.status,
                        effectiveTranscriptText = state.effectiveTranscriptText,
                        hasApiKey = hasApiKey,
                        isGenerating = structuredOutcomeGenerationInFlight.value,
                    )
                if (validationMessage != null) {
                    _message.value = context.getString(validationMessage)
                    return@launch
                }

                val recordingId = currentRecordingId ?: return@launch
                val transcriptText = state.effectiveTranscriptText
                val transcriptRevision = transcriptText.structuredOutcomeRevision()
                structuredOutcomeGenerationInFlight.value = true

                try {
                    val result = llmClient.generateStructuredOutcomeExtraction(transcriptText)
                    if (result.isSuccess) {
                        val extraction = result.getOrThrow()
                        repository.saveStructuredOutcomeSuccess(
                            recordingId = recordingId,
                            sourceTranscriptRevision = transcriptRevision,
                            tasks = extraction.tasks,
                            decisions = extraction.decisions,
                            followUps = extraction.followUps,
                        )
                    } else {
                        // I18N-05: persist friendly, actionable copy; the raw error goes to logs.
                        Log.e("ProcessingStudioVM", "Structured outcome generation failed", result.exceptionOrNull())
                        repository.saveStructuredOutcomeFailure(
                            recordingId = recordingId,
                            sourceTranscriptRevision = transcriptRevision,
                            failureMessage = aiFailureDisplayMessage(context, result.exceptionOrNull()),
                        )
                    }
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Log.e("ProcessingStudioVM", "Structured outcome generation failed", error)
                    repository.saveStructuredOutcomeFailure(
                        recordingId = recordingId,
                        sourceTranscriptRevision = transcriptRevision,
                        failureMessage = aiFailureDisplayMessage(context, error),
                    )
                } finally {
                    structuredOutcomeGenerationInFlight.value = false
                }
            }
        }

        fun draftStructuredOutcomeQuestion(item: StructuredOutcomeItemUi) {
            // Route through updateChatDraft so the generated question survives process death
            // like a hand-typed one, and never silently clobber text already in the box.
            val existing = _uiState.value.chatDraft
            val generated = buildStructuredOutcomeAskAiDraft(item)
            updateChatDraft(if (existing.isBlank()) generated else "$existing\n\n$generated")
        }

        fun onStructuredOutcomeCopied() {
            _message.value = context.getString(R.string.rec_copied_to_clipboard)
        }

        fun onTranscriptCopied() {
            _message.value = context.getString(R.string.rec_copied_to_clipboard)
        }

        fun startEditingTitle() {
            mirrorTitleEditState(isEditing = true, editedTitle = _uiState.value.title)
            _uiState.value =
                _uiState.value.copy(
                    isEditingTitle = true,
                    editedTitle = _uiState.value.title,
                )
        }

        fun updateEditedTitle(newTitle: String) {
            mirrorTitleEditState(isEditing = true, editedTitle = newTitle)
            _uiState.value = _uiState.value.copy(editedTitle = newTitle)
        }

        fun startEditingTranscript() {
            val state = _uiState.value
            if (state.effectiveTranscriptText.isBlank()) return
            if (state.isSelectingTranscript) {
                _message.value = context.getString(R.string.rec_msg_exit_selection_first)
                return
            }
            if (isTranscriptBusy(state.status)) {
                _message.value = context.getString(R.string.rec_msg_transcript_busy)
                return
            }

            val nextState = state.enterTranscriptEditMode()
            mirrorTranscriptEditState(isEditing = true, draft = nextState.transcriptDraft)
            _uiState.value = refreshTranscriptInteractionState(nextState)
        }

        fun updateTranscriptDraft(newText: String) {
            mirrorTranscriptEditState(isEditing = true, draft = newText)
            _uiState.value = _uiState.value.copy(transcriptDraft = newText)
        }

        fun cancelEditingTranscript() {
            mirrorTranscriptEditState(isEditing = false, draft = null)
            _uiState.value = refreshTranscriptInteractionState(_uiState.value.exitTranscriptEditMode())
        }

        fun enterTranscriptSelectionMode() {
            val state = _uiState.value
            if (state.isEditingTranscript) {
                _message.value = context.getString(R.string.rec_msg_finish_edit_first)
                return
            }
            if (!state.canEnterTranscriptSelectionMode()) return

            _uiState.value = refreshTranscriptInteractionState(state.enterTranscriptSelectionMode())
        }

        fun exitTranscriptSelectionMode() {
            _uiState.value = refreshTranscriptInteractionState(_uiState.value.exitTranscriptSelectionMode())
        }

        fun onTranscriptSelectionChanged(selectedText: String) {
            val state = _uiState.value
            if (!state.isSelectingTranscript) return

            _uiState.value = state.updateTranscriptSelection(selectedText)
        }

        fun runTranscriptSelectionAction(action: TranscriptPassageAction) {
            viewModelScope.launch {
                // hasApiKey() suspends, so the state snapshot is taken after it resumes;
                // startTranscriptSelectionAction below builds on the fresh value.
                val hasApiKey = llmPreferences.hasApiKey()
                val state = _uiState.value
                val validationMessage = state.validateTranscriptSelectionActionRequest(hasApiKey = hasApiKey)
                if (validationMessage != null) {
                    _message.value = context.getString(validationMessage)
                    return@launch
                }

                val selection = state.selectedTranscriptPassage
                val renderedTranscriptText = state.renderedTranscriptText
                _uiState.value = state.startTranscriptSelectionAction(action)

                val result = llmClient.generateTranscriptPassageResponse(action = action, passage = selection)
                val latestState = _uiState.value
                if (!latestState.matchesTranscriptSelectionRequest(selection, renderedTranscriptText, action)) {
                    return@launch
                }

                _uiState.value =
                    if (result.isSuccess) {
                        latestState.finishTranscriptSelectionAction(
                            action = action,
                            resultText = result.getOrThrow(),
                        )
                    } else {
                        latestState.failTranscriptSelectionAction()
                    }

                if (result.isFailure) {
                    // I18N-05: friendly classified copy; raw detail stays in logs.
                    Log.e("ProcessingStudioVM", "Transcript passage action failed", result.exceptionOrNull())
                    _message.value = aiFailureDisplayMessage(context, result.exceptionOrNull())
                }
            }
        }

        // Guards the ✓ button: two taps before the Room write returns would save the same
        // correction twice and offer two promotion prompts.
        private var correctionSaveInFlight = false

        fun saveTranscriptCorrection() {
            if (correctionSaveInFlight) return
            correctionSaveInFlight = true
            viewModelScope.launch {
                try {
                    saveTranscriptCorrectionNow()
                } finally {
                    correctionSaveInFlight = false
                }
            }
        }

        private suspend fun saveTranscriptCorrectionNow() {
            val recordingId = currentRecordingId ?: return
            val transcript = currentTranscript ?: return
            val correctedText = _uiState.value.transcriptDraft.trim()
            if (correctedText.isBlank()) {
                _message.value = context.getString(R.string.rec_msg_transcript_empty)
                return
            }

            val sourceText = transcript.effectiveText
            if (correctedText == sourceText) {
                mirrorTranscriptEditState(isEditing = false, draft = null)
                _uiState.value = refreshTranscriptInteractionState(_uiState.value.exitTranscriptEditMode())
                return
            }

            // ERR-18: one-shot Room writes throw on a full disk; surface instead of crashing.
            try {
                if (correctedText == transcript.pipelineText) {
                    repository.clearManualCorrection(recordingId)
                    _message.value = context.getString(R.string.rec_msg_correction_cleared)
                } else {
                    repository.saveManualCorrection(
                        recordingId = recordingId,
                        correctedText = correctedText,
                        sourceText = sourceText,
                    )
                    _message.value = context.getString(R.string.rec_msg_correction_saved)
                    // PLH-7: a single-word/phrase correction can be promoted to a global
                    // Word Replacement; offer it via an actionable snackbar. Large contiguous
                    // rewrites also produce a "diff", but they are not word replacements, so
                    // the offer is capped to short phrases.
                    analyzeTranscriptCorrectionPromotion(
                        sourceText = sourceText,
                        correctedText = correctedText,
                    )?.takeIf(::isPromotableAsWordReplacement)?.let { promotion ->
                        _promotionPrompt.value =
                            TranscriptCorrectionPromotionPrompt(
                                original = promotion.original,
                                replacement = promotion.replacement,
                            )
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("ProcessingStudioVM", "Failed to save transcript correction", e)
                _message.value = context.getString(R.string.rec_msg_correction_save_failed)
                return
            }

            mirrorTranscriptEditState(isEditing = false, draft = null)
            _uiState.value =
                refreshTranscriptInteractionState(
                    _uiState.value.copy(
                        isEditingTranscript = false,
                        transcriptDraft = correctedText,
                    ),
                )
        }

        fun promoteTranscriptCorrection() {
            // The prompt already carries the original/replacement computed at save time.
            // Re-deriving from currentTranscript raced the Room re-emission: a fast tap on
            // the snackbar action saw a stale in-memory transcript and failed spuriously.
            // Captured before launching so the caller's clearPromotionPrompt() cannot race it.
            val prompt = _promotionPrompt.value
            if (prompt == null) {
                _message.value = context.getString(R.string.rec_msg_promotion_unavailable)
                return
            }
            val promotion =
                TranscriptCorrectionPromotion(
                    original = prompt.original,
                    replacement = prompt.replacement,
                )
            viewModelScope.launch {
                // ERR-18: one-shot Room writes throw on a full disk; surface instead of crashing.
                try {
                    val existing =
                        wordReplacementRepository.getEquivalentReplacement(
                            original = promotion.original,
                            replacement = promotion.replacement,
                        )
                    if (existing != null) {
                        _message.value = context.getString(R.string.rec_msg_replacement_exists)
                        return@launch
                    }

                    wordReplacementRepository.createReplacement(
                        original = promotion.original,
                        replacement = promotion.replacement,
                    )
                    _message.value = context.getString(R.string.rec_msg_replacement_added)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e("ProcessingStudioVM", "Failed to promote transcript correction", e)
                    _message.value = context.getString(R.string.rec_msg_replacement_add_failed)
                }
            }
        }

        /**
         * PIPE-07: user-facing cancel for a queued/running transcription. The recovery port
         * resolves the row to a neutral awaiting/completed state rather than FAILED.
         */
        fun cancelTranscription() {
            viewModelScope.launch {
                val recordingId = currentRecordingId ?: return@launch
                try {
                    transcriptionRecovery.cancelProcessing(recordingId)
                    _message.value = context.getString(CoreUiR.string.rec_msg_transcription_cancelled)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e("ProcessingStudioVM", "Failed to cancel processing for $recordingId", e)
                    _message.value = context.getString(CoreUiR.string.rec_msg_cancel_transcription_failed)
                }
                refreshRecoveryForCurrentRecording()
            }
        }

        fun retranscribe() {
            if (_uiState.value.isEditingTranscript) {
                _message.value = context.getString(R.string.rec_msg_finish_edit_first)
                return
            }

            launchRecoveryAction {
                val recordingId = currentRecordingId ?: return@launchRecoveryAction
                val result = transcriptionRecovery.retranscribe(recordingId)
                refreshRecoveryForCurrentRecording()
                _message.value = result.toUserMessage(context, context.getString(CoreUiR.string.rec_msg_requeued_transcription))
            }
        }

        fun recoverPendingTranscription() {
            launchRecoveryAction {
                val recordingId = currentRecordingId ?: return@launchRecoveryAction
                val result = transcriptionRecovery.recoverPendingTranscription(recordingId)
                _message.value = result.toUserMessage(context, context.getString(R.string.rec_msg_pending_recovered))
                refreshRecoveryForCurrentRecording()
            }
        }

        fun recoverEnhancing() {
            launchRecoveryAction {
                val recordingId = currentRecordingId ?: return@launchRecoveryAction
                val result =
                    if (_uiState.value.status == RecordingStatus.PENDING_ENHANCEMENT) {
                        transcriptionRecovery.recoverPendingEnhancement(recordingId)
                    } else {
                        transcriptionRecovery.recoverEnhancing(recordingId)
                    }
                _message.value = result.toUserMessage(context, context.getString(R.string.rec_msg_enhancement_recovery_queued))
                refreshRecoveryForCurrentRecording()
            }
        }

        fun retranscribeFromEnhancing() {
            launchRecoveryAction {
                val recordingId = currentRecordingId ?: return@launchRecoveryAction
                val result = transcriptionRecovery.retranscribeFromEnhancing(recordingId)
                _message.value = result.toUserMessage(context, context.getString(R.string.rec_msg_retranscription_queued))
                refreshRecoveryForCurrentRecording()
            }
        }

        fun retryTranscription() {
            launchRecoveryAction {
                val recordingId = currentRecordingId ?: return@launchRecoveryAction
                val result = transcriptionRecovery.retry(recordingId)
                _message.value = result.toUserMessage(context, context.getString(CoreUiR.string.rec_msg_requeued_transcription))
                refreshRecoveryForCurrentRecording()
            }
        }

        // The recovery buttons stay enabled until a DB round trip updates the status, so a
        // double tap within that window would enqueue the same recovery twice and post two
        // snackbars. One flag covers all five actions; they are mutually exclusive operations
        // on the same recording. viewModelScope is Main.immediate, so plain field access is
        // race-free.
        private var recoveryActionInFlight = false

        private fun launchRecoveryAction(block: suspend () -> Unit) {
            if (recoveryActionInFlight) return
            recoveryActionInFlight = true
            viewModelScope.launch {
                try {
                    block()
                } finally {
                    recoveryActionInFlight = false
                }
            }
        }

        private suspend fun refreshRecoveryForCurrentRecording() {
            val recordingId = currentRecordingId ?: return
            val state = _uiState.value
            val key =
                RecoveryDiagnosticsRefreshKey(
                    recordingId = recordingId,
                    status = state.status,
                    errorMessage = state.errorMessage,
                )
            loadRecoveryDiagnostics(key)?.let(::applyRecoveryDiagnosticsResult)
        }

        private fun scheduleRecoveryDiagnosticsRefresh(key: RecoveryDiagnosticsRefreshKey) {
            if (lastScheduledRecoveryKey == key) return

            lastScheduledRecoveryKey = key
            recoveryDiagnosticsJob?.cancel()
            recoveryDiagnosticsJob =
                viewModelScope.launch {
                    loadRecoveryDiagnostics(key)?.let(::applyRecoveryDiagnosticsResult)
                }
        }

        private suspend fun loadRecoveryDiagnostics(key: RecoveryDiagnosticsRefreshKey): RecoveryDiagnosticsResult? {
            // ERR-18: a disk-level read failure degrades to no diagnostics update instead
            // of crashing the studio while it is trying to show a recovery path.
            val diagnostics =
                try {
                    transcriptionRecovery.getRecoveryDiagnostics(key.recordingId)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e("ProcessingStudioVM", "Failed to load recovery diagnostics", e)
                    return null
                }
            return RecoveryDiagnosticsResult(key = key, diagnostics = diagnostics)
        }

        private fun applyRecoveryDiagnosticsResult(result: RecoveryDiagnosticsResult) {
            val state = _uiState.value
            val currentKey =
                RecoveryDiagnosticsRefreshKey(
                    recordingId = currentRecordingId ?: return,
                    status = state.status,
                    errorMessage = state.errorMessage,
                )
            if (currentKey != result.key) return

            val diagnostics = result.diagnostics.toUiModel()
            _uiState.value =
                state.copy(
                    recoveryDiagnostics = diagnostics,
                    recoveryActions = computeTranscriptionRecoveryActions(state.status, diagnostics.ownership),
                )
        }

        fun cancelEditingTitle() {
            mirrorTitleEditState(isEditing = false, editedTitle = null)
            _uiState.value = _uiState.value.copy(isEditingTitle = false)
        }

        // --- NOTES: freeform per-recording note (captured live on the record screen, edited here) ---

        fun startEditingNotes() {
            val current = _uiState.value
            mirrorNotesEditState(isEditing = true, editedNotes = current.notes)
            _uiState.value = current.copy(isEditingNotes = true, editedNotes = current.notes)
        }

        fun updateEditedNotes(newNotes: String) {
            mirrorNotesEditState(isEditing = true, editedNotes = newNotes)
            _uiState.value = _uiState.value.copy(editedNotes = newNotes)
        }

        fun cancelEditingNotes() {
            mirrorNotesEditState(isEditing = false, editedNotes = null)
            _uiState.value = _uiState.value.copy(isEditingNotes = false)
        }

        /**
         * Persists the edited note. Clearing all text removes the note (the row's notes column
         * goes back to NULL via the repository's blank normalization, hiding the section again).
         */
        fun saveNotes() {
            viewModelScope.launch {
                val id = currentRecordingId ?: return@launch
                val trimmedNotes = _uiState.value.editedNotes.trim()
                // ERR-18: surface a full-disk write failure instead of crashing.
                try {
                    repository.updateNotes(id, trimmedNotes)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e("ProcessingStudioVM", "Failed to save note", e)
                    _message.value = context.getString(R.string.rec_msg_note_save_failed)
                    return@launch
                }
                mirrorNotesEditState(isEditing = false, editedNotes = null)
                _uiState.value = _uiState.value.copy(isEditingNotes = false, notes = trimmedNotes)
            }
        }

        fun saveTitle() {
            viewModelScope.launch {
                val id = currentRecordingId ?: return@launch
                val trimmedTitle = _uiState.value.editedTitle.trim()
                if (trimmedTitle.isEmpty()) {
                    // A blank Save used to exit edit mode with the old title and no feedback;
                    // there is a separate Cancel button, so tell the user and stay editing.
                    _message.value = context.getString(R.string.rec_msg_title_blank)
                    return@launch
                }
                // ERR-18: surface a full-disk write failure instead of crashing.
                try {
                    repository.updateTitle(id, trimmedTitle)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e("ProcessingStudioVM", "Failed to save title", e)
                    _message.value = context.getString(R.string.rec_msg_title_save_failed)
                    return@launch
                }
                _uiState.value = _uiState.value.copy(title = trimmedTitle)
                mirrorTitleEditState(isEditing = false, editedTitle = null)
                _uiState.value = _uiState.value.copy(isEditingTitle = false)
            }
        }

        fun deleteRecording(onDeleted: () -> Unit) {
            viewModelScope.launch {
                val id = currentRecordingId ?: return@launch
                // ERR-18: a disk-level read failure surfaces as a failed delete, not a crash.
                val rec =
                    try {
                        repository.getRecording(id)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.e("ProcessingStudioVM", "Failed to load recording for delete: $id", e)
                        _message.value = context.getString(CoreUiR.string.rec_msg_delete_failed)
                        return@launch
                    }
                if (rec == null) {
                    // The row is already gone (deleted from Home, a recovery worker): the user
                    // confirmed a delete, so still leave the now-dead screen instead of silently
                    // doing nothing.
                    onDeleted()
                    return@launch
                }
                if (playbackController.state.value.recordingId == id) {
                    playbackController.stop()
                }
                // Same contract as the Home delete (PIPE-07): a still-processing recording must
                // not keep transcribing against a deleted row and file.
                transcriptionRecovery.cancelProcessing(id)
                try {
                    isDeleting = true
                    repository.delete(rec)
                    withContext(Dispatchers.IO) {
                        try {
                            val file = File(rec.audioPath)
                            if (file.exists() && !file.delete()) {
                                Log.w("ProcessingStudioVM", "Failed to delete audio file: ${rec.audioPath}")
                            }
                            // The oversized-draft side file holds the full transcript text; it
                            // must not outlive the recording it belongs to.
                            draftFileMutex.withLock { oversizedDraftFile()?.delete() }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.w("ProcessingStudioVM", "Error deleting audio file: ${rec.audioPath}", e)
                        }
                    }
                    onDeleted()
                } catch (e: Exception) {
                    isDeleting = false
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e("ProcessingStudioVM", "Failed to delete recording: $id", e)
                    _message.value = context.getString(CoreUiR.string.rec_msg_delete_failed)
                }
            }
        }

        fun shareAudio(context: Context) {
            viewModelScope.launch {
                val state = _uiState.value
                val path = state.audioPath
                if (!state.isAudioReady) return@launch
                val file = File(path)
                val exists = withContext(Dispatchers.IO) { file.exists() }
                if (!exists) {
                    _message.value = context.getString(CoreUiR.string.rec_msg_audio_missing)
                    return@launch
                }
                try {
                    context.startActivity(
                        ProcessingStudioShare.chooserIntent(
                            ProcessingStudioShare.audioShareIntent(context, file, state.title),
                            context.getString(CoreUiR.string.rec_share_audio),
                        ),
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // I18N-05: exception messages are developer diagnostics; keep them in logs.
                    Log.e("ProcessingStudioVM", "Share failed", e)
                    _message.value = context.getString(CoreUiR.string.rec_msg_share_failed)
                }
            }
        }

        fun shareTranscript(context: Context) {
            viewModelScope.launch {
                val state = _uiState.value
                try {
                    // Building the share text and (for oversized transcripts) writing the share
                    // file are proportional to transcript length; keep them off the main thread.
                    val intent =
                        withContext(Dispatchers.IO) {
                            val text =
                                ProcessingStudioShare.buildTranscriptShareText(
                                    title = state.title,
                                    summary = state.summary,
                                    transcriptText = state.effectiveTranscriptText,
                                )
                            ProcessingStudioShare.transcriptShareIntent(context, state.title, text)
                        }
                    context.startActivity(
                        ProcessingStudioShare.chooserIntent(
                            intent,
                            context.getString(CoreUiR.string.rec_share_transcript),
                        ),
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // I18N-05: exception messages are developer diagnostics; keep them in logs.
                    Log.e("ProcessingStudioVM", "Share failed", e)
                    _message.value = context.getString(CoreUiR.string.rec_msg_share_failed)
                }
            }
        }

        fun shareStructuredOutcome(
            context: Context,
            item: StructuredOutcomeItemUi,
        ) {
            val state = _uiState.value
            val text =
                ProcessingStudioShare.buildStructuredOutcomeShareText(
                    title = state.title,
                    groupLabel = item.group.displayLabel(context),
                    itemText = item.text,
                )
            try {
                context.startActivity(
                    ProcessingStudioShare.chooserIntent(
                        ProcessingStudioShare.structuredOutcomeShareIntent(
                            context = context,
                            title = state.title,
                            groupLabel = item.group.displayLabel(context),
                            text = text,
                        ),
                        context.getString(R.string.rec_share_item_chooser),
                    ),
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // I18N-05: exception messages are developer diagnostics; keep them in logs.
                Log.e("ProcessingStudioVM", "Share failed", e)
                _message.value = context.getString(CoreUiR.string.rec_msg_share_failed)
            }
        }

        fun shareBoth(context: Context) {
            viewModelScope.launch {
                val state = _uiState.value
                val path = state.audioPath
                if (!state.isAudioReady) return@launch
                val file = File(path)
                val exists = withContext(Dispatchers.IO) { file.exists() }
                if (!exists) {
                    _message.value = context.getString(CoreUiR.string.rec_msg_audio_missing)
                    return@launch
                }
                try {
                    val intent =
                        withContext(Dispatchers.IO) {
                            val text =
                                ProcessingStudioShare.buildTranscriptShareText(
                                    title = state.title,
                                    summary = state.summary,
                                    transcriptText = state.effectiveTranscriptText,
                                )
                            ProcessingStudioShare.audioAndTranscriptShareIntent(
                                context = context,
                                audioFile = file,
                                title = state.title,
                                text = text,
                            )
                        }
                    context.startActivity(
                        ProcessingStudioShare.chooserIntent(
                            intent,
                            context.getString(CoreUiR.string.rec_share_recording_chooser),
                        ),
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // I18N-05: exception messages are developer diagnostics; keep them in logs.
                    Log.e("ProcessingStudioVM", "Share failed", e)
                    _message.value = context.getString(CoreUiR.string.rec_msg_share_failed)
                }
            }
        }

        override fun onCleared() {
            cancelRecordingObservation()
            super.onCleared()
        }

        // Same status set the Screen uses to disable edit/retranscribe, so the two can't drift.
        private fun isTranscriptBusy(status: RecordingStatus?): Boolean = status.transcriptionProgressKind() != null

        /**
         * Recomputes the karaoke highlight index in the hoisted [playbackTick] flow whenever the
         * transcript content or interaction mode changes (edit/selection disables highlighting).
         * Returns [state] unchanged so call sites keep their `_uiState.value = ...` shape.
         */
        private fun refreshTranscriptInteractionState(state: ProcessingStudioState): ProcessingStudioState {
            val currentTick = _playbackTick.value
            val nextActiveIndex = activeSegmentIndexFor(state, currentTick.currentPositionMs)
            if (currentTick.activeTranscriptSegmentIndex != nextActiveIndex) {
                _playbackTick.value = currentTick.copy(activeTranscriptSegmentIndex = nextActiveIndex)
            }
            return state
        }

        private suspend fun buildTimedTranscript(
            rawText: String,
            timings: List<dev.chirpboard.app.data.entity.TranscriptTiming>,
        ): BuiltTranscript {
            val inputs = TranscriptBuildInputs(rawText = rawText, timings = timings)
            cachedTranscriptInputs?.let { cached ->
                if (cached == inputs) return cachedTranscript
            }
            // Rendered text is joined off the main thread too: on a long dictation the
            // join over thousands of segments is as measurable as the build itself.
            val built =
                withContext(transcriptBuildDispatcher) {
                    val transcript = buildProcessingStudioTranscript(rawText = rawText, timings = timings)
                    BuiltTranscript(transcript, transcript.renderedText())
                }
            cachedTranscriptInputs = inputs
            cachedTranscript = built
            return built
        }
    }


private data class StudioRecordingLoadState(
    val recording: dev.chirpboard.app.data.entity.Recording?,
    val transcript: Transcript?,
    val timings: List<dev.chirpboard.app.data.entity.TranscriptTiming>,
    val structuredOutcomeSnapshot: dev.chirpboard.app.data.model.StructuredOutcomeSnapshot?,
    val isStructuredOutcomeGenerating: Boolean,
    val recordingLoadFailed: Boolean,
 )

private data class RecoveryDiagnosticsRefreshKey(
    val recordingId: UUID,
    val status: RecordingStatus?,
    val errorMessage: String?,
)

private data class RecoveryDiagnosticsResult(
    val key: RecoveryDiagnosticsRefreshKey,
    val diagnostics: RecoveryDiagnostics,
)

private data class PlaybackRevealKey(
    val recordingId: UUID,
    val audioPath: String,
)

private data class BuiltTranscript(
    val transcript: ProcessingStudioTranscript,
    val renderedText: String,
)

private val EMPTY_BUILT_TRANSCRIPT = BuiltTranscript(ProcessingStudioTranscript.Empty, "")

private data class TranscriptBuildInputs(
    val rawText: String,
    val timings: List<dev.chirpboard.app.data.entity.TranscriptTiming>,
)

/** LIF-05: SavedStateHandle keys for mid-edit state that must survive process death. */
private const val STUDIO_SKIP_MS = 10_000L
private const val KEY_IS_EDITING_TRANSCRIPT = "studio.isEditingTranscript"
private const val KEY_TRANSCRIPT_DRAFT = "studio.transcriptDraft"
private const val KEY_TRANSCRIPT_DRAFT_IN_FILE = "studio.transcriptDraftInFile"
private const val KEY_IS_EDITING_TITLE = "studio.isEditingTitle"
private const val KEY_EDITED_TITLE = "studio.editedTitle"
private const val KEY_IS_EDITING_NOTES = "studio.isEditingNotes"
private const val KEY_EDITED_NOTES = "studio.editedNotes"
private const val KEY_CHAT_DRAFT = "studio.chatDraft"

/**
 * Saved-state Bundles share a ~1MB binder budget; drafts beyond this length are not mirrored
 * (LIF-05) rather than risking a TransactionTooLargeException on every lifecycle save.
 */
private const val MAX_MIRRORED_DRAFT_CHARS = 100_000

/**
 * Debounce for persisting an oversized draft to its side file; long enough to coalesce
 * keystrokes, short enough that process death loses at most a moment of typing.
 */
private const val OVERSIZED_DRAFT_PERSIST_DEBOUNCE_MS = 750L

/** LIF-05: mid-edit state recovered from SavedStateHandle after process death. */
internal data class StudioDraftRestoration(
    val isEditingTranscript: Boolean,
    val transcriptDraft: String?,
    /** True when the draft outgrew the Bundle budget and lives in the side file instead. */
    val transcriptDraftInFile: Boolean = false,
    val isEditingTitle: Boolean,
    val editedTitle: String?,
    val isEditingNotes: Boolean = false,
    val editedNotes: String? = null,
    val chatDraft: String?,
)

internal fun ProcessingStudioState.applyDraftRestoration(restoration: StudioDraftRestoration): ProcessingStudioState {
    var state = this
    if (restoration.isEditingTranscript) {
        // A missing draft (side-file write never landed before process death) still reopens
        // edit mode on the saved transcript instead of silently dropping back to read mode.
        state =
            state.copy(
                isEditingTranscript = true,
                transcriptDraft = restoration.transcriptDraft ?: state.effectiveTranscriptText,
            )
    }
    if (restoration.isEditingTitle) {
        state =
            state.copy(
                isEditingTitle = true,
                editedTitle = restoration.editedTitle ?: state.title,
            )
    }
    if (restoration.isEditingNotes) {
        state =
            state.copy(
                isEditingNotes = true,
                editedNotes = restoration.editedNotes ?: state.notes,
            )
    }
    if (!restoration.chatDraft.isNullOrEmpty()) {
        state = state.copy(chatDraft = restoration.chatDraft)
    }
    return state
}

/** PLH-7: only short phrases are sensible Word Replacements (and fit a snackbar offer). */
private const val MAX_PROMOTION_WORDS = 3
private const val MAX_PROMOTION_CHARS = 60

internal fun isPromotableAsWordReplacement(promotion: TranscriptCorrectionPromotion): Boolean {
    fun sideFits(side: String): Boolean =
        side.length <= MAX_PROMOTION_CHARS && side.split(' ').size <= MAX_PROMOTION_WORDS
    return sideFits(promotion.original) && sideFits(promotion.replacement)
}

/**
 * I18N-05: classify an AI-path failure into short actionable copy. Raw exception messages are
 * developer diagnostics and stay in logs.
 */
internal fun aiFailureDisplayMessage(
    context: Context,
    error: Throwable?,
): String =
    if (error is IOException) {
        context.getString(R.string.rec_ai_failure_network)
    } else {
        context.getString(R.string.rec_ai_failure_generic)
    }

private fun StructuredOutcomeGroup.displayLabel(context: Context): String =
    when (this) {
        StructuredOutcomeGroup.TASKS -> context.getString(R.string.rec_structured_group_tasks)
        StructuredOutcomeGroup.DECISIONS -> context.getString(R.string.rec_structured_group_decisions)
        StructuredOutcomeGroup.FOLLOW_UPS -> context.getString(R.string.rec_structured_group_follow_ups)
    }
