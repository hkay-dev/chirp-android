package dev.chirpboard.app.feature.recording.ui.replacement

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.core.ui.components.EmptyState
import dev.chirpboard.app.data.entity.WordReplacement
import dev.chirpboard.app.feature.recording.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordReplacementsScreen(
    viewModel: WordReplacementsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
) {
    val replacements by viewModel.replacements.collectAsStateWithLifecycle()

    var showEditorDialog by remember { mutableStateOf(false) }
    var editingReplacement by remember { mutableStateOf<WordReplacement?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rec_word_replacements)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.desc_back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingReplacement = null
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
                    modifier = Modifier.padding(paddingValues),
                )
            } else {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    items(
                        items = replacements,
                        key = { it.id },
                        contentType = { "replacement" },
                    ) { replacement ->
                        SwipeableReplacementItem(
                            replacement = replacement,
                            onToggleEnabled = { viewModel.toggleEnabled(replacement) },
                            onEdit = {
                                editingReplacement = replacement
                                showEditorDialog = true
                            },
                            onDelete = { viewModel.delete(replacement) },
                            modifier = Modifier.animateItem(),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    // Editor dialog
    if (showEditorDialog) {
        WordReplacementEditorDialog(
            replacement = editingReplacement,
            onDismiss = {
                showEditorDialog = false
                editingReplacement = null
            },
            onSave = { original, replacement, caseSensitive ->
                if (editingReplacement != null) {
                    viewModel.update(
                        editingReplacement!!.copy(
                            original = original,
                            replacement = replacement,
                            caseSensitive = caseSensitive,
                        ),
                    )
                } else {
                    viewModel.create(original, replacement, caseSensitive)
                }
                showEditorDialog = false
                editingReplacement = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableReplacementItem(
    replacement: WordReplacement,
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
                        .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.desc_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
    ) {
        ReplacementItemCard(
            replacement = replacement,
            onToggleEnabled = onToggleEnabled,
            onEdit = onEdit,
        )
    }
}

@Composable
private fun ReplacementItemCard(
    replacement: WordReplacement,
    onToggleEnabled: () -> Unit,
    onEdit: () -> Unit,
) {
    val fromTextColor by animateColorAsState(
        targetValue =
            if (replacement.enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            },
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "to_text_color",
    )

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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (!replacement.enabled) TextDecoration.LineThrough else TextDecoration.None,
                    color = fromTextColor,
                )
                Text(
                    text = " \u2192 ",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = replacement.replacement.ifEmpty { stringResource(R.string.rec_replacement_remove) },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = toTextColor,
                )
            }
        },
        supportingContent = if (replacement.caseSensitive) {
            {
                Text(
                    text = stringResource(R.string.rec_case_sensitive),
                    style = MaterialTheme.typography.labelSmall,
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
                    contentDescription = stringResource(R.string.desc_edit),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    )
}
