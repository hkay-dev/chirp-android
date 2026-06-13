package dev.chirpboard.app.feature.recording.ui.replacement

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.core.ui.components.ChirpSettingsDetailScaffold
import dev.chirpboard.app.core.ui.components.ChirpPrimaryFab
import dev.chirpboard.app.core.ui.components.EmptyState
import dev.chirpboard.app.core.ui.components.RepositoryErrorSnackbarEffect
import dev.chirpboard.app.data.entity.WordReplacement
import dev.chirpboard.app.core.ui.R as CoreR
import dev.chirpboard.app.core.ui.motion.animatePushDownLayout
import dev.chirpboard.app.core.ui.theme.ChirpSpacing
import dev.chirpboard.app.feature.recording.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordReplacementsScreen(
    viewModel: WordReplacementsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
) {
    val replacements by viewModel.replacements.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    RepositoryErrorSnackbarEffect(
        errorMessage = errorMessage,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
    )

    // LIF-12: the open editor (and which replacement it edits) survives rotation/process death;
    // the entity is id-keyed and re-resolved from the live list.
    var showEditorDialog by rememberSaveable { mutableStateOf(false) }
    var editingReplacementId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingReplacement =
        remember(editingReplacementId, replacements) {
            editingReplacementId?.let { id -> replacements.firstOrNull { it.id.toString() == id } }
        }

    ChirpSettingsDetailScaffold(
        title = stringResource(R.string.rec_word_replacements),
        onNavigateBack = onNavigateBack,
        snackbarHostState = snackbarHostState,
        floatingActionButton = {
            ChirpPrimaryFab(
                onClick = {
                    editingReplacementId = null
                    showEditorDialog = true
                },
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.desc_add_replacement),
                )
            }
        },
    ) { paddingValues ->
        AnimatedContent(
            modifier = Modifier.animatePushDownLayout(),
            targetState = replacements.isEmpty(),
            transitionSpec = {
                fadeIn(tween(200, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(200, easing = FastOutSlowInEasing))
            },
            label = "replacements_content",
        ) { isEmpty ->
            if (isEmpty) {
                EmptyState(
                    icon = Icons.Default.SwapHoriz,
                    title = stringResource(R.string.rec_word_replacements_empty_title),
                    description = stringResource(R.string.rec_empty_replacements_description),
                    actionLabel = stringResource(R.string.rec_add_replacement_title),
                    onAction = {
                        editingReplacementId = null
                        showEditorDialog = true
                    },
                    modifier = Modifier.padding(paddingValues),
                )
            } else {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                    contentPadding = PaddingValues(bottom = ChirpSpacing.MiniPlayerClearance),
                ) {
                    itemsIndexed(
                        items = replacements,
                        key = { _, item -> item.id },
                        contentType = { _, _ -> "replacement" },
                    ) { index, replacement ->
                        SwipeableReplacementItem(
                            replacement = replacement,
                            showDivider = index < replacements.lastIndex,
                            onToggleEnabled = { viewModel.toggleEnabled(replacement) },
                            onEdit = {
                                editingReplacementId = replacement.id.toString()
                                showEditorDialog = true
                            },
                            onDelete = { viewModel.delete(replacement) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    // Editor dialog
    if (showEditorDialog) {
        val editTarget = editingReplacement
        WordReplacementEditorDialog(
            replacement = editTarget,
            onDismiss = {
                showEditorDialog = false
                editingReplacementId = null
            },
            onSave = { original, replacement, caseSensitive ->
                if (editTarget != null) {
                    viewModel.update(
                        editTarget.copy(
                            original = original,
                            replacement = replacement,
                            caseSensitive = caseSensitive,
                        ),
                    )
                } else {
                    viewModel.create(original, replacement, caseSensitive)
                }
                showEditorDialog = false
                editingReplacementId = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableReplacementItem(
    replacement: WordReplacement,
    showDivider: Boolean,
    onToggleEnabled: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissState =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { dismissValue ->
                if (dismissValue == SwipeToDismissBoxValue.EndToStart) {
                    onDelete()
                    true
                } else {
                    false
                }
            },
        )

    // Reset state after deletion animation
    LaunchedEffect(replacement.id) {
        dismissState.reset()
    }

    SwipeToDismissBox(
        modifier = modifier,
        state = dismissState,
        backgroundContent = {
            val backgroundColor by animateColorAsState(
                targetValue =
                    when (dismissState.targetValue) {
                        SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                        else -> Color.Transparent
                    },
                label = "background_color",
            )

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                        .padding(horizontal = ChirpSpacing.ScreenHorizontal),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(CoreR.string.desc_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
    ) {
        ReplacementItemCard(
            replacement = replacement,
            showDivider = showDivider,
            onToggleEnabled = onToggleEnabled,
            onEdit = onEdit,
        )
    }
}

@Composable
private fun ReplacementItemCard(
    replacement: WordReplacement,
    showDivider: Boolean,
    onToggleEnabled: () -> Unit,
    onEdit: () -> Unit,
) {
    val fromTextColor by animateColorAsState(
        targetValue =
            if (replacement.enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "from_text_color",
    )
    val toTextColor by animateColorAsState(
        targetValue =
            if (replacement.enabled) {
                if (replacement.replacement.isEmpty()) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "to_text_color",
    )

    // The card carries the surface so the indented divider participates in the swipe and the
    // insert/remove animations instead of bleeding through the delete background as a sibling.
    Column(
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
    ) {
        ListItem(
            modifier = Modifier.fillMaxWidth().clickable { onToggleEnabled() },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = replacement.original,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (!replacement.enabled) TextDecoration.LineThrough else TextDecoration.None,
                        color = fromTextColor,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = " → ",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = replacement.replacement.ifEmpty { stringResource(R.string.rec_replacement_remove) },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = toTextColor,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            },
            supportingContent = if (replacement.caseSensitive) {
                {
                    Text(
                        text = stringResource(R.string.rec_case_sensitive),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else null,
            leadingContent = {
                Switch(
                    checked = replacement.enabled,
                    onCheckedChange = null, // Handled by row click
                )
            },
            trailingContent = {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(CoreR.string.desc_edit),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        )
        if (showDivider) {
            HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
        }
    }
}
