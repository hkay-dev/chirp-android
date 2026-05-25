package dev.chirpboard.app.feature.studio.tabs

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.chirpboard.app.core.R as CoreR
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.feature.llm.client.TranscriptPassageAction
import dev.chirpboard.app.feature.studio.R
import dev.chirpboard.app.feature.studio.ProcessingStudioTranscript
import dev.chirpboard.app.feature.studio.TranscriptSegment
import dev.chirpboard.app.feature.studio.TranscriptSelectionResult

@Composable
fun TranscriptTab(
    transcript: ProcessingStudioTranscript,
    renderedTranscriptText: String,
    effectiveTranscriptText: String,
    transcriptDraft: String,
    isEditingTranscript: Boolean,
    isSelectingTranscript: Boolean,
    selectedTranscriptPassage: String,
    transcriptSelectionActionInFlight: TranscriptPassageAction?,
    transcriptSelectionResult: TranscriptSelectionResult?,
    canEnterSelectionMode: Boolean,
    hasManualCorrection: Boolean,
    canPromoteManualCorrection: Boolean,
    activeSegmentIndex: Int,
    status: RecordingStatus?,
    onSegmentClicked: ((Long) -> Unit)?,
    onStartEditing: () -> Unit,
    onEnterSelectionMode: () -> Unit,
    onExitSelectionMode: () -> Unit,
    onTranscriptSelectionChange: (String) -> Unit,
    onRunTranscriptSelectionAction: (TranscriptPassageAction) -> Unit,
    onTranscriptDraftChange: (String) -> Unit,
    onCancelEditing: () -> Unit,
    onSaveCorrection: () -> Unit,
    onPromoteCorrection: () -> Unit,
    onRetranscribe: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    val progressCopy = status.transcriptionProgressCopy()
    val hasTranscriptContent = transcript != ProcessingStudioTranscript.Empty
    val showProgress = progressCopy != null && !isEditingTranscript && !isSelectingTranscript
    val showTranscriptChrome = hasTranscriptContent && !isEditingTranscript && !isSelectingTranscript
    val showEmptyCompleted =
        transcript == ProcessingStudioTranscript.Empty &&
            status == RecordingStatus.COMPLETED &&
            progressCopy == null
    val usesStudioInsets = showTranscriptChrome || isEditingTranscript || isSelectingTranscript
    val contentInset by animateDpAsState(
        targetValue = if (usesStudioInsets) 16.dp else 0.dp,
        animationSpec = studioLayoutMotionSpring,
        label = "transcript_content_inset",
    )
    val transcriptAlpha by animateFloatAsState(
        targetValue = if (showTranscriptChrome) 1f else 0f,
        animationSpec = studioContentAlphaTween,
        label = "transcript_body_alpha",
    )

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(contentInset),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showTranscriptChrome) {
            TranscriptActionRow(
                effectiveTranscriptText = effectiveTranscriptText,
                transcriptDraft = transcriptDraft,
                isEditingTranscript = isEditingTranscript,
                isSelectingTranscript = isSelectingTranscript,
                canEnterSelectionMode = canEnterSelectionMode,
                canPromoteManualCorrection = canPromoteManualCorrection,
                status = status,
                onStartEditing = onStartEditing,
                onEnterSelectionMode = onEnterSelectionMode,
                onExitSelectionMode = onExitSelectionMode,
                onCancelEditing = onCancelEditing,
                onSaveCorrection = onSaveCorrection,
                onPromoteCorrection = onPromoteCorrection,
                onRetranscribe = onRetranscribe,
                modifier =
                    Modifier
                        .graphicsLayer { alpha = transcriptAlpha }
                        .fillMaxWidth(),
            )
        }

        if (hasManualCorrection && !isEditingTranscript) {
            Surface(
                modifier =
                    Modifier
                        .graphicsLayer { alpha = transcriptAlpha }
                        .fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(
                    text = "Showing your saved manual correction.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }

        Box(modifier = Modifier.weight(1f, fill = true).fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (showProgress && progressCopy != null) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .then(if (!hasTranscriptContent) Modifier.weight(1f, fill = true) else Modifier),
                        contentAlignment = if (hasTranscriptContent) Alignment.TopStart else Alignment.Center,
                    ) {
                        MorphingTranscriptionProgress(
                            compact = hasTranscriptContent,
                            copy = progressCopy,
                        )
                    }
                }

                when {
                    showTranscriptChrome -> {
                        Box(
                            modifier =
                                Modifier
                                    .weight(1f, fill = true)
                                    .fillMaxWidth()
                                    .graphicsLayer { alpha = transcriptAlpha },
                        ) {
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

                    showEmptyCompleted -> {
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

                    isEditingTranscript -> {
                        OutlinedTextField(
                            value = transcriptDraft,
                            onValueChange = onTranscriptDraftChange,
                            modifier = Modifier.fillMaxSize(),
                            minLines = 12,
                        )
                    }

                    isSelectingTranscript -> {
                        TranscriptSelectionModeContent(
                            transcript = transcript,
                            renderedTranscriptText = renderedTranscriptText,
                            selectedTranscriptPassage = selectedTranscriptPassage,
                            actionInFlight = transcriptSelectionActionInFlight,
                            transcriptSelectionResult = transcriptSelectionResult,
                            onTranscriptSelectionChange = onTranscriptSelectionChange,
                            onRunTranscriptSelectionAction = onRunTranscriptSelectionAction,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptActionRow(
    effectiveTranscriptText: String,
    transcriptDraft: String,
    isEditingTranscript: Boolean,
    isSelectingTranscript: Boolean,
    canEnterSelectionMode: Boolean,
    canPromoteManualCorrection: Boolean,
    status: RecordingStatus?,
    onStartEditing: () -> Unit,
    onEnterSelectionMode: () -> Unit,
    onExitSelectionMode: () -> Unit,
    onCancelEditing: () -> Unit,
    onSaveCorrection: () -> Unit,
    onPromoteCorrection: () -> Unit,
    onRetranscribe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isBusy = status.transcriptionProgressCopy() != null
    val trimmedDraft = transcriptDraft.trim()
    val trimmedEffectiveText = effectiveTranscriptText.trim()

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isEditingTranscript) {
            TextButton(onClick = onCancelEditing) {
                Text(stringResource(CoreR.string.rec_cancel))
            }
            OutlinedButton(
                onClick = onSaveCorrection,
                enabled = trimmedDraft.isNotBlank() && trimmedDraft != trimmedEffectiveText,
            ) {
                Text(stringResource(CoreR.string.rec_save))
            }
        } else if (isSelectingTranscript) {
            TextButton(onClick = onExitSelectionMode) {
                Text(stringResource(R.string.rec_transcript_selection_done))
            }
        } else {
            OutlinedButton(
                onClick = onStartEditing,
                enabled = effectiveTranscriptText.isNotBlank() && !isBusy,
            ) {
                Text("Edit")
            }
            OutlinedButton(
                onClick = onEnterSelectionMode,
                enabled = canEnterSelectionMode,
            ) {
                Text(stringResource(R.string.rec_select_text))
            }
            if (canPromoteManualCorrection) {
                TextButton(onClick = onPromoteCorrection) {
                    Text("Promote rule")
                }
            }
            TextButton(
                onClick = onRetranscribe,
                enabled = effectiveTranscriptText.isNotBlank() && !isBusy,
            ) {
                Text(stringResource(R.string.rec_retranscribe))
            }
        }
    }
}

@Composable
private fun TranscriptSelectionModeContent(
    transcript: ProcessingStudioTranscript,
    renderedTranscriptText: String,
    selectedTranscriptPassage: String,
    actionInFlight: TranscriptPassageAction?,
    transcriptSelectionResult: TranscriptSelectionResult?,
    onTranscriptSelectionChange: (String) -> Unit,
    onRunTranscriptSelectionAction: (TranscriptPassageAction) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TranscriptSelectionPanel(
            selectedTranscriptPassage = selectedTranscriptPassage,
            actionInFlight = actionInFlight,
            transcriptSelectionResult = transcriptSelectionResult,
            onRunTranscriptSelectionAction = onRunTranscriptSelectionAction,
        )

        if (transcript is ProcessingStudioTranscript.Untimed) {
            WordTimingUnavailableNote()
        }

        SelectableTranscriptContent(
            text = renderedTranscriptText,
            onSelectionChange = onTranscriptSelectionChange,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun TranscriptSelectionPanel(
    selectedTranscriptPassage: String,
    actionInFlight: TranscriptPassageAction?,
    transcriptSelectionResult: TranscriptSelectionResult?,
    onRunTranscriptSelectionAction: (TranscriptPassageAction) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text =
                    if (selectedTranscriptPassage.isBlank()) {
                        stringResource(R.string.rec_transcript_selection_prompt)
                    } else {
                        selectedTranscriptPassage
                    },
                style = MaterialTheme.typography.bodyMedium,
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(TranscriptPassageAction.entries, key = { it.name }) { action ->
                    OutlinedButton(
                        onClick = { onRunTranscriptSelectionAction(action) },
                        enabled = selectedTranscriptPassage.isNotBlank() && actionInFlight == null,
                    ) {
                        Text(action.label)
                    }
                }
            }

            if (actionInFlight != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(
                        text = "${actionInFlight.label}...",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            transcriptSelectionResult?.let { result ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = result.action.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = result.text,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectableTranscriptContent(
    text: String,
    onSelectionChange: (String) -> Unit,
    modifier: Modifier,
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val fontSize = MaterialTheme.typography.bodyLarge.fontSize.value

    AndroidView(
        modifier = modifier,
        factory = { context ->
            SelectionAwareEditText(context).apply {
                setPadding(0, 0, 0, 0)
                gravity = Gravity.TOP or Gravity.START
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
                setBackgroundColor(Color.TRANSPARENT)
                updateSelectionCallback(onSelectionChange)
            }
        },
        update = { view ->
            view.updateSelectionCallback(onSelectionChange)
            view.setTextColor(textColor)
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
            if (view.text.toString() != text) {
                view.setText(text)
                view.setSelection(0)
                onSelectionChange("")
            }
        },
    )
}

@Composable
private fun WordTimingUnavailableNote() {
    Text(
        text = "Word-level timing isn't available for this transcript.",
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
    val activeColor = MaterialTheme.colorScheme.primary
    val defaultColor = MaterialTheme.colorScheme.onSurface
    return remember(chunk, chunkStartIndex, activeSegmentIndex, onSegmentClicked, activeColor, defaultColor) {
        buildAnnotatedString {
            chunk.forEachIndexed { index, segment ->
                val absoluteIndex = chunkStartIndex + index
                val isActive = absoluteIndex == activeSegmentIndex
                val segmentStyle =
                    if (isActive) {
                        SpanStyle(
                            color = activeColor,
                            fontWeight = FontWeight.SemiBold,
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

private class SelectionAwareEditText(
    context: Context,
) : EditText(context) {
    private var onSelectionChangedListener: ((String) -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isLongClickable = true
        isCursorVisible = false
        inputType = InputType.TYPE_NULL
        setTextIsSelectable(true)
        showSoftInputOnFocus = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        setHorizontallyScrolling(false)
    }

    fun updateSelectionCallback(listener: (String) -> Unit) {
        onSelectionChangedListener = listener
    }

    override fun onSelectionChanged(
        selStart: Int,
        selEnd: Int,
    ) {
        super.onSelectionChanged(selStart, selEnd)

        val selectedText =
            if (selStart >= 0 && selEnd > selStart) {
                text
                    ?.subSequence(selStart, selEnd)
                    ?.toString()
                    .orEmpty()
                    .trim()
            } else {
                ""
            }
        onSelectionChangedListener?.invoke(selectedText)
    }
}
