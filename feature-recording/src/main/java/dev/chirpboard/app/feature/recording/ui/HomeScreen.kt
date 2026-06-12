package dev.chirpboard.app.feature.recording.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import dev.chirpboard.app.core.ui.motion.PushDownReveal
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import dev.chirpboard.app.core.ui.components.AnimatedAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.chirpboard.app.core.ui.components.SkeletonPlaceholder
import dev.chirpboard.app.core.ui.components.StatsPillRow
import dev.chirpboard.app.core.ui.components.RepositoryErrorSnackbarEffect
import dev.chirpboard.app.core.ui.components.StatusBarProtection
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.core.ui.motion.ChirpMotion
import dev.chirpboard.app.core.ui.motion.animatePushDownLayout
import dev.chirpboard.app.core.ui.theme.ChirpSpacing
import dev.chirpboard.app.feature.recording.R
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onRecordingClick: (RecordingDisplayItem) -> Unit,
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
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val quickStarts by viewModel.quickStartProfiles.collectAsStateWithLifecycle()
    val recoverableSessions by viewModel.recoverableSessions.collectAsStateWithLifecycle()
    val playbackRowState by viewModel.playbackRowState.collectAsStateWithLifecycle()
    // LOAD-3: distinguishes "first DB load not yet resolved" from "loaded and genuinely empty" so
    // the empty illustration never flashes for a user who actually has recordings.
    val contentLoaded by viewModel.contentLoaded.collectAsStateWithLifecycle()

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
    // The recovery prompt is presented only when the user taps "Review" on the in-list recovery
    // banner (below). It is intentionally NOT auto-presented over Home: the modal's destructive
    // "Discard" button must never materialise asynchronously under an in-flight tap.
    var recoveryPromptSession by remember { mutableStateOf<dev.chirpboard.app.feature.recording.session.RecoverableRecordingSession?>(null) }

    // UI-7: FAB expand/collapse with REAL hysteresis (the previous code documented hysteresis but
    // used a single hard threshold, so a list resting near 48px toggled the extended FAB open/shut
    // repeatedly). We collapse only once scrolled well past the first row and re-expand only once
    // scrolled back near the very top, holding the prior decision in the dead band between. The
    // previous decision is held in a non-snapshot box so the derivedStateOf only depends on the
    // scroll position (no write-during-read of observed state).
    val fabExpandedHysteresis = remember { intArrayOf(1) }
    val fabExpanded by remember {
        derivedStateOf {
            val next =
                nextFabExpandedState(
                    previousExpanded = fabExpandedHysteresis[0] == 1,
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                )
            fabExpandedHysteresis[0] = if (next) 1 else 0
            next
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

    // SLOP-23: progress/success notices use their own channel so they are never styled as errors.
    RepositoryErrorSnackbarEffect(
        errorMessage = statusMessage,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearStatus,
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

    recoveryPromptSession?.let { session ->
        AnimatedAlertDialog(
            onDismissRequest = {
                viewModel.deferInterruptedSession(session.sessionId)
                recoveryPromptSession = null
            },
            title = { Text(stringResource(R.string.rec_recovery_title)) },
            text = {
                Text(
                    if (session.hasPotentialLoss) {
                        stringResource(
                            R.string.rec_recovery_message_with_loss,
                            session.estimatedLostMinutes(),
                        )
                    } else {
                        stringResource(R.string.rec_recovery_message)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.recoverInterruptedSession(session.sessionId)
                        recoveryPromptSession = null
                    },
                ) {
                    Text(stringResource(R.string.rec_recovery_recover))
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            viewModel.keepInterruptedSession(session.sessionId)
                            recoveryPromptSession = null
                        },
                    ) {
                        Text(stringResource(R.string.rec_recovery_keep))
                    }
                    TextButton(
                        onClick = {
                            viewModel.discardInterruptedSession(session.sessionId)
                            recoveryPromptSession = null
                        },
                    ) {
                        Text(stringResource(R.string.rec_recovery_discard))
                    }
                }
            },
        )
    }

    // LOAD-3: three-state home content. Until the first Room emission resolves (`contentLoaded`),
    // hold a skeleton instead of flashing the empty illustration then crossfading to the list. Only
    // once the first load is known do we choose between the genuine empty state and the list.
    val homeContentPhase =
        homeContentPhase(
            contentLoaded = contentLoaded,
            totalRecordings = stats.totalRecordings,
            searchBlank = searchQuery.isBlank(),
            filterAll = listFilter == ListFilterMode.ALL,
        )
    val showEmptyState = homeContentPhase == HomeContentPhase.EMPTY
    val hasActiveListFilter = listFilter != ListFilterMode.ALL || searchQuery.isNotBlank()
    val appBarScrollBehavior = if (searchActive) null else scrollBehavior

    // collapsedFraction is backed by mutableFloatStateOf and changes every frame while the
    // app bar collapses/expands during a fling. Derive the threshold boolean so the topBar
    // scope invalidates only when the threshold is crossed, not on every frame.
    val collapsed by remember(appBarScrollBehavior) {
        derivedStateOf { isAppBarCollapsed(appBarScrollBehavior?.state?.collapsedFraction ?: 0f) }
    }

    Scaffold(
        modifier =
            if (appBarScrollBehavior != null) {
                Modifier.nestedScroll(appBarScrollBehavior.nestedScrollConnection)
            } else {
                Modifier
            },
        topBar = {
            // UI-5: no animateContentSize here. The collapsing MediumTopAppBar resizes itself every
            // frame via exitUntilCollapsedScrollBehavior; wrapping it in a spring made the bar (and
            // the content offset below it) rubber-band behind the finger. The only sibling whose
            // appearance needs animating is the search field, and PushDownReveal already animates
            // its own expand/collapse.
            Column(modifier = Modifier.fillMaxWidth()) {
                MediumTopAppBar(
                    title = {
                        // UI-6: crossfade the title across the collapse threshold instead of swapping
                        // text + typography in a single frame (a visible mid-scroll pop, and a
                        // flicker when the scroll rests near the threshold).
                        Crossfade(
                            targetState = collapsed,
                            animationSpec = ChirpMotion.studioAlphaTween,
                            label = "home_title_collapse",
                        ) { isCollapsed ->
                            Text(
                                text =
                                    if (isCollapsed) {
                                        stringResource(R.string.rec_recordings_title_collapsed)
                                    } else {
                                        stringResource(R.string.rec_recordings_title_expanded)
                                    },
                                style =
                                    if (isCollapsed) {
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
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = stringResource(R.string.desc_search),
                                    tint =
                                        if (searchQuery.isNotBlank()) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                )
                            }
                        } else {
                            IconButton(onClick = { searchActive = false }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.desc_close),
                                )
                            }
                        }

                        Box {
                            IconButton(onClick = { showImportMenu = true }) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
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
                                            imageVector = Icons.Rounded.AudioFile,
                                            contentDescription = null,
                                        )
                                    },
                                )
                            }
                        }

                        IconButton(onClick = onSettingsClick) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = stringResource(R.string.desc_settings),
                            )
                        }
                    },
                    scrollBehavior = appBarScrollBehavior,
                    colors =
                        TopAppBarDefaults.mediumTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            scrolledContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                )
                PushDownReveal(visible = searchActive) {
                    SearchBarDefaults.InputField(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        query = searchQuery,
                        onQueryChange = viewModel::onSearchQueryChange,
                        onSearch = { searchActive = false },
                        expanded = true,
                        onExpandedChange = { searchActive = it },
                        placeholder = { Text(stringResource(R.string.search_recordings)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Search,
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
                                    imageVector = Icons.Rounded.Close,
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
                }
            }
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.animatePushDownLayout(),
            ) {
                PushDownReveal(
                    visible =
                        homeContentPhase == HomeContentPhase.LIST &&
                            shouldShowHomeQuickStartSurface(quickStarts),
                ) {
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
                        .consumeWindowInsets(paddingValues)
                        .animatePushDownLayout(),
                targetState = homeContentPhase,
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
        ) { phase ->
            when (phase) {
                HomeContentPhase.LOADING ->
                    HomeListSkeleton(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(paddingValues),
                    )

                HomeContentPhase.EMPTY ->
                AnimatedEmptyState(
                    onRecordClick = onRecordClick,
                    onQuickStartClick = onQuickStartClick,
                    quickStarts = quickStarts,
                    isRecordEntryChecking = isRecordEntryChecking,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .padding(horizontal = ChirpSpacing.ScreenHorizontal),
                )

                HomeContentPhase.LIST ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding =
                        PaddingValues(
                            top = paddingValues.calculateTopPadding(),
                            // INS-7: reserve clearance for BOTH the Record FAB and the global
                            // mini-player bar (a layout sibling below this screen) so the last row
                            // is never cramped under the now-playing transport when it is visible.
                            bottom =
                                paddingValues.calculateBottomPadding() +
                                    HOME_LIST_BOTTOM_CLEARANCE,
                        ),
                ) {
                    item(key = "recovery_banner", contentType = "recovery_banner") {
                        PushDownReveal(visible = recoverableSessions.isNotEmpty()) {
                            Card(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            horizontal = ChirpSpacing.ScreenHorizontal,
                                            vertical = ChirpSpacing.Small,
                                        ),
                                colors =
                                    CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                    ),
                            ) {
                                Column(
                                    modifier = Modifier.padding(ChirpSpacing.Large),
                                    verticalArrangement = Arrangement.spacedBy(ChirpSpacing.Small),
                                ) {
                                    Text(
                                        text = stringResource(R.string.rec_recovery_banner_title),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        text =
                                            if (recoverableSessions.firstOrNull()?.hasPotentialLoss == true) {
                                                stringResource(
                                                    R.string.rec_recovery_banner_body_with_loss,
                                                    recoverableSessions.first().estimatedLostMinutes(),
                                                )
                                            } else {
                                                stringResource(R.string.rec_recovery_banner_body)
                                            },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilledTonalButton(
                                            onClick = {
                                                recoverableSessions.firstOrNull()?.let { session ->
                                                    recoveryPromptSession = session
                                                }
                                            },
                                        ) {
                                            Text(stringResource(R.string.rec_recovery_review))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item(key = "stats", contentType = "stats") {
                        PushDownReveal(visible = searchQuery.isBlank() && stats.totalRecordings > 0) {
                            StatsPillRow(
                                recordingCount = stats.totalRecordings,
                                totalDurationMs = stats.totalDurationMs,
                                processingCount = stats.processingCount,
                                onProcessingClick = { viewModel.onProcessingClick() },
                                processingFilterActive = listFilter == ListFilterMode.PROCESSING,
                                // VIS-4: no horizontal inset here — StatsPillRow applies
                                // ChirpSpacing.ScreenHorizontal internally, so the pills' left edge
                                // lines up with the list rows below (the old 8dp + internal 16dp
                                // double-inset pushed them to 24dp).
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = ChirpSpacing.Small),
                            )
                        }
                    }

                    item(key = "processing_filter_chip", contentType = "processing_filter_chip") {
                        PushDownReveal(
                            visible =
                                searchQuery.isBlank() &&
                                    stats.totalRecordings > 0 &&
                                    listFilter == ListFilterMode.PROCESSING,
                        ) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            horizontal = ChirpSpacing.ScreenHorizontal,
                                            vertical = ChirpSpacing.ExtraSmall,
                                        ),
                                horizontalArrangement = Arrangement.spacedBy(ChirpSpacing.Small),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                InputChip(
                                    selected = true,
                                    onClick = { viewModel.setListFilter(ListFilterMode.ALL) },
                                    label = { Text(stringResource(R.string.rec_filter_processing)) },
                                    trailingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = stringResource(R.string.rec_filter_clear),
                                            modifier = Modifier.size(18.dp),
                                        )
                                    },
                                )
                            }
                        }
                    }

                    item(key = "recover_stuck", contentType = "recover_stuck") {
                        PushDownReveal(
                            visible =
                                searchQuery.isBlank() &&
                                    listFilter == ListFilterMode.PROCESSING &&
                                    stuckCount > 0,
                        ) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            horizontal = ChirpSpacing.ScreenHorizontal,
                                            vertical = ChirpSpacing.ExtraSmall,
                                        ),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                FilledTonalButton(onClick = { viewModel.recoverAllStuck() }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(ChirpSpacing.Small))
                                    Text(stringResource(R.string.rec_recover_stuck, stuckCount))
                                }
                            }
                        }
                    }

                    item(key = "search_results", contentType = "search_results") {
                        PushDownReveal(visible = searchQuery.isNotBlank()) {
                            Text(
                                text = pluralStringResource(R.plurals.rec_search_results_count, displayItems.size, displayItems.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier =
                                    Modifier
                                        .padding(
                                            horizontal = ChirpSpacing.ScreenHorizontal,
                                            vertical = ChirpSpacing.Small,
                                        ),
                            )
                        }
                    }

                    item(key = "filter_empty", contentType = "filter_empty") {
                        PushDownReveal(visible = displayItems.isEmpty() && hasActiveListFilter) {
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            horizontal = ChirpSpacing.ExtraLarge,
                                            vertical = ChirpSpacing.ExtraExtraLarge,
                                        ),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(ChirpSpacing.Medium),
                            ) {
                                Text(
                                    text = stringResource(R.string.rec_filter_empty_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(
                                    onClick = {
                                        viewModel.clearListFilters()
                                        searchActive = false
                                    },
                                ) {
                                    Text(stringResource(R.string.rec_filter_clear))
                                }
                            }
                        }
                    }

                    // Recording list items - smooth scrolling, no stagger delay
                    items(
                        items = displayItems,
                        key = { it.id },
                        contentType = { "recording" },
                    ) { item ->
                        Column(modifier = Modifier.animateItem()) {
                            RecordingListItem(
                                item = item,
                                playbackState = playbackRowState,
                                recordingState = recordingState,
                                onClick = { onRecordingClick(item) },
                                onPlayClick = { viewModel.playRecording(item) },
                                onLongClick = { selectedItem = item },
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = ChirpSpacing.ScreenHorizontal),
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

/**
 * The three top-level states of the home content area (LOAD-3).
 *
 * [LOADING] is held until the first Room emission resolves so the empty illustration is never
 * flashed for a user who actually has recordings; only once the first load is known does the screen
 * resolve to [EMPTY] or [LIST].
 */
internal enum class HomeContentPhase {
    LOADING,
    EMPTY,
    LIST,
}

/**
 * Resolve the home content phase (LOAD-3). Extracted as a pure function so the gating logic — the
 * part that prevents the empty-state flash — is unit-tested without a Compose runtime.
 *
 * @param contentLoaded true once the first recordings emission has resolved.
 * @param totalRecordings the loaded recording count (only meaningful once [contentLoaded]).
 * @param searchBlank true when there is no active search query.
 * @param filterAll true when the list filter is [ListFilterMode.ALL].
 */
internal fun homeContentPhase(
    contentLoaded: Boolean,
    totalRecordings: Int,
    searchBlank: Boolean,
    filterAll: Boolean,
): HomeContentPhase =
    when {
        // Never claim "empty" before the first load resolves: hold the skeleton instead.
        !contentLoaded -> HomeContentPhase.LOADING
        totalRecordings == 0 && searchBlank && filterAll -> HomeContentPhase.EMPTY
        else -> HomeContentPhase.LIST
    }

/**
 * Bottom clearance reserved by the home list (INS-7). Covers the Record FAB AND the global
 * mini-player bar (a layout sibling below this screen, ~72dp tall when visible) so the last row is
 * never cramped under the now-playing transport. Slightly larger than the previous FAB-only 96dp.
 */
private val HOME_LIST_BOTTOM_CLEARANCE = 112.dp

/** Number of shimmer placeholder rows shown in the first-load skeleton (LOAD-3). */
private const val HOME_SKELETON_ROW_COUNT = 4

/**
 * First-load skeleton for the home list (LOAD-3).
 *
 * Shown only while the first DB emission is pending. Renders a faint stats-pill placeholder plus a
 * few shimmering [SkeletonPlaceholder] rows that mirror the real row layout (title bar + metadata
 * pill row), so the wait reads as intentional loading rather than a blank screen or a wrong-state
 * empty flash. Crossfades to the real list (or empty state) once the load resolves.
 */
@Composable
private fun HomeListSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier.padding(
                horizontal = ChirpSpacing.ScreenHorizontal,
                vertical = ChirpSpacing.Small,
            ),
        verticalArrangement = Arrangement.spacedBy(ChirpSpacing.Small),
    ) {
        // Faint stats-pill placeholder mirroring the StatsPillRow that lands first.
        Row(horizontalArrangement = Arrangement.spacedBy(ChirpSpacing.Small)) {
            repeat(3) {
                SkeletonPlaceholder(width = 64.dp, height = 32.dp, shape = MaterialTheme.shapes.large)
            }
        }
        Spacer(modifier = Modifier.size(ChirpSpacing.Small))
        repeat(HOME_SKELETON_ROW_COUNT) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = ChirpSpacing.Small),
                verticalArrangement = Arrangement.spacedBy(ChirpSpacing.Small),
            ) {
                SkeletonPlaceholder(width = 180.dp, height = 20.dp)
                SkeletonPlaceholder(width = 220.dp, height = 16.dp)
            }
        }
    }
}

