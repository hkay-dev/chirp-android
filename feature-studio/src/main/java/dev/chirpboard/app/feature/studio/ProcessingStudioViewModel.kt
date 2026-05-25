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
import dev.chirpboard.app.core.transcription.TranscriptionRecovery
import dev.chirpboard.app.core.ui.motion.ChirpMotion
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
        private var currentRecordingId: UUID? = null
        private var currentTranscript: Transcript? = null

        private val _message = MutableStateFlow<String?>(null)
        val message: StateFlow<String?> = _message.asStateFlow()

        fun clearMessage() {
            _message.value = null
        }

        private val _uiState = MutableStateFlow(ProcessingStudioState())
        val uiState: StateFlow<ProcessingStudioState> = _uiState.asStateFlow()

        private val structuredOutcomeGenerationInFlight = MutableStateFlow(false)

        val playbackState: StateFlow<dev.chirpboard.app.core.playback.RecordingPlaybackState> = playbackController.state

        init {
            val recordingIdStr = savedStateHandle.get<String>("recordingId")
            if (!recordingIdStr.isNullOrEmpty() && recordingIdStr != "-1") {
                try {
                    loadRecording(UUID.fromString(recordingIdStr))
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // Invalid UUID
                }
            }

            viewModelScope.launch {
                playbackController.state.collect { playback ->
                    val screenRecordingId = currentRecordingId ?: return@collect
                    val current = _uiState.value
                    if (playback.recordingId == screenRecordingId) {
                        updatePlaybackPosition(playback.positionMs)
                        _uiState.value =
                            current.copy(
                                isPlaying = playback.isPlaying,
                                currentPositionMs = playback.positionMs,
                                durationMs =
                                    if (playback.durationMs > 0) {
                                        playback.durationMs
                                    } else {
                                        current.durationMs
                                    },
                            )
                    } else if (playback.isPlaying && playback.recordingId != screenRecordingId) {
                        _uiState.value = current.copy(isPlaying = false)
                    }
                }
            }
        }

        private fun loadRecording(id: UUID) {
            viewModelScope.launch {
                currentRecordingId = id
                currentTranscript = null

                _uiState.value = ProcessingStudioState(isLoading = true)
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
                }.collectLatest { loadState ->
                    val recording = loadState.recording
                    val transcript = loadState.transcript
                    val timings = loadState.timings
                    val structuredOutcomeSnapshot = loadState.structuredOutcomeSnapshot
                    val isStructuredOutcomeGenerating = loadState.isStructuredOutcomeGenerating
                    if (recording != null) {
                        val currentState = _uiState.value
                        currentTranscript = transcript
                        val effectiveTranscriptText = transcript?.effectiveText.orEmpty()
                        val wasEditingTranscript = currentState.isEditingTranscript
                        val transcriptChanged = effectiveTranscriptText != currentState.effectiveTranscriptText
                        val isEditingTranscript = wasEditingTranscript && !transcriptChanged
                        val transcriptState =
                            buildProcessingStudioTranscript(
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
                                isLoading = false,
                                status = recording.status,
                                errorMessage = recording.errorMessage,
                                transcript = transcriptState,
                                renderedTranscriptText = renderedTranscriptText,
                                effectiveTranscriptText = effectiveTranscriptText,
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
                                audioPath = recording.audioPath,
                                source = recording.source,
                            )

                        if (renderedTranscriptChanged) {
                            nextState = nextState.exitTranscriptSelectionMode()
                        }

                        val shouldRefreshRecovery =
                            nextState.status != currentState.status ||
                                nextState.recoveryDiagnostics == null
                        val stateWithRecovery =
                            if (shouldRefreshRecovery) {
                                withContext(Dispatchers.IO) {
                                    refreshRecoveryState(nextState, recording.id, recording.status)
                                }
                            } else {
                                nextState
                            }

                        _uiState.value = refreshTranscriptInteractionState(stateWithRecovery)

                        scheduleDeferredStudioPlayback(
                            recordingId = recording.id,
                            title = recording.title,
                            audioPath = recording.audioPath,
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(isLoading = false)
                    }
                }
            }
        }

        private fun scheduleDeferredStudioPlayback(
            recordingId: UUID,
            title: String,
            audioPath: String,
        ) {
            viewModelScope.launch {
                delay(ChirpMotion.NAV_TRANSITION_MS.toLong())
                if (currentRecordingId != recordingId) return@launch
                playbackController.onStudioOpened(recordingId, title, audioPath)
            }
        }

        fun togglePlayPause() {
            val recordingId = currentRecordingId ?: return
            val screen = _uiState.value
            if (screen.audioPath.isBlank()) return
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
            val playback = playbackController.state.value
            if (playback.recordingId != recordingId) {
                playbackController.prepare(recordingId, screen.title, screen.audioPath)
            }
            playbackController.seekTo(positionMs)
            updatePlaybackPosition(positionMs)
        }

        fun skipForward() {
            playbackController.skipForward()
        }

        fun skipBackward() {
            playbackController.skipBackward()
        }

        private fun updatePlaybackPosition(positionMs: Long) {
            val current = _uiState.value
            val nextActiveIndex =
                if (current.canUseTranscriptInteractions()) {
                    findActiveTranscriptSegmentIndex(
                        transcript = current.transcript,
                        positionMs = positionMs,
                    )
                } else {
                    -1
                }
            if (current.currentPositionMs == positionMs && current.activeTranscriptSegmentIndex == nextActiveIndex) {
                return
            }
            _uiState.value =
                current.copy(
                    currentPositionMs = positionMs,
                    activeTranscriptSegmentIndex = nextActiveIndex,
                )
        }

        fun onWordClicked(timestamp: Long) {
            if (!_uiState.value.canUseTranscriptInteractions()) return
            seekTo(timestamp)
            val recordingId = currentRecordingId ?: return
            val screen = _uiState.value
            if (!playbackController.state.value.isPlaying) {
                playbackController.play(recordingId, screen.title, screen.audioPath)
            }
        }

        fun updateChatDraft(newText: String) {
            _uiState.value = _uiState.value.copy(chatDraft = newText)
        }

        fun onSendChatMessage(text: String) {
            val trimmedText = text.trim()
            if (trimmedText.isBlank()) return

            val userMsg = createStudioChatMessage(trimmedText, isFromUser = true)
            _uiState.value =
                _uiState.value.copy(
                    chatMessages = (_uiState.value.chatMessages + userMsg).toImmutableList(),
                    chatDraft = "",
                    isTyping = true,
                )

            viewModelScope.launch {
                val result =
                    completeStudioChatExchange(
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
                        repository.saveStructuredOutcomeFailure(
                            recordingId = recordingId,
                            sourceTranscriptRevision = transcriptRevision,
                            failureMessage = result.exceptionOrNull()?.message ?: "Couldn't generate structured outcomes",
                        )
                    }
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Log.e("ProcessingStudioVM", "Structured outcome generation failed", error)
                    repository.saveStructuredOutcomeFailure(
                        recordingId = recordingId,
                        sourceTranscriptRevision = transcriptRevision,
                        failureMessage = error.message ?: "Couldn't generate structured outcomes",
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
            _message.value = "Copied to clipboard"
        }

        fun startEditingTitle() {
            _uiState.value =
                _uiState.value.copy(
                    isEditingTitle = true,
                    editedTitle = _uiState.value.title,
                )
        }

        fun updateEditedTitle(newTitle: String) {
            _uiState.value = _uiState.value.copy(editedTitle = newTitle)
        }

        fun startEditingTranscript() {
            val state = _uiState.value
            if (state.effectiveTranscriptText.isBlank()) return
            if (state.isSelectingTranscript) {
                _message.value = "Exit transcript selection mode first"
                return
            }
            if (isTranscriptBusy(state.status)) {
                _message.value = "Transcript can't be edited while processing"
                return
            }

            _uiState.value = refreshTranscriptInteractionState(state.enterTranscriptEditMode())
        }

        fun updateTranscriptDraft(newText: String) {
            _uiState.value = _uiState.value.copy(transcriptDraft = newText)
        }

        fun cancelEditingTranscript() {
            _uiState.value = refreshTranscriptInteractionState(_uiState.value.exitTranscriptEditMode())
        }

        fun enterTranscriptSelectionMode() {
            val state = _uiState.value
            if (state.isEditingTranscript) {
                _message.value = "Save or cancel your transcript edit first"
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
                    _message.value = result.exceptionOrNull()?.message ?: "Couldn't run transcript tool"
                }
            }
        }

        fun saveTranscriptCorrection() {
            viewModelScope.launch {
                val recordingId = currentRecordingId ?: return@launch
                val transcript = currentTranscript ?: return@launch
                val correctedText = _uiState.value.transcriptDraft.trim()
                if (correctedText.isBlank()) {
                    _message.value = "Transcript can't be empty"
                    return@launch
                }

                val sourceText = transcript.effectiveText
                if (correctedText == sourceText) {
                    _uiState.value = refreshTranscriptInteractionState(_uiState.value.exitTranscriptEditMode())
                    return@launch
                }

                if (correctedText == transcript.pipelineText) {
                    repository.clearManualCorrection(recordingId)
                    _message.value = "Manual transcript correction cleared"
                } else {
                    repository.saveManualCorrection(
                        recordingId = recordingId,
                        correctedText = correctedText,
                        sourceText = sourceText,
                    )
                    _message.value = "Transcript correction saved"
                }

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
                    _message.value = "This correction can't be promoted"
                    return@launch
                }

                val promotion = analyzeTranscriptCorrectionPromotion(sourceText, correctedText)
                if (promotion == null) {
                    _message.value = "This correction can't be promoted"
                    return@launch
                }

                val existing =
                    wordReplacementRepository.getEquivalentReplacement(
                        original = promotion.original,
                        replacement = promotion.replacement,
                    )
                if (existing != null) {
                    _message.value = "Matching word replacement already exists"
                    return@launch
                }

                wordReplacementRepository.createReplacement(
                    original = promotion.original,
                    replacement = promotion.replacement,
                )
                _message.value = "Word replacement added"
            }
        }

        fun retranscribe() {
            if (_uiState.value.isEditingTranscript) {
                _message.value = "Save or cancel your transcript edit first"
                return
            }

            viewModelScope.launch {
                val recordingId = currentRecordingId ?: return@launch
                transcriptionRecovery.enqueue(recordingId)
                refreshRecoveryForCurrentRecording()
                _message.value = "Re-queued for transcription"
            }
        }

        fun recoverPendingTranscription() {
            viewModelScope.launch {
                val recordingId = currentRecordingId ?: return@launch
                val result = transcriptionRecovery.recoverPendingTranscription(recordingId)
                _message.value = result.toUserMessage("Pending transcription recovered")
                refreshRecoveryForCurrentRecording()
            }
        }

        fun recoverEnhancing() {
            viewModelScope.launch {
                val recordingId = currentRecordingId ?: return@launch
                val result = transcriptionRecovery.recoverEnhancing(recordingId)
                _message.value = result.toUserMessage("Enhancement recovery queued")
                refreshRecoveryForCurrentRecording()
            }
        }

        fun retranscribeFromEnhancing() {
            viewModelScope.launch {
                val recordingId = currentRecordingId ?: return@launch
                val result = transcriptionRecovery.retranscribeFromEnhancing(recordingId)
                _message.value = result.toUserMessage("Full retranscription queued")
                refreshRecoveryForCurrentRecording()
            }
        }

        fun retryTranscription() {
            viewModelScope.launch {
                val recordingId = currentRecordingId ?: return@launch
                val status = _uiState.value.status
                if (status == RecordingStatus.FAILED || status == RecordingStatus.COMPLETED) {
                    transcriptionRecovery.retry(recordingId)
                    _message.value = "Re-queued for transcription"
                    refreshRecoveryForCurrentRecording()
                }
            }
        }

        private suspend fun refreshRecoveryForCurrentRecording() {
            val recordingId = currentRecordingId ?: return
            val status = _uiState.value.status
            _uiState.value = refreshRecoveryState(_uiState.value, recordingId, status)
        }

        private suspend fun refreshRecoveryState(
            state: ProcessingStudioState,
            recordingId: UUID,
            status: RecordingStatus?,
        ): ProcessingStudioState {
            val diagnostics = transcriptionRecovery.getRecoveryDiagnostics(recordingId).toUiModel()
            return state.copy(
                recoveryDiagnostics = diagnostics,
                recoveryActions = computeTranscriptionRecoveryActions(status, diagnostics.ownership),
            )
        }

        fun cancelEditingTitle() {
            _uiState.value = _uiState.value.copy(isEditingTitle = false)
        }

        fun saveTitle() {
            viewModelScope.launch {
                val id = currentRecordingId ?: return@launch
                val trimmedTitle = _uiState.value.editedTitle.trim()
                if (trimmedTitle.isNotEmpty()) {
                    repository.updateTitle(id, trimmedTitle)
                    _uiState.value = _uiState.value.copy(title = trimmedTitle)
                }
                _uiState.value = _uiState.value.copy(isEditingTitle = false)
            }
        }

        fun deleteRecording(onDeleted: () -> Unit) {
            viewModelScope.launch {
                val id = currentRecordingId ?: return@launch
                val rec = repository.getRecording(id) ?: return@launch
                if (playbackController.state.value.recordingId == id) {
                    playbackController.stop()
                }
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
                    _message.value = "Failed to delete recording"
                }
            }
        }

        fun shareAudio(context: Context) {
            viewModelScope.launch {
                val state = _uiState.value
                val path = state.audioPath
                if (path.isEmpty()) return@launch
                val file = File(path)
                val exists = withContext(Dispatchers.IO) { file.exists() }
                if (!exists) {
                    _message.value = "Audio file not found"
                    return@launch
                }
                try {
                    context.startActivity(
                        ProcessingStudioShare.chooserIntent(
                            ProcessingStudioShare.audioShareIntent(context, file, state.title),
                            "Share audio",
                        ),
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _message.value = "Failed to share: ${e.message}"
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
                        "Share transcript",
                    ),
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _message.value = "Failed to share: ${e.message}"
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
                    groupLabel = item.group.displayLabel(),
                    itemText = item.text,
                )
            try {
                context.startActivity(
                    ProcessingStudioShare.chooserIntent(
                        ProcessingStudioShare.structuredOutcomeShareIntent(
                            title = state.title,
                            groupLabel = item.group.displayLabel(),
                            text = text,
                        ),
                        "Share item",
                    ),
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _message.value = "Failed to share: ${e.message}"
            }
        }

        fun shareBoth(context: Context) {
            viewModelScope.launch {
                val state = _uiState.value
                val path = state.audioPath
                if (path.isEmpty()) return@launch
                val file = File(path)
                val exists = withContext(Dispatchers.IO) { file.exists() }
                if (!exists) {
                    _message.value = "Audio file not found"
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
                            "Share recording",
                        ),
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _message.value = "Failed to share: ${e.message}"
                }
            }
        }

        override fun onCleared() {
            super.onCleared()
        }

        private fun isTranscriptBusy(status: RecordingStatus?): Boolean =
            status == RecordingStatus.PENDING_TRANSCRIPTION ||
                status == RecordingStatus.TRANSCRIBING ||
                status == RecordingStatus.ENHANCING ||
                status == RecordingStatus.PENDING_ENHANCEMENT

        private fun refreshTranscriptInteractionState(state: ProcessingStudioState): ProcessingStudioState =
            state.copy(
                activeTranscriptSegmentIndex =
                    if (state.canUseTranscriptInteractions()) {
                        findActiveTranscriptSegmentIndex(
                            transcript = state.transcript,
                            positionMs = state.currentPositionMs,
                        )
                    } else {
                        -1
                    },
            )
    }


private data class StudioRecordingLoadState(
    val recording: dev.chirpboard.app.data.entity.Recording?,
    val transcript: Transcript?,
    val timings: List<dev.chirpboard.app.data.entity.TranscriptTiming>,
    val structuredOutcomeSnapshot: dev.chirpboard.app.data.model.StructuredOutcomeSnapshot?,
    val isStructuredOutcomeGenerating: Boolean,
 )

private fun StructuredOutcomeGroup.displayLabel(): String =
    when (this) {
        StructuredOutcomeGroup.TASKS -> "Tasks"
        StructuredOutcomeGroup.DECISIONS -> "Decisions"
        StructuredOutcomeGroup.FOLLOW_UPS -> "Follow-ups"
    }