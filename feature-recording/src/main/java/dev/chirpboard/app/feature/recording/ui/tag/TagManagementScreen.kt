package dev.chirpboard.app.feature.recording.ui.tag

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.core.ui.components.ChirpPrimaryFab
import dev.chirpboard.app.core.ui.components.ChirpSettingsDetailScaffold
import dev.chirpboard.app.core.ui.components.EmptyState
import dev.chirpboard.app.core.ui.components.RepositoryErrorSnackbarEffect
import dev.chirpboard.app.core.ui.R as CoreR
import dev.chirpboard.app.core.ui.motion.animatePushDownLayout
import dev.chirpboard.app.core.ui.theme.ChirpSpacing
import dev.chirpboard.app.feature.recording.R

/**
 * Full screen for managing all tags.
 */
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
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val pendingUndo by viewModel.pendingUndo.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    RepositoryErrorSnackbarEffect(
        errorMessage = errorMessage,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
    )

    // PROP-11: surface an Undo snackbar for the most recent swipe-delete. The message is honest
    // that re-creating the tag does not restore the recordings it had been applied to.
    val undoLabel = stringResource(R.string.rec_undo)
    LaunchedEffect(pendingUndo) {
        val deleted = pendingUndo ?: return@LaunchedEffect
        val result =
            snackbarHostState.showSnackbar(
                message = context.getString(R.string.rec_tag_deleted, deleted.name),
                actionLabel = undoLabel,
                duration = SnackbarDuration.Long,
            )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoDelete()
        } else {
            viewModel.clearPendingUndo()
        }
    }

    // LIF-12: open editors survive rotation/process death; the edited tag is keyed by id and
    // re-resolved from the live list so the dialog also closes if the tag is deleted elsewhere.
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var editingTagId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingTag = remember(editingTagId, tags) { editingTagId?.let { id -> tags.orEmpty().firstOrNull { it.id.toString() == id } } }

    ChirpSettingsDetailScaffold(
        title = stringResource(R.string.rec_tags),
        onNavigateBack = onNavigateBack,
        snackbarHostState = snackbarHostState,
        floatingActionButton = {
            ChirpPrimaryFab(onClick = { showCreateDialog = true }) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.desc_add_tag),
                )
            }
        },
    ) { paddingValues ->
        AnimatedContent(
            modifier = Modifier.animatePushDownLayout(),
            // Three states: null = first load still running (render nothing, no flicker),
            // true = genuinely empty, false = list. Error emissions never reach here.
            targetState = tags?.isEmpty(),
            transitionSpec = {
                fadeIn(tween(200, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(200, easing = FastOutSlowInEasing))
            },
            label = "tags_content",
        ) { isEmpty ->
            if (isEmpty == null) {
                Box(modifier = Modifier.fillMaxSize().padding(paddingValues))
            } else if (isEmpty) {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.Label,
                    title = stringResource(R.string.rec_no_tags_yet),
                    description = stringResource(R.string.rec_empty_tags_description),
                    modifier = Modifier.padding(paddingValues),
                )
            } else {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                    verticalArrangement = Arrangement.spacedBy(ChirpSpacing.Small),
                    contentPadding = PaddingValues(bottom = ChirpSpacing.MiniPlayerClearance),
                ) {
                    items(
                        items = tags.orEmpty(),
                        key = { it.id },
                        contentType = { "tag" },
                    ) { tag ->
                        SwipeableTagItem(
                            tagItem = TagItemUiState(tag),
                            onEdit = { editingTagId = tag.id.toString() },
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
            onDismiss = { editingTagId = null },
            onSave = { name, color ->
                viewModel.updateTag(tag.copy(name = name, color = color))
                editingTagId = null
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

    // Reset state after a recycled slot rebinds so a half-swiped row never flickers in.
    LaunchedEffect(tagItem.tag.id) {
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
                label = "dismiss_background",
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
            tag.color?.let { parseTagColor(it, defaultColor) } ?: defaultColor
        }
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val editTagDescription = stringResource(R.string.desc_edit_tag)

    // PROP-12: the whole row is the primary tap target (opens the rename/recolor editor). The
    // trailing pencil is now a decorative affordance — the row keeps the tag name as its label and
    // describes the click action via onClickLabel, so TalkBack announces the name plus "Edit tag".
    ListItem(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .clickable(
                    onClickLabel = editTagDescription,
                    role = Role.Button,
                    onClick = onEdit,
                ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                text = tag.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Box(
                modifier =
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(tagColor)
                        .border(1.dp, outlineColor, CircleShape),
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    )
}
