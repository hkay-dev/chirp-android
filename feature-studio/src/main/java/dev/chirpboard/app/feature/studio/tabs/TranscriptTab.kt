package dev.chirpboard.app.feature.studio.tabs

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.feature.studio.ProcessingStudioTranscript
import dev.chirpboard.app.feature.studio.R
import dev.chirpboard.app.feature.studio.TranscriptSegment

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
    onSegmentClicked: ((Long) -> Unit)?,
    onTranscriptDraftChange: (String) -> Unit,
    onCopyTranscript: () -> Unit,
    onCopyOriginal: () -> Unit,
    onCopyEnhanced: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    val isProcessing = status.transcriptionProgressKind() != null
    val progressCopy = status.transcriptionProgressCopy()
    val progressKind = status.transcriptionProgressKind()
    val hasTranscriptContent = transcript != ProcessingStudioTranscript.Empty
    val showTranscriptChrome = hasTranscriptContent && !isEditingTranscript && !isProcessing
    val showEmptyCompleted =
        transcript == ProcessingStudioTranscript.Empty &&
            status == RecordingStatus.COMPLETED &&
            !isProcessing

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AnimatedVisibility(
            visible = showTranscriptChrome,
            enter = progressEnterTransition,
            exit = progressExitTransition,
        ) {
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

        AnimatedVisibility(
            visible = hasManualCorrection && !isEditingTranscript && showTranscriptChrome,
            enter = progressEnterTransition,
            exit = progressExitTransition,
        ) {
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

        Box(modifier = Modifier.weight(1f, fill = true).fillMaxWidth()) {
            when {
                isProcessing && !isEditingTranscript -> {
                    if (progressCopy != null && progressKind != null) {
                        TranscriptionProgressPanel(
                            copy = progressCopy,
                            kind = progressKind,
                        )
                    } else {
                        TranscriptProcessingSkeleton()
                    }
                }

                showTranscriptChrome -> {
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
            }
        }
    }
}

@Composable
private fun TranscriptProcessingSkeleton() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 24.dp),
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
                    imageVector = Icons.Outlined.ContentCopy,
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
                    imageVector = Icons.Filled.AutoAwesome,
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
                    imageVector = Icons.Outlined.ContentCopy,
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
