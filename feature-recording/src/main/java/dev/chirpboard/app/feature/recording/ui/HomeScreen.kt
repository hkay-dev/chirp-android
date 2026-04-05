package dev.chirpboard.app.feature.recording.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.ui.components.StatsPillRow
import dev.chirpboard.app.core.util.formatAsDuration
import dev.chirpboard.app.core.util.formatRelative
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.model.RecordingStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onRecordingClick: (Recording) -> Unit,
    onRecordClick: () -> Unit,
    onSettingsClick: () -> Unit,
    isRecordEntryChecking: Boolean = false,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val displayItems by viewModel.displayItems.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val listFilter by viewModel.listFilter.collectAsStateWithLifecycle()
    val stuckCount by viewModel.stuckCount.collectAsStateWithLifecycle()
    val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    var searchActive by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // FAB expand/collapse based on scroll
    val fabExpanded by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 }
    }

    // Bottom sheet state
    var selectedItem by remember { mutableStateOf<RecordingDisplayItem?>(null) }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    // Show error messages
    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            viewModel.clearError()
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short,
            )
        }
    }

    LaunchedEffect(recordingState) {
        if (recordingState is RecordingState.Error) {
            val error = recordingState as RecordingState.Error
            viewModel.clearError()
            snackbarHostState.showSnackbar(
                message = error.message,
                duration = SnackbarDuration.Short,
            )
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                MediumTopAppBar(
                    title = {
                        // Animated title based on collapse state
                        val collapsed = scrollBehavior.state.collapsedFraction > 0.5f
                        Text(
                            text = if (collapsed) "Recordings" else "Your Recordings",
                            style =
                                if (collapsed) {
                                    MaterialTheme.typography.titleLarge
                                } else {
                                    MaterialTheme.typography.headlineMedium
                                },
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    actions = {
                        val collapsed = scrollBehavior.state.collapsedFraction > 0.5f
                        if (collapsed && !searchActive) {
                            IconButton(onClick = { searchActive = true }) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search",
                                )
                            }
                        }
                        IconButton(onClick = onSettingsClick) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                    colors =
                        TopAppBarDefaults.mediumTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            scrolledContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                )

                // Docked search bar (visible when expanded or active)
                val collapsed = scrollBehavior.state.collapsedFraction > 0.5f
                AnimatedVisibility(
                    visible = !collapsed || searchActive,
                ) {
                    DockedSearchBar(
                        inputField = {
                            SearchBarDefaults.InputField(
                                query = searchQuery,
                                onQueryChange = viewModel::onSearchQueryChange,
                                onSearch = { searchActive = false },
                                expanded = searchActive,
                                onExpandedChange = { searchActive = it },
                                placeholder = { Text("Search recordings") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = "Search",
                                    )
                                },
                                trailingIcon = {
                                    if (searchActive || searchQuery.isNotEmpty()) {
                                        IconButton(onClick = {
                                            viewModel.onSearchQueryChange("")
                                            searchActive = false
                                        }) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Clear search",
                                            )
                                        }
                                    }
                                },
                            )
                        },
                        expanded = searchActive,
                        onExpandedChange = { searchActive = it },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        // Search suggestions could go here
                    }
                }
            }
        },
        floatingActionButton = {
            BreathingExtendedFab(
                expanded = fabExpanded,
                isChecking = isRecordEntryChecking,
                onClick = onRecordClick,
            )
        },
        floatingActionButtonPosition = FabPosition.Center,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        AnimatedContent(
            targetState = displayItems.isEmpty() && searchQuery.isBlank(),
            transitionSpec = {
                fadeIn(tween(200, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(200, easing = FastOutSlowInEasing))
            },
            label = "home_content",
        ) { showEmpty ->
            if (showEmpty) {
                AnimatedEmptyState(
                    onRecordClick = onRecordClick,
                    isRecordEntryChecking = isRecordEntryChecking,
                    modifier = Modifier.padding(paddingValues),
                )
            } else {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                    state = listState,
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    // Stats pill row - show when not searching
                    if (searchQuery.isBlank() && stats.totalRecordings > 0) {
                        item(key = "stats", contentType = "stats") {
                            StatsPillRow(
                                recordingCount = stats.totalRecordings,
                                totalDurationMs = stats.totalDurationMs,
                                processingCount = stats.processingCount,
                                onProcessingClick = { viewModel.onProcessingClick() },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp)
                                        .animateItem(),
                            )
                        }

                        if (searchQuery.isBlank() && listFilter == ListFilterMode.PROCESSING && stuckCount > 0) {
                            item(key = "recover_stuck", contentType = "recover_stuck") {
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    FilledTonalButton(onClick = { viewModel.recoverAllStuck() }) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Recover stuck ($stuckCount)")
                                    }
                                }
                            }
                        }
                    }

                    // Search results count
                    if (searchQuery.isNotBlank()) {
                        item(key = "search_results", contentType = "search_results") {
                            Text(
                                text = "${displayItems.size} result${if (displayItems.size != 1) "s" else ""}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier =
                                    Modifier
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                        .animateItem(),
                            )
                        }
                    }

                    // Recording list items - smooth scrolling, no stagger delay
                    items(
                        items = displayItems,
                        key = { it.recording.id },
                        contentType = { "recording" },
                    ) { item ->
                        Column(modifier = Modifier.animateItem()) {
                            RecordingListItem(
                                item = item,
                                onClick = { onRecordingClick(item.recording) },
                                onLongClick = { selectedItem = item },
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 72.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        }

        // Bottom sheet for item actions
        if (selectedItem != null) {
            ModalBottomSheet(
                onDismissRequest = { selectedItem = null },
                sheetState = sheetState,
            ) {
                RecordingActionsSheet(
                    item = selectedItem!!,
                    onShare = {
                        viewModel.shareRecording(selectedItem!!.recording)
                        scope.launch {
                            sheetState.hide()
                            selectedItem = null
                        }
                    },
                    onDelete = {
                        viewModel.deleteRecording(selectedItem!!.recording)
                        scope.launch {
                            sheetState.hide()
                            selectedItem = null
                        }
                    },
                    onRetryTranscription =
                        if (selectedItem!!.recording.status == RecordingStatus.FAILED) {
                            {
                                viewModel.retryTranscription(selectedItem!!.recording)
                                scope.launch {
                                    sheetState.hide()
                                    selectedItem = null
                                }
                            }
                        } else {
                            null
                        },
                    onGenerateTitle =
                        if (selectedItem!!.recording.status == RecordingStatus.COMPLETED) {
                            {
                                viewModel.generateTitle(selectedItem!!.recording)
                                scope.launch {
                                    sheetState.hide()
                                    selectedItem = null
                                }
                            }
                        } else {
                            null
                        },
                    onGenerateSummary =
                        if (selectedItem!!.recording.status == RecordingStatus.COMPLETED) {
                            {
                                viewModel.generateSummary(selectedItem!!.recording)
                                scope.launch {
                                    sheetState.hide()
                                    selectedItem = null
                                }
                            }
                        } else {
                            null
                        },
                    onRecoverStuck =
                        if (shouldShowStuckRecoveryAction(selectedItem!!.recording.status)) {
                            {
                                viewModel.recoverStuckItem(selectedItem!!.recording)
                                scope.launch {
                                    sheetState.hide()
                                    selectedItem = null
                                }
                            }
                        } else {
                            null
                        },
                )
            }
        }
    }
}

