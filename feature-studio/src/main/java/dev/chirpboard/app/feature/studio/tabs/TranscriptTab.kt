package dev.chirpboard.app.feature.studio.tabs

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.feature.llm.client.TranscriptPassageAction
import dev.chirpboard.app.feature.studio.TranscriptSelectionResult
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.feature.studio.ProcessingStudioTranscript
import dev.chirpboard.app.feature.studio.R
import dev.chirpboard.app.feature.studio.TranscriptSegment
import dev.chirpboard.app.core.ui.components.transcriptionProgressKind
import dev.chirpboard.app.core.ui.motion.ChirpMotion
import dev.chirpboard.app.core.ui.motion.PushDownReveal
import dev.chirpboard.app.core.ui.motion.animatePushDownLayout

private const val ACTIVE_SEGMENT_BACKGROUND_ALPHA = 0.22f

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranscriptTab(
    transcript: ProcessingStudioTranscript,
    effectiveTranscriptText: String,
    rawTranscriptText: String,
    enhancedTranscriptText: String,
    llmProcessingEnabled: Boolean,
    transcriptDraft: String,
    isEditingTranscript: Boolean,
    hasManualCorrection: Boolean,
    activeSegmentIndex: Int,
    status: RecordingStatus?,
    isSelectingTranscript: Boolean,
    renderedTranscriptText: String,
    selectedTranscriptPassage: String,
    transcriptSelectionActionInFlight: TranscriptPassageAction?,
    transcriptSelectionResult: TranscriptSelectionResult?,
    onTranscriptSelectionChanged: (String) -> Unit,
    onRunTranscriptSelectionAction: (TranscriptPassageAction) -> Unit,
    onCopySelectionResult: (String) -> Unit,
    onStartTranscription: (() -> Unit)?,
    onSegmentClicked: ((Long) -> Unit)?,
    onTranscriptDraftChange: (String) -> Unit,
    onCopyTranscript: () -> Unit,
    onCopyOriginal: () -> Unit,
    onCopyEnhanced: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    val isProcessing = status.transcriptionProgressKind() != null
    val isFailed = status == RecordingStatus.FAILED
    val isAwaitingManual = status == RecordingStatus.AWAITING_MANUAL_TRANSCRIPTION
    val hasTranscriptContent = transcript != ProcessingStudioTranscript.Empty
    val showTranscriptChrome =
        hasTranscriptContent && !isEditingTranscript && !isSelectingTranscript && !isProcessing
    val showEmptyCompleted =
        transcript == ProcessingStudioTranscript.Empty &&
            status == RecordingStatus.COMPLETED &&
            !isProcessing

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(contentPadding)
                .animatePushDownLayout(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PushDownReveal(visible = showTranscriptChrome) {
            TranscriptCopyActions(
                llmProcessingEnabled = llmProcessingEnabled,
                rawTranscriptText = rawTranscriptText,
                enhancedTranscriptText = enhancedTranscriptText,
                effectiveTranscriptText = effectiveTranscriptText,
                enabled = true,
                onCopyTranscript = onCopyTranscript,
                onCopyOriginal = onCopyOriginal,
                onCopyEnhanced = onCopyEnhanced,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        PushDownReveal(visible = hasManualCorrection && showTranscriptChrome) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.rec_manual_correction_banner),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }

        val bodyMode =
            when {
                isFailed && !isEditingTranscript -> TranscriptBodyMode.Failed
                // PLH-6: passage selection replaces the karaoke body with a selectable text view
                // plus the three AI passage actions.
                isSelectingTranscript && !isEditingTranscript -> TranscriptBodyMode.Selecting
                // PLH-4: a deliberately skipped recording is not "processing"; it waits for an
                // explicit start instead of showing an endless skeleton.
                isAwaitingManual && !isEditingTranscript -> TranscriptBodyMode.AwaitingManual
                isProcessing && !isEditingTranscript -> TranscriptBodyMode.Processing
                showTranscriptChrome -> TranscriptBodyMode.Chrome
                showEmptyCompleted -> TranscriptBodyMode.EmptyCompleted
                isEditingTranscript -> TranscriptBodyMode.Editing
                else -> TranscriptBodyMode.Processing
            }

        Box(
            modifier =
                Modifier
                    .weight(1f, fill = true)
                    .fillMaxWidth()
                    .animatePushDownLayout(),
        ) {
            AnimatedContent(
                targetState = bodyMode,
                transitionSpec = { ChirpMotion.studioContentCrossfade },
                label = "transcript_body_mode",
            ) { mode ->
                when (mode) {
                    TranscriptBodyMode.Processing -> TranscriptProcessingSkeleton()

                    TranscriptBodyMode.Failed -> Unit

                    TranscriptBodyMode.Selecting ->
                        TranscriptSelectionContent(
                            transcriptText = renderedTranscriptText,
                            selectedPassage = selectedTranscriptPassage,
                            actionInFlight = transcriptSelectionActionInFlight,
                            result = transcriptSelectionResult,
                            onSelectionChanged = onTranscriptSelectionChanged,
                            onRunAction = onRunTranscriptSelectionAction,
                            onCopyResult = onCopySelectionResult,
                        )

                    TranscriptBodyMode.AwaitingManual ->
                        AwaitingManualTranscriptionContent(onStartTranscription = onStartTranscription)

                    TranscriptBodyMode.Chrome -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            when (transcript) {
                                ProcessingStudioTranscript.Empty -> Unit

                                is ProcessingStudioTranscript.Untimed -> {
                                    UntimedTranscriptContent(
                                        transcript = transcript,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }

                                is ProcessingStudioTranscript.Timed -> {
                                    TimedTranscriptContent(
                                        transcript = transcript,
                                        activeSegmentIndex = activeSegmentIndex,
                                        onSegmentClicked = onSegmentClicked,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    }

                    TranscriptBodyMode.EmptyCompleted -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.rec_no_transcript_available),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }

                    TranscriptBodyMode.Editing -> {
                        // A11Y: name the edit box so TalkBack says what is being edited.
                        val transcriptFieldDescription = stringResource(R.string.rec_transcript_field_desc)
                        OutlinedTextField(
                            value = transcriptDraft,
                            onValueChange = onTranscriptDraftChange,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .semantics { contentDescription = transcriptFieldDescription },
                            minLines = 12,
                        )
                    }
                }
            }
        }
    }
}

private enum class TranscriptBodyMode {
    Processing,
    Failed,
    Selecting,
    AwaitingManual,
    Chrome,
    EmptyCompleted,
    Editing,
}

@Composable
private fun TranscriptProcessingSkeleton() {
    // A11Y: announce the load instead of reading as an empty tab to TalkBack.
    val processingDescription = stringResource(R.string.rec_transcript_processing_desc)
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .semantics { contentDescription = processingDescription },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(6) { index ->
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth(fraction = if (index % 2 == 0) 1f else 0.72f)
                        .height(14.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {}
        }
    }
}

/**
 * PLH-6: passage-selection body — the transcript in a selectable read-only field, the three AI
 * passage actions, and the latest action result.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranscriptSelectionContent(
    transcriptText: String,
    selectedPassage: String,
    actionInFlight: TranscriptPassageAction?,
    result: TranscriptSelectionResult?,
    onSelectionChanged: (String) -> Unit,
    onRunAction: (TranscriptPassageAction) -> Unit,
    onCopyResult: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.rec_transcript_selection_prompt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TranscriptPassageAction.entries.forEach { action ->
                OutlinedButton(
                    onClick = { onRunAction(action) },
                    enabled = selectedPassage.isNotBlank() && actionInFlight == null,
                ) {
                    if (actionInFlight == action) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(passageActionLabel(action))
                }
            }
        }

        result?.let { selectionResult ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = passageActionLabel(selectionResult.action),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onCopyResult(selectionResult.text) }) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = stringResource(R.string.rec_copy),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Text(
                        text = selectionResult.text,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // A read-only BasicTextField is the one Compose surface that reports selection changes
        // (SelectionContainer never exposes its selection), which the passage actions need.
        var selectionValue by remember(transcriptText) { mutableStateOf(TextFieldValue(transcriptText)) }
        BasicTextField(
            value = selectionValue,
            onValueChange = { next ->
                selectionValue = next.copy(text = transcriptText)
                val selection = next.selection
                onSelectionChanged(
                    if (selection.collapsed) {
                        ""
                    } else {
                        transcriptText.substring(selection.min, selection.max)
                    },
                )
            },
            readOnly = true,
            textStyle =
                MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun passageActionLabel(action: TranscriptPassageAction): String =
    when (action) {
        TranscriptPassageAction.SUMMARIZE -> stringResource(R.string.rec_passage_summarize)
        TranscriptPassageAction.EXPLAIN -> stringResource(R.string.rec_passage_explain)
        TranscriptPassageAction.EXTRACT_ITEMS -> stringResource(R.string.rec_passage_extract_items)
    }

/**
 * PLH-4: body for AWAITING_MANUAL_TRANSCRIPTION — the recording was deliberately not queued
 * (profile Auto Transcribe off, or the user cancelled), so explain and offer an explicit start.
 */
@Composable
private fun AwaitingManualTranscriptionContent(onStartTranscription: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.rec_awaiting_transcription_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.rec_awaiting_transcription_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onStartTranscription != null) {
            FilledTonalButton(onClick = onStartTranscription) {
                Text(stringResource(R.string.rec_transcribe_now_studio))
            }
        }
    }
}

