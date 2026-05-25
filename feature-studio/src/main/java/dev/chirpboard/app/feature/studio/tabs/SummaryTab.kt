package dev.chirpboard.app.feature.studio.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.R as CoreR
import dev.chirpboard.app.core.ui.components.CopyActionButton
import dev.chirpboard.app.core.ui.components.StudioOutlinedAction
import dev.chirpboard.app.feature.studio.R
import dev.chirpboard.app.feature.studio.StructuredOutcomeItemUi
import dev.chirpboard.app.feature.studio.StructuredOutcomeSectionState

@Composable
fun SummaryTab(
    summaryMarkdown: String,
    structuredOutcomeSection: StructuredOutcomeSectionState,
    onGenerateStructuredOutcomes: () -> Unit,
    onCopyStructuredOutcome: (StructuredOutcomeItemUi) -> Unit,
    onShareStructuredOutcome: (StructuredOutcomeItemUi) -> Unit,
    onAskAiAboutStructuredOutcome: (StructuredOutcomeItemUi) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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

                StudioOutlinedAction(
                    onClick = onGenerateStructuredOutcomes,
                    icon = Icons.Filled.AutoAwesome,
                    label = structuredOutcomeActionLabel(state),
                    enabled = state.canRunGeneration,
                )
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
                    StructuredOutcomeGeneratingProgress()
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
                    AnimatedVisibility(
                        visible = state.isGenerating,
                        enter = progressEnterTransition,
                        exit = progressExitTransition,
                    ) {
                        StructuredOutcomeGeneratingProgress()
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
private fun StructuredOutcomeGeneratingProgress() {
    MorphingTranscriptionProgress(
        compact = true,
        copy =
            TranscriptionProgressCopy(
                title = stringResource(R.string.rec_structured_generating_title),
                subtitle = stringResource(R.string.rec_structured_generating_subtitle),
            ),
        kind = null,
        leadingIcon = Icons.Filled.AutoAwesome,
        modifier = Modifier.fillMaxWidth(),
    )
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
                        CopyActionButton(
                            onClick = { onCopyStructuredOutcome(item) },
                            label = stringResource(CoreR.string.rec_copy),
                        )
                        StudioOutlinedAction(
                            onClick = { onShareStructuredOutcome(item) },
                            icon = Icons.Filled.Share,
                            label = stringResource(CoreR.string.rec_share),
                        )
                        StudioOutlinedAction(
                            onClick = { onAskAiAboutStructuredOutcome(item) },
                            icon = Icons.AutoMirrored.Filled.Chat,
                            label = stringResource(R.string.rec_ask_ai),
                        )
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