/**
 * Individual recording list item - no card wrapper.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingListItem(
    item: RecordingDisplayItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recording = item.recording

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Profile emoji in 40dp touch target with secondaryContainer background
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (item.profileIcon != null) {
                Text(
                    text = item.profileIcon,
                    style = MaterialTheme.typography.titleSmall,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Title
            Text(
                text = recording.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Metadata line: "3h ago · 4:32"
            val metadataText = remember(recording.createdAt, recording.durationMs) {
                "${recording.createdAt.formatRelative()} · ${recording.durationMs.formatAsDuration()}"
            }
            Text(
                text = metadataText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Summary (2 lines, 60% alpha)
            if (item.summary != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (shouldShowStuckRecoveryAction(recording.status)) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text =
                        recording.errorMessage
                            ?: "Stuck in ${recording.status.name.lowercase().replace('_', ' ')}. Long-press for recovery.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Tags (max 3 + overflow)
            if (item.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item.tags.take(3).forEach { tag ->
                        CompactTagChip(tag = tag)
                    }
                    if (item.tags.size > 3) {
                        Text(
                            text = "+${item.tags.size - 3}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact tag chip for list items.
 */
@Composable
private fun CompactTagChip(tag: Tag) {
    // Memoize color parsing to avoid redundant computation during list scrolling
    // Color.parseColor is expensive and list items recompose frequently during scroll
    val defaultColor = MaterialTheme.colorScheme.tertiary
    val tagColor =
        remember(tag.color, defaultColor) {
            tag.color?.let {
                try {
                    Color(android.graphics.Color.parseColor(it))
                } catch (_: Exception) {
                    defaultColor
                }
            } ?: defaultColor
        }

    Row(
        modifier =
            Modifier
                .background(
                    color = tagColor.copy(alpha = 0.12f),
                    shape = MaterialTheme.shapes.small,
                ).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(tagColor),
        )
        Text(
            text = tag.name,
            style = MaterialTheme.typography.labelSmall,
            color = tagColor,
            maxLines = 1,
        )
    }
}

