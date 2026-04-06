package dev.chirpboard.app.feature.recording.ui.tag

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.core.ui.components.EmptyState
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.feature.recording.R

/**
 * Full screen for managing all tags.
 */
@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Stable
data class TagItemUiState(
    val tag: dev.chirpboard.app.data.entity.Tag,
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun TagManagementScreen(
    viewModel: TagsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
) {
    val tags by viewModel.tags.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    var editingTag by remember { mutableStateOf<Tag?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rec_tags)) },
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
                onClick = { showCreateDialog = true },
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.desc_add_tag),
                )
            }
        },
    ) { paddingValues ->
        AnimatedContent(
            targetState = tags.isEmpty(),
            transitionSpec = {
                fadeIn(tween(200, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(200, easing = FastOutSlowInEasing))
            },
            label = "tags_content",
        ) { isEmpty ->
            if (isEmpty) {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.Label,
                    title = "No tags yet",
                    description = "Create tags to organize your recordings",
                    modifier = Modifier.padding(paddingValues),
                )
            } else {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding =
                        androidx.compose.foundation.layout
                            .PaddingValues(16.dp),
                ) {
                    items(
                        items = tags,
                        key = { it.id },
                        contentType = { "tag" },
                    ) { tag ->
                        SwipeableTagItem(
                            tagItem = TagItemUiState(tag),
                            onEdit = { editingTag = tag },
                            onDelete = { viewModel.deleteTag(tag) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    // Create dialog
    if (showCreateDialog) {
        TagEditorDialog(
            tag = null,
            onDismiss = { showCreateDialog = false },
            onSave = { name, color ->
                viewModel.createTag(name, color)
                showCreateDialog = false
            },
        )
    }

    // Edit dialog
    editingTag?.let { tag ->
        TagEditorDialog(
            tag = tag,
            onDismiss = { editingTag = null },
            onSave = { name, color ->
                viewModel.updateTag(tag.copy(name = name, color = color))
                editingTag = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableTagItem(
    tagItem: TagItemUiState,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tag = tagItem.tag
    val dismissState =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value == SwipeToDismissBoxValue.EndToStart) {
                    onDelete()
                    true
                } else {
                    false
                }
            },
        )

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
                label = "dismiss_background",
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
    ) {
        TagItemCard(
            tagItem = tagItem,
            onEdit = onEdit,
        )
    }
}

@Composable
private fun TagItemCard(
    tagItem: TagItemUiState,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tag = tagItem.tag
    val defaultColor = MaterialTheme.colorScheme.primary
    val tagColor =
        remember(tag.color, defaultColor) {
            tag.color?.let { parseColor(it) } ?: defaultColor
        }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Color indicator
            Box(
                modifier =
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(tagColor),
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Tag name
            Text(
                text = tag.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )

            // Edit button
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.desc_edit_tag),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun parseColor(hexColor: String): Color =
    try {
        Color(android.graphics.Color.parseColor(hexColor))
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Color.Gray
    }
