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
import java.io.IOException
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

        private var currentRecordingId: UUID? = null
        private var currentTranscript: Transcript? = null
        private var recordingObservationJob: Job? = null
        private var missingRecordingJob: Job? = null
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
        private var cachedTranscript: ProcessingStudioTranscript = ProcessingStudioTranscript.Empty

        private val structuredOutcomeGenerationInFlight = MutableStateFlow(false)

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
        private var pendingDraftRestoration: StudioDraftRestoration? = readDraftRestoration()

        private fun readDraftRestoration(): StudioDraftRestoration? {
            val isEditingTranscript = savedStateHandle.get<Boolean>(KEY_IS_EDITING_TRANSCRIPT) ?: false
            val transcriptDraft = savedStateHandle.get<String>(KEY_TRANSCRIPT_DRAFT)
            val isEditingTitle = savedStateHandle.get<Boolean>(KEY_IS_EDITING_TITLE) ?: false
            val editedTitle = savedStateHandle.get<String>(KEY_EDITED_TITLE)
            val isEditingNotes = savedStateHandle.get<Boolean>(KEY_IS_EDITING_NOTES) ?: false
            val editedNotes = savedStateHandle.get<String>(KEY_EDITED_NOTES)
            val chatDraft = savedStateHandle.get<String>(KEY_CHAT_DRAFT)
            if (!isEditingTranscript && !isEditingTitle && !isEditingNotes && chatDraft.isNullOrEmpty()) return null
            return StudioDraftRestoration(
                isEditingTranscript = isEditingTranscript,
                transcriptDraft = transcriptDraft,
                isEditingTitle = isEditingTitle,
                editedTitle = editedTitle,
                isEditingNotes = isEditingNotes,
                editedNotes = editedNotes,
                chatDraft = chatDraft,
            )
        }

        private fun mirrorTranscriptEditState(
            isEditing: Boolean,
            draft: String?,
        ) {
            savedStateHandle[KEY_IS_EDITING_TRANSCRIPT] = isEditing
            // Bundles have a hard size budget; very large drafts are not mirrored rather than
            // risking a TransactionTooLargeException on every lifecycle save.
            savedStateHandle[KEY_TRANSCRIPT_DRAFT] = draft?.takeIf { it.length <= MAX_MIRRORED_DRAFT_CHARS }
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

            viewModelScope.launch {
                llmPreferences.llmEnabled.collect { enabled ->
                    _uiState.value = _uiState.value.copy(llmProcessingEnabled = enabled)
                }
            }

            viewModelScope.launch {
                playbackController.state.collect { playback ->
                    val screenRecordingId = currentRecordingId ?: return@collect
                    val current = _uiState.value
                    if (playback.recordingId == screenRecordingId) {
                        // Single uiState write per tick: position + active segment are hoisted into
                        // the separate playbackTick flow, so a 10 Hz ticker no longer revs the
                        // screen-wide state object. isPlaying/duration change at transition
                        // frequency only and copy() dedups identical values.
                        val nextIsPlaying = playback.isPlaying
                        val nextDurationMs = if (playback.durationMs > 0) playback.durationMs else current.durationMs
                        if (current.isPlaying != nextIsPlaying || current.durationMs != nextDurationMs) {
                            _uiState.value = current.copy(isPlaying = nextIsPlaying, durationMs = nextDurationMs)
                        }
                        updatePlaybackPosition(playback.positionMs)
                    } else if (playback.isPlaying && playback.recordingId != screenRecordingId) {
                        if (current.isPlaying) {
                            _uiState.value = current.copy(isPlaying = false)
                        }
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
            playbackController.pauseIfDifferentRecording(id)
            recordingObservationJob =
                viewModelScope.launch {
                currentRecordingId = id
                currentTranscript = null
                cachedTranscriptInputs = null
                cachedTranscript = ProcessingStudioTranscript.Empty
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
                    listOfNotNull(
                        recordingState.errorMessage,
                        transcriptState.errorMessage,
                        timingsState.errorMessage,
                        snapshotState.errorMessage,
                    ).firstOrNull()?.let { _message.value = it }
                    StudioRecordingLoadState(
                        recording = recordingState.value,
                        transcript = transcriptState.value,
                        timings = timingsState.value,
                        structuredOutcomeSnapshot = snapshotState.value,
                        isStructuredOutcomeGenerating = isStructuredOutcomeGenerating,
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
                        val currentState = _uiState.value
                        currentTranscript = transcript
                        val effectiveTranscriptText = transcript?.effectiveText.orEmpty()
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
                        val transcriptState =
                            buildTimedTranscript(
                                rawText = effectiveTranscriptText,
                                timings = timings,
                            )
                        val renderedTranscriptText = transcriptState.renderedText()
                        val renderedTranscriptChanged = renderedTranscriptText != currentState.renderedTranscriptText
                        val promotionCandidate =
                            transcript?.manualCorrectionSourceText?.let { sourceText ->
                                transcript.manualCorrectionText?.let { correctedText ->
                                    analyzeTranscriptCorrectionPromotion(
                                        sourceText = sourceText,
                                        correctedText = correctedText,
                                    )
                                }
                            }

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
                                canPromoteManualCorrection = promotionCandidate != null,
                                summary = transcript?.summary ?: "",
                                structuredOutcomeSection =
                                    buildStructuredOutcomeSectionState(
                                        recordingStatus = recording.status,
                                        effectiveTranscriptText = effectiveTranscriptText,
                                        snapshot = structuredOutcomeSnapshot,
                                        isGenerating = isStructuredOutcomeGenerating,
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
                        if (sawRecording) {
                            markRecordingNotFound()
                        } else {
                            scheduleMissingRecordingCheck(id)
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
                    val stillMissing = repository.getRecording(id) == null
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
                playbackController.prepare(recordingId, screen.title, screen.audioPath)
            }
            playbackController.seekTo(positionMs)
            updatePlaybackPosition(positionMs)
        }

        fun skipForward() {
            if (!_uiState.value.isAudioReady) return
            playbackController.skipForward()
        }

        fun skipBackward() {
            if (!_uiState.value.isAudioReady) return
            playbackController.skipBackward()
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

            viewModelScope.launch {
                val result =
                    completeStudioChatExchange(
                        context = context,
                        llmClient = llmClient,
                        transcriptText = _uiState.value.effectiveTranscriptText,
                        messagesWithUser = _uiState.value.chatMessages,
                    )
                _uiState.value =
                    _uiState.value.copy(
                        chatMessages = result.messages,
                        isTyping = result.isTyping,
                    )
            }
        }

        fun generateStructuredOutcomes() {
            val state = _uiState.value
            val validationMessage =
                validateStructuredOutcomeGenerationRequest(
                    recordingStatus = state.status,
                    effectiveTranscriptText = state.effectiveTranscriptText,
                    hasApiKey = llmPreferences.hasApiKey(),
                    isGenerating = structuredOutcomeGenerationInFlight.value,
                )
            if (validationMessage != null) {
                _message.value = validationMessage
                return
            }

            val recordingId = currentRecordingId ?: return
            val transcriptText = state.effectiveTranscriptText
            val transcriptRevision = transcriptText.structuredOutcomeRevision()
            structuredOutcomeGenerationInFlight.value = true

            viewModelScope.launch {
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
            _uiState.value = _uiState.value.copy(chatDraft = buildStructuredOutcomeAskAiDraft(item))
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
            val state = _uiState.value
            val validationMessage = state.validateTranscriptSelectionActionRequest(hasApiKey = llmPreferences.hasApiKey())
            if (validationMessage != null) {
                _message.value = validationMessage
                return
            }

            val selection = state.selectedTranscriptPassage
            val renderedTranscriptText = state.renderedTranscriptText
            _uiState.value = state.startTranscriptSelectionAction(action)

            viewModelScope.launch {
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

        fun saveTranscriptCorrection() {
            viewModelScope.launch {
                val recordingId = currentRecordingId ?: return@launch
                val transcript = currentTranscript ?: return@launch
                val correctedText = _uiState.value.transcriptDraft.trim()
                if (correctedText.isBlank()) {
                    _message.value = context.getString(R.string.rec_msg_transcript_empty)
                    return@launch
                }

                val sourceText = transcript.effectiveText
                if (correctedText == sourceText) {
                    mirrorTranscriptEditState(isEditing = false, draft = null)
                    _uiState.value = refreshTranscriptInteractionState(_uiState.value.exitTranscriptEditMode())
                    return@launch
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
                    return@launch
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
        }

        fun promoteTranscriptCorrection() {
            viewModelScope.launch {
                val transcript = currentTranscript
                val sourceText = transcript?.manualCorrectionSourceText
                val correctedText = transcript?.manualCorrectionText
                if (sourceText.isNullOrBlank() || correctedText.isNullOrBlank()) {
                    _message.value = context.getString(R.string.rec_msg_promotion_unavailable)
                    return@launch
                }

                val promotion = analyzeTranscriptCorrectionPromotion(sourceText, correctedText)
                if (promotion == null) {
                    _message.value = context.getString(R.string.rec_msg_promotion_unavailable)
                    return@launch
                }

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

            viewModelScope.launch {
                val recordingId = currentRecordingId ?: return@launch
                val result = transcriptionRecovery.retranscribe(recordingId)
                refreshRecoveryForCurrentRecording()
                _message.value = result.toUserMessage(context, context.getString(CoreUiR.string.rec_msg_requeued_transcription))
            }
        }

        fun recoverPendingTranscription() {
            viewModelScope.launch {
                val recordingId = currentRecordingId ?: return@launch
                val result = transcriptionRecovery.recoverPendingTranscription(recordingId)
                _message.value = result.toUserMessage(context, context.getString(R.string.rec_msg_pending_recovered))
                refreshRecoveryForCurrentRecording()
            }
        }

        fun recoverEnhancing() {
            viewModelScope.launch {
                val recordingId = currentRecordingId ?: return@launch
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
            viewModelScope.launch {
                val recordingId = currentRecordingId ?: return@launch
                val result = transcriptionRecovery.retranscribeFromEnhancing(recordingId)
                _message.value = result.toUserMessage(context, context.getString(R.string.rec_msg_retranscription_queued))
                refreshRecoveryForCurrentRecording()
            }
        }

        fun retryTranscription() {
            viewModelScope.launch {
                val recordingId = currentRecordingId ?: return@launch
                val result = transcriptionRecovery.retry(recordingId)
                _message.value = result.toUserMessage(context, context.getString(CoreUiR.string.rec_msg_requeued_transcription))
                refreshRecoveryForCurrentRecording()
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
            applyRecoveryDiagnosticsResult(loadRecoveryDiagnostics(key))
        }

        private fun scheduleRecoveryDiagnosticsRefresh(key: RecoveryDiagnosticsRefreshKey) {
            if (lastScheduledRecoveryKey == key) return

            lastScheduledRecoveryKey = key
            recoveryDiagnosticsJob?.cancel()
            recoveryDiagnosticsJob =
                viewModelScope.launch {
                    applyRecoveryDiagnosticsResult(loadRecoveryDiagnostics(key))
                }
        }

        private suspend fun loadRecoveryDiagnostics(key: RecoveryDiagnosticsRefreshKey): RecoveryDiagnosticsResult {
            val diagnostics = transcriptionRecovery.getRecoveryDiagnostics(key.recordingId)
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
                if (trimmedTitle.isNotEmpty()) {
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
                }
                mirrorTitleEditState(isEditing = false, editedTitle = null)
                _uiState.value = _uiState.value.copy(isEditingTitle = false)
            }
        }

        fun deleteRecording(onDeleted: () -> Unit) {
            viewModelScope.launch {
                val id = currentRecordingId ?: return@launch
                val rec = repository.getRecording(id)
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
                    repository.delete(rec)
                    withContext(Dispatchers.IO) {
                        try {
                            val file = File(rec.audioPath)
                            if (file.exists() && !file.delete()) {
                                Log.w("ProcessingStudioVM", "Failed to delete audio file: ${rec.audioPath}")
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.w("ProcessingStudioVM", "Error deleting audio file: ${rec.audioPath}", e)
                        }
                    }
                    onDeleted()
                } catch (e: Exception) {
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
            val state = _uiState.value
            val text =
                ProcessingStudioShare.buildTranscriptShareText(
                    title = state.title,
                    summary = state.summary,
                    transcriptText = state.effectiveTranscriptText,
                )
            try {
                context.startActivity(
                    ProcessingStudioShare.chooserIntent(
                        ProcessingStudioShare.transcriptShareIntent(state.title, text),
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
                    val text =
                        ProcessingStudioShare.buildTranscriptShareText(
                            title = state.title,
                            summary = state.summary,
                            transcriptText = state.effectiveTranscriptText,
                        )
                    context.startActivity(
                        ProcessingStudioShare.chooserIntent(
                            ProcessingStudioShare.audioAndTranscriptShareIntent(
                                context = context,
                                audioFile = file,
                                title = state.title,
                                text = text,
                            ),
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

        private fun isTranscriptBusy(status: RecordingStatus?): Boolean =
            status == RecordingStatus.RECORDING ||
                status == RecordingStatus.PENDING_TRANSCRIPTION ||
                status == RecordingStatus.TRANSCRIBING ||
                status == RecordingStatus.ENHANCING ||
                status == RecordingStatus.PENDING_ENHANCEMENT

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
        ): ProcessingStudioTranscript {
            val inputs = TranscriptBuildInputs(rawText = rawText, timings = timings)
            cachedTranscriptInputs?.let { cached ->
                if (cached == inputs) return cachedTranscript
            }
            val built =
                withContext(transcriptBuildDispatcher) {
                    buildProcessingStudioTranscript(rawText = rawText, timings = timings)
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

private data class TranscriptBuildInputs(
    val rawText: String,
    val timings: List<dev.chirpboard.app.data.entity.TranscriptTiming>,
)

/** LIF-05: SavedStateHandle keys for mid-edit state that must survive process death. */
private const val KEY_IS_EDITING_TRANSCRIPT = "studio.isEditingTranscript"
private const val KEY_TRANSCRIPT_DRAFT = "studio.transcriptDraft"
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

/** LIF-05: mid-edit state recovered from SavedStateHandle after process death. */
internal data class StudioDraftRestoration(
    val isEditingTranscript: Boolean,
    val transcriptDraft: String?,
    val isEditingTitle: Boolean,
    val editedTitle: String?,
    val isEditingNotes: Boolean = false,
    val editedNotes: String? = null,
    val chatDraft: String?,
)

internal fun ProcessingStudioState.applyDraftRestoration(restoration: StudioDraftRestoration): ProcessingStudioState {
    var state = this
    if (restoration.isEditingTranscript && restoration.transcriptDraft != null) {
        state =
            state.copy(
                isEditingTranscript = true,
                transcriptDraft = restoration.transcriptDraft,
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
