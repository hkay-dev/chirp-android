package dev.chirpboard.app.feature.studio.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.R as CoreR
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.feature.studio.R
import dev.chirpboard.app.feature.studio.StructuredOutcomeGroup
import dev.chirpboard.app.feature.studio.StructuredOutcomeItemUi
import dev.chirpboard.app.feature.studio.StructuredOutcomeSectionState

@Composable
fun SummaryTab(
    summaryMarkdown: String,
    status: RecordingStatus?,
    structuredOutcomeSection: StructuredOutcomeSectionState,
    onGenerateStructuredOutcomes: () -> Unit,
    onCopyStructuredOutcome: (StructuredOutcomeItemUi) -> Unit,
    onShareStructuredOutcome: (StructuredOutcomeItemUi) -> Unit,
    onAskAiAboutStructuredOutcome: (StructuredOutcomeItemUi) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    val progressCopy = status.transcriptionProgressCopy()
    val showProgress = progressCopy != null
    val summaryAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (showProgress) 0f else 1f,
        animationSpec = studioContentAlphaTween,
        label = "summary_content_alpha",
    )

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = summaryAlpha },
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "summary_body") {
                SummaryBody(summaryMarkdown = summaryMarkdown)
            }

            item(key = "structured_outcomes") {
                StructuredOutcomeSection(
                    state = structuredOutcomeSection,
                    onGenerateStructuredOutcomes = onGenerateStructuredOutcomes,
                    onCopyStructuredOutcome = onCopyStructuredOutcome,
                    onShareStructuredOutcome = onShareStructuredOutcome,
                    onAskAiAboutStructuredOutcome = onAskAiAboutStructuredOutcome,
                )
            }
        }

        AnimatedVisibility(
            visible = showProgress && progressCopy != null,
            enter = progressEnterTransition,
            exit = progressExitTransition,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (progressCopy != null) {
                TranscriptionProgressPanel(
                    copy = progressCopy,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun SummaryBody(summaryMarkdown: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.rec_summary),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (summaryMarkdown.isBlank()) {
                Text(
                    text = stringResource(R.string.rec_structured_no_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = summaryMarkdown,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun StructuredOutcomeSection(
    state: StructuredOutcomeSectionState,
    onGenerateStructuredOutcomes: () -> Unit,
    onCopyStructuredOutcome: (StructuredOutcomeItemUi) -> Unit,
    onShareStructuredOutcome: (StructuredOutcomeItemUi) -> Unit,
    onAskAiAboutStructuredOutcome: (StructuredOutcomeItemUi) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.rec_structured_outcomes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                TextButton(
                    onClick = onGenerateStructuredOutcomes,
                    enabled = state.canRunGeneration,
                ) {
                    Text(text = structuredOutcomeActionLabel(state))
                }
            }

            when {
                !state.isVisible -> {
                    StructuredOutcomeInfo(
                        text = stringResource(R.string.rec_structured_unavailable),
                    )
                }

                !state.hasTranscriptText -> {
                    Text(
                        text = stringResource(R.string.rec_structured_no_transcript),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                state.isGenerating && !state.hasReadySnapshot -> {
                    StructuredOutcomeInfo(
                        text = stringResource(R.string.rec_structured_generating),
                    )
                }

                !state.hasReadySnapshot && state.failureMessage != null -> {
                    StructuredOutcomeInfo(
                        text = stringResource(R.string.rec_structured_failed_message, state.failureMessage),
                        isError = true,
                    )
                }

                !state.hasReadySnapshot -> {
                    StructuredOutcomeInfo(
                        text = stringResource(R.string.rec_structured_empty_body),
                    )
                }

                else -> {
                    if (state.isGenerating) {
                        StructuredOutcomeInfo(
                            text = stringResource(R.string.rec_structured_generating),
                        )
                    }
                    if (state.isStale) {
                        StructuredOutcomeInfo(
                            text = stringResource(R.string.rec_structured_stale),
                            isError = true,
                        )
                    }
                    if (state.failureMessage != null) {
                        StructuredOutcomeInfo(
                            text = stringResource(R.string.rec_structured_failed_message, state.failureMessage),
                            isError = true,
                        )
                    }
                    if (!state.hasAnyGroups) {
                        StructuredOutcomeInfo(
                            text = stringResource(R.string.rec_structured_no_items),
                        )
                    }
                    StructuredOutcomeGroupSection(
                        title = stringResource(R.string.rec_structured_group_tasks),
                        items = state.tasks,
                        onCopyStructuredOutcome = onCopyStructuredOutcome,
                        onShareStructuredOutcome = onShareStructuredOutcome,
                        onAskAiAboutStructuredOutcome = onAskAiAboutStructuredOutcome,
                    )
                    StructuredOutcomeGroupSection(
                        title = stringResource(R.string.rec_structured_group_decisions),
                        items = state.decisions,
                        onCopyStructuredOutcome = onCopyStructuredOutcome,
                        onShareStructuredOutcome = onShareStructuredOutcome,
                        onAskAiAboutStructuredOutcome = onAskAiAboutStructuredOutcome,
                    )
                    StructuredOutcomeGroupSection(
                        title = stringResource(R.string.rec_structured_group_follow_ups),
                        items = state.followUps,
                        onCopyStructuredOutcome = onCopyStructuredOutcome,
                        onShareStructuredOutcome = onShareStructuredOutcome,
                        onAskAiAboutStructuredOutcome = onAskAiAboutStructuredOutcome,
                    )
                }
            }
        }
    }
}

@Composable
private fun StructuredOutcomeInfo(
    text: String,
    isError: Boolean = false,
) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun StructuredOutcomeGroupSection(
    title: String,
    items: List<StructuredOutcomeItemUi>,
    onCopyStructuredOutcome: (StructuredOutcomeItemUi) -> Unit,
    onShareStructuredOutcome: (StructuredOutcomeItemUi) -> Unit,
    onAskAiAboutStructuredOutcome: (StructuredOutcomeItemUi) -> Unit,
) {
    if (items.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )

        items.forEach { item ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onCopyStructuredOutcome(item) }) {
                            Text(text = stringResource(CoreR.string.rec_copy))
                        }
                        TextButton(onClick = { onShareStructuredOutcome(item) }) {
                            Text(text = stringResource(CoreR.string.rec_share))
                        }
                        TextButton(onClick = { onAskAiAboutStructuredOutcome(item) }) {
                            Text(text = stringResource(R.string.rec_ask_ai))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun structuredOutcomeActionLabel(state: StructuredOutcomeSectionState): String =
    when {
        state.hasReadySnapshot -> stringResource(R.string.rec_structured_regenerate)
        state.failureMessage != null -> stringResource(R.string.rec_structured_try_again)
        else -> stringResource(R.string.rec_structured_generate)
    }