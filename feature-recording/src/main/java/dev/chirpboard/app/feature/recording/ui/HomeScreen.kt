package dev.chirpboard.app.feature.recording.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.ui.components.StatsPillRow
import dev.chirpboard.app.core.ui.components.RepositoryErrorSnackbarEffect
import dev.chirpboard.app.core.ui.components.StatusBarProtection
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.core.ui.motion.ChirpMotion
import dev.chirpboard.app.feature.recording.R
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onRecordingClick: (UUID) -> Unit,
    onRecordClick: () -> Unit,
    onQuickStartClick: (UUID) -> Unit,
    onSettingsClick: () -> Unit,
    onImportAudio: (Uri) -> Unit = {},
    isRecordEntryChecking: Boolean = false,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val displayItems by viewModel.displayItems.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val listFilter by viewModel.listFilter.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val stuckCount by viewModel.stuckCount.collectAsStateWithLifecycle()
    val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val quickStarts by viewModel.quickStartProfiles.collectAsStateWithLifecycle()

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                onImportAudio(uri)
            }
        }

    var searchActive by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showImportMenu by remember { mutableStateOf(false) }

    // FAB expand/collapse based on scroll with hysteresis to avoid flicker at the threshold.
    val fabExpanded by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset < 48
        }
    }
    val isListScrolling by remember {
        derivedStateOf { listState.isScrollInProgress }
    }

    // Bottom sheet state
    var selectedItem by remember { mutableStateOf<RecordingDisplayItem?>(null) }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    // Show error messages
    RepositoryErrorSnackbarEffect(
        errorMessage = errorMessage,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
    )

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

    val showEmptyState = displayItems.isEmpty() && searchQuery.isBlank()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            val collapsed = scrollBehavior.state.collapsedFraction > 0.5f
            MediumTopAppBar(
                title = {
                    if (searchActive) {
                        SearchBarDefaults.InputField(
                            query = searchQuery,
                            onQueryChange = viewModel::onSearchQueryChange,
                            onSearch = { searchActive = false },
                            expanded = true,
                            onExpandedChange = { searchActive = it },
                            placeholder = { Text(stringResource(R.string.search_recordings)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = stringResource(R.string.desc_search),
                                )
                            },
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        if (searchQuery.isNotEmpty()) {
                                            viewModel.onSearchQueryChange("")
                                        } else {
                                            searchActive = false
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription =
                                            stringResource(
                                                if (searchQuery.isNotEmpty()) {
                                                    R.string.desc_clear_search
                                                } else {
                                                    R.string.desc_close
                                                },
                                            ),
                                    )
                                }
                            },
                        )
                    } else {
                        Text(
                            text =
                                if (collapsed) {
                                    stringResource(R.string.rec_recordings_title_collapsed)
                                } else {
                                    stringResource(R.string.rec_recordings_title_expanded)
                                },
                            style =
                                if (collapsed) {
                                    MaterialTheme.typography.titleLarge
                                } else {
                                    MaterialTheme.typography.headlineMedium
                                },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                actions = {
                    if (!searchActive) {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(R.string.desc_search),
                                tint =
                                    if (searchQuery.isNotBlank()) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        }
                    }

                    Box {
                        IconButton(onClick = { showImportMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.rec_import_audio),
                            )
                        }
                        DropdownMenu(
                            expanded = showImportMenu,
                            onDismissRequest = { showImportMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rec_import_audio)) },
                                onClick = {
                                    showImportMenu = false
                                    launcher.launch("audio/*")
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.AudioFile,
                                        contentDescription = null,
                                    )
                                },
                            )
                        }
                    }

                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.desc_settings),
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
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!showEmptyState && shouldShowHomeQuickStartSurface(quickStarts)) {
                    HomeQuickStartSurface(
                        quickStarts = quickStarts,
                        onQuickStartClick = onQuickStartClick,
                        isRecordEntryChecking = isRecordEntryChecking,
                    )
                }

                BreathingExtendedFab(
                    expanded = fabExpanded,
                    isChecking = isRecordEntryChecking,
                    isScrollInProgress = isListScrolling,
                    onClick = onRecordClick,
                )
            }
        },
        floatingActionButtonPosition = FabPosition.End,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .consumeWindowInsets(paddingValues),
                targetState = showEmptyState,
            transitionSpec = {
                fadeIn(ChirpMotion.studioAlphaTween) togetherWith
                    fadeOut(
                        tween(
                            durationMillis = ChirpMotion.STUDIO_HIDE_MS,
                            easing = FastOutSlowInEasing,
                        ),
                    )
            },
            label = "home_content",
        ) { showEmpty ->
            if (showEmpty) {
                AnimatedEmptyState(
                    onRecordClick = onRecordClick,
                    onQuickStartClick = onQuickStartClick,
                    quickStarts = quickStarts,
                    isRecordEntryChecking = isRecordEntryChecking,
                    modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 8.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding =
                        PaddingValues(
                            top = paddingValues.calculateTopPadding(),
                            bottom = paddingValues.calculateBottomPadding() + 96.dp,
                        ),
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
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                            )
                        }

                        if (searchQuery.isBlank() && listFilter == ListFilterMode.PROCESSING && stuckCount > 0) {
                            item(key = "recover_stuck", contentType = "recover_stuck") {
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    FilledTonalButton(onClick = { viewModel.recoverAllStuck() }) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.rec_recover_stuck, stuckCount))
                                    }
                                }
                            }
                        }
                    }

                    // Search results count
                    if (searchQuery.isNotBlank()) {
                        item(key = "search_results", contentType = "search_results") {
                            Text(
                                text = pluralStringResource(R.plurals.rec_search_results_count, displayItems.size, displayItems.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier =
                                    Modifier
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                            )
                        }
                    }

                    // Recording list items - smooth scrolling, no stagger delay
                    items(
                        items = displayItems,
                        key = { it.id },
                        contentType = { "recording" },
                    ) { item ->
                        Column {
                            RecordingListItem(
                                item = item,
                                onClick = { onRecordingClick(item.id) },
                                onLongClick = { selectedItem = item },
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        }

            StatusBarProtection(
                modifier = Modifier.align(Alignment.TopCenter),
            )
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
                        viewModel.shareRecording(selectedItem!!, context)
                        scope.launch {
                            sheetState.hide()
                            selectedItem = null
                        }
                    },
                    onDelete = {
                        viewModel.deleteRecording(selectedItem!!)
                        scope.launch {
                            sheetState.hide()
                            selectedItem = null
                        }
                    },
                    onRetryTranscription =
                        if (selectedItem!!.status == RecordingStatus.FAILED) {
                            {
                                viewModel.retryTranscription(selectedItem!!)
                                scope.launch {
                                    sheetState.hide()
                                    selectedItem = null
                                }
                            }
                        } else {
                            null
                        },
                    onGenerateTitle =
                        if (selectedItem!!.status == RecordingStatus.COMPLETED) {
                            {
                                viewModel.generateTitle(selectedItem!!)
                                scope.launch {
                                    sheetState.hide()
                                    selectedItem = null
                                }
                            }
                        } else {
                            null
                        },
                    onGenerateSummary =
                        if (selectedItem!!.status == RecordingStatus.COMPLETED) {
                            {
                                viewModel.generateSummary(selectedItem!!)
                                scope.launch {
                                    sheetState.hide()
                                    selectedItem = null
                                }
                            }
                        } else {
                            null
                        },
                    onRecoverStuck =
                        if (shouldShowStuckRecoveryAction(selectedItem!!.status)) {
                            {
                                viewModel.recoverStuckItem(selectedItem!!)
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

internal fun shouldShowStuckRecoveryAction(status: RecordingStatus): Boolean =
    status == RecordingStatus.PENDING_TRANSCRIPTION || status == RecordingStatus.ENHANCING

internal fun quickStartTestTag(profileId: UUID): String = "home_quick_start_$profileId"

internal fun isRecordEntryActionEnabled(isChecking: Boolean): Boolean = !isChecking

@Composable
internal fun recordFabLabel(isChecking: Boolean): String =
    if (isChecking) {
        stringResource(R.string.rec_record_fab_checking)
    } else {
        stringResource(R.string.rec_record_fab_default)
    }

@Composable
internal fun emptyStateRecordButtonLabel(isChecking: Boolean): String =
    if (isChecking) {
        stringResource(R.string.rec_empty_state_record_checking)
    } else {
        stringResource(R.string.rec_empty_state_record_default)
    }

object HomeScreenRecordEntryTestTags {
    const val RecordFab = "home_record_fab"
    const val EmptyStateRecordButton = "home_empty_record_button"
    const val QuickStartSurface = "home_quick_start_surface"
}