/**
 * Fraction past which the medium top bar is considered collapsed for title/style selection.
 */
private const val APP_BAR_COLLAPSED_THRESHOLD = 0.5f

/**
 * Whether the medium top app bar is collapsed at [collapsedFraction]. Extracted so the boolean
 * can be derived via [derivedStateOf]: it only changes when the threshold is crossed, sparing the
 * topBar scope a per-frame recomposition during scroll.
 */
internal fun isAppBarCollapsed(collapsedFraction: Float): Boolean =
    collapsedFraction > APP_BAR_COLLAPSED_THRESHOLD

/** Scroll offset (px) past the first row at which the record FAB collapses to its compact form. */
private const val FAB_COLLAPSE_OFFSET_PX = 64

/** Scroll offset (px) at or below which the record FAB re-expands to its labelled form. */
private const val FAB_EXPAND_OFFSET_PX = 32

/**
 * Next expanded state for the record FAB given the current scroll position and the [previousExpanded]
 * decision. Uses separate collapse (>64px) and expand (<=32px) thresholds so the FAB does not toggle
 * back and forth when the list comes to rest in the 32-64px band — the hysteresis the old single
 * threshold only claimed to have. Extracted as a pure function so the threshold logic is unit-tested.
 */
internal fun nextFabExpandedState(
    previousExpanded: Boolean,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
): Boolean {
    if (firstVisibleItemIndex != 0) {
        return false
    }
    return when {
        firstVisibleItemScrollOffset <= FAB_EXPAND_OFFSET_PX -> true
        firstVisibleItemScrollOffset > FAB_COLLAPSE_OFFSET_PX -> false
        else -> previousExpanded
    }
}

internal fun shouldShowStuckRecoveryAction(status: RecordingStatus): Boolean =
    status == RecordingStatus.PENDING_TRANSCRIPTION ||
        status == RecordingStatus.PENDING_ENHANCEMENT ||
        status == RecordingStatus.ENHANCING

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
    const val QuickStartSurface = "home_quick_start_surface"
}