/**
 * Bottom sheet with recording actions.
 */
@Composable
private fun RecordingActionsSheet(
    item: RecordingDisplayItem,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRetryTranscription: (() -> Unit)?,
    onGenerateTitle: (() -> Unit)?,
    onGenerateSummary: (() -> Unit)?,
    onRecoverStuck: (() -> Unit)?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
    ) {
        // Header
        Text(
            text = item.recording.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        HorizontalDivider()

        // Share
        SheetActionItem(
            icon = Icons.Default.Share,
            text = "Share",
            onClick = onShare,
        )

        // AI Options (for completed recordings)
        if (onGenerateTitle != null || onGenerateSummary != null) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))

            if (onGenerateTitle != null) {
                SheetActionItem(
                    icon = Icons.Default.Title,
                    text = "Generate title",
                    onClick = onGenerateTitle,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }

            if (onGenerateSummary != null) {
                SheetActionItem(
                    icon = Icons.Default.Summarize,
                    text = "Generate summary",
                    onClick = onGenerateSummary,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
        }

        // Retry (for failed recordings)
        if (onRetryTranscription != null) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
            SheetActionItem(
                icon = Icons.Default.Refresh,
                text = "Retry transcription",
                onClick = onRetryTranscription,
            )
        }

        if (onRecoverStuck != null) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
            SheetActionItem(
                icon = Icons.Default.Refresh,
                text = "Recover stuck processing",
                onClick = onRecoverStuck,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))

        // Delete
        SheetActionItem(
            icon = Icons.Default.Delete,
            text = "Delete",
            onClick = onDelete,
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SheetActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
        )
    }
}

/**
 * Extended FAB with breathing animation.
 */
@Composable
fun BreathingExtendedFab(
    expanded: Boolean,
    isChecking: Boolean,
    onClick: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    val animatedScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.03f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "fab_scale",
    )
    val scale = if (isChecking) 1f else animatedScale

    ExtendedFloatingActionButton(
        onClick = {
            if (isRecordEntryActionEnabled(isChecking)) {
                onClick()
            }
        },
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        expanded = expanded,
        icon = {
            if (isChecking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                )
            }
        },
        text = {
            Text(recordFabLabel(isChecking))
        },
        modifier =
            Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .testTag(HomeScreenRecordEntryTestTags.RecordFab),
    )
}

/**
 * Animated empty state with floating mic icon.
 */
@Composable
fun AnimatedEmptyState(
    onRecordClick: () -> Unit,
    isRecordEntryChecking: Boolean,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "floating")
    val offsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 8f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 3000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "float_offset",
    )

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Floating mic icon
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = null,
            modifier =
                Modifier
                    .size(80.dp)
                    .graphicsLayer { translationY = -offsetY },
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Your stage awaits",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Tap Record to capture your first brilliant idea",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        FilledTonalButton(
            onClick = onRecordClick,
            enabled = isRecordEntryActionEnabled(isRecordEntryChecking),
            modifier = Modifier.testTag(HomeScreenRecordEntryTestTags.EmptyStateRecordButton),
        ) {
            if (isRecordEntryChecking) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(emptyStateRecordButtonLabel(isRecordEntryChecking))
                }
            } else {
                Text(emptyStateRecordButtonLabel(isRecordEntryChecking))
            }
        }
    }
}

internal fun shouldShowStuckRecoveryAction(status: RecordingStatus): Boolean =
    status == RecordingStatus.PENDING_TRANSCRIPTION || status == RecordingStatus.ENHANCING

internal fun isRecordEntryActionEnabled(isChecking: Boolean): Boolean = !isChecking

internal fun recordFabLabel(isChecking: Boolean): String = if (isChecking) "Checking..." else "Record"

internal fun emptyStateRecordButtonLabel(isChecking: Boolean): String = if (isChecking) "Checking model..." else "Record now"

object HomeScreenRecordEntryTestTags {
    const val RecordFab = "home_record_fab"
    const val EmptyStateRecordButton = "home_empty_record_button"
}