@Composable
private fun TranscriptCopyActions(
    llmProcessingEnabled: Boolean,
    rawTranscriptText: String,
    enhancedTranscriptText: String,
    effectiveTranscriptText: String,
    enabled: Boolean,
    onCopyTranscript: () -> Unit,
    onCopyOriginal: () -> Unit,
    onCopyEnhanced: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (llmProcessingEnabled) {
            OutlinedButton(
                onClick = onCopyOriginal,
                enabled = enabled && rawTranscriptText.isNotBlank(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.rec_copy_original))
            }
            OutlinedButton(
                onClick = onCopyEnhanced,
                enabled = enabled && enhancedTranscriptText.isNotBlank(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.rec_copy_enhanced))
            }
        } else {
            OutlinedButton(
                onClick = onCopyTranscript,
                enabled = enabled && effectiveTranscriptText.isNotBlank(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.rec_copy))
            }
        }
    }
}

@Composable
private fun WordTimingUnavailableNote() {
    Text(
        text = stringResource(R.string.rec_word_timing_unavailable),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun UntimedTranscriptContent(
    transcript: ProcessingStudioTranscript.Untimed,
    modifier: Modifier,
) {
    val textChunks =
        remember(transcript.text) {
            transcript.text
                .split("\\s+".toRegex())
                .filter { it.isNotBlank() }
                .chunked(100)
                .map { it.joinToString(" ") }
        }

    LazyColumn(modifier = modifier) {
        item {
            Box(modifier = Modifier.padding(bottom = 12.dp)) {
                WordTimingUnavailableNote()
            }
        }
        itemsIndexed(textChunks, key = { index, _ -> index }) { _, chunk ->
            Text(
                text = chunk,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun TimedTranscriptContent(
    transcript: ProcessingStudioTranscript.Timed,
    activeSegmentIndex: Int,
    onSegmentClicked: ((Long) -> Unit)?,
    modifier: Modifier,
) {
    val chunks = remember(transcript.segments) { transcript.segments.chunked(100) }
    val activeChunkIndex =
        remember(activeSegmentIndex) {
            if (activeSegmentIndex < 0) -1 else activeSegmentIndex / 100
        }

    LazyColumn(modifier = modifier) {
        itemsIndexed(chunks, key = { index, _ -> index }) { chunkIndex, chunk ->
            val chunkStartIndex = chunkIndex * 100
            val isActiveChunk = chunkIndex == activeChunkIndex
            val annotatedString =
                rememberTimedTranscriptChunk(
                    chunk = chunk,
                    chunkStartIndex = chunkStartIndex,
                    activeSegmentIndex = if (isActiveChunk) activeSegmentIndex else -1,
                    onSegmentClicked = onSegmentClicked,
                )

            Text(
                text = annotatedString,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun rememberTimedTranscriptChunk(
    chunk: List<TranscriptSegment>,
    chunkStartIndex: Int,
    activeSegmentIndex: Int,
    onSegmentClicked: ((Long) -> Unit)?,
): androidx.compose.ui.text.AnnotatedString {
    val activeColor = MaterialTheme.colorScheme.onPrimaryContainer
    val activeBackground = MaterialTheme.colorScheme.primary.copy(alpha = ACTIVE_SEGMENT_BACKGROUND_ALPHA)
    val defaultColor = MaterialTheme.colorScheme.onSurface
    return remember(chunk, chunkStartIndex, activeSegmentIndex, onSegmentClicked, activeColor, activeBackground, defaultColor) {
        buildAnnotatedString {
            chunk.forEachIndexed { index, segment ->
                val absoluteIndex = chunkStartIndex + index
                val isActive = absoluteIndex == activeSegmentIndex
                // Highlight the active karaoke word with color + background only. Changing
                // fontWeight widens glyphs and reflows the whole paragraph word-by-word during
                // playback (UI-15), so weight is held constant.
                val segmentStyle =
                    if (isActive) {
                        SpanStyle(
                            color = activeColor,
                            background = activeBackground,
                        )
                    } else {
                        SpanStyle(color = defaultColor)
                    }

                if (onSegmentClicked != null) {
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = "segment_${segment.startTimestampMs}_$absoluteIndex",
                            linkInteractionListener = { onSegmentClicked(segment.startTimestampMs) },
                        ),
                    ) {
                        withStyle(segmentStyle) {
                            append(segment.text)
                        }
                    }
                } else {
                    withStyle(segmentStyle) {
                        append(segment.text)
                    }
                }
                append(" ")
            }
        }
    }
}
