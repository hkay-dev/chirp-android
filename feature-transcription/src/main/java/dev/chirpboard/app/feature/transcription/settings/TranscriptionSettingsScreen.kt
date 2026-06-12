package dev.chirpboard.app.feature.transcription.settings

import androidx.compose.animation.AnimatedContent
import dev.chirpboard.app.core.ui.motion.PushDownReveal
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.core.storage.AllFilesAccessRequester
import dev.chirpboard.app.core.ui.components.AnimatedAlertDialog
import dev.chirpboard.app.core.ui.components.SettingsSectionHeader
import dev.chirpboard.app.core.ui.R as CoreR
import dev.chirpboard.app.feature.transcription.R

/**
 * Entry point for every download intent. When All-files-access is missing the user is
 * shown a rationale + storage-choice dialog (PLT-07) instead of being bounced straight to
 * the system toggle: they can grant the permission for the durable shared-storage location
 * OR download into app storage with no permission at all, so a declined grant is never a
 * dead end.
 */
private fun requestModelDownload(
    viewModel: TranscriptionSettingsViewModel,
    uiState: TranscriptionSettingsViewModel.UiState,
) {
    if (uiState.isLoading || uiState.isDownloaded) {
        return
    }

    if (AllFilesAccessRequester.needsPermission()) {
        viewModel.showStorageChoice()
        return
    }

    viewModel.downloadModel()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptionSettingsScreen(
    @Suppress("UNUSED_PARAMETER") autoStartDownload: Boolean = false,
    viewModel: TranscriptionSettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // LIF-06/ERR-3: the autoDownload nav-arg is consumed exactly once via the ViewModel's
    // SavedStateHandle (the nav arg itself arrives there). Rotation, process-death restore,
    // and error->idle flips can never re-trigger the 660MB download or loop a failing one.
    LaunchedEffect(Unit) {
        if (viewModel.consumePendingAutoDownload()) {
            requestModelDownload(viewModel, viewModel.uiState.value)
        }
    }

    // PLT-07: returning from the system All-files-access page resumes the user's intent —
    // if they granted access, the download they asked for starts without another tap.
    LifecycleResumeEffect(Unit) {
        viewModel.onResumed(hasAllFilesAccess = !AllFilesAccessRequester.needsPermission())
        onPauseOrDispose { }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.transcription_title),
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(CoreR.string.desc_navigate_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors =
                    TopAppBarDefaults.largeTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = padding,
        ) {

            // Model Status Section
            item {
                SettingsSectionHeader(
                    title = stringResource(R.string.transcription_section_model),
                )
            }

            item {
                ModelManagementCard(
                    modelName = uiState.modelName,
                    modelSizeMb = uiState.downloadedSizeMb ?: uiState.modelSizeMb,
                    isDownloaded = uiState.isDownloaded,
                    isLoading = uiState.isLoading,
                    isWaitingForNetwork = uiState.isWaitingForNetwork,
                    progress = uiState.downloadProgress,
                    currentFile = uiState.currentFile,
                    onDownload = { requestModelDownload(viewModel, uiState) },
                    onCancelDownload = viewModel::cancelDownload,
                    onDelete = viewModel::showDeleteConfirmation,
                )
            }

            // Error Message (honest card with a manual Retry — never auto-retried, ERR-3)
            uiState.errorMessage?.let { error ->
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    ErrorCard(
                        message = error,
                        showRetry = !uiState.isDownloaded,
                        onRetry = { requestModelDownload(viewModel, uiState) },
                        onDismiss = viewModel::dismissError,
                    )
                }
            }

            // Help text
            item {
                Text(
                    text = stringResource(R.string.transcription_model_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp),
                )
            }
            // INS-7: reserve space under the list for the global mini-player sibling bar.
            item { Spacer(modifier = Modifier.height(96.dp)) }
        }
    }

    // VIS-7: the bespoke AnimatedVisibility(scaleIn/scaleOut) wrapper is removed; the dialog now
    // shares the app-wide entrance by routing through AnimatedAlertDialog directly.
    if (uiState.showDeleteConfirmation) {
        DeleteConfirmationDialog(
            modelName = uiState.modelName,
            onConfirm = viewModel::deleteModel,
            onDismiss = viewModel::dismissDeleteConfirmation,
        )
    }

    if (uiState.showStorageChoice) {
        StorageChoiceDialog(
            modelSizeMb = uiState.modelSizeMb,
            onAllowAccess = {
                viewModel.onAllFilesAccessRequested()
                AllFilesAccessRequester.openSettings(context)
            },
            onUseAppStorage = { viewModel.downloadModel(preferInternalStorage = true) },
            onDismiss = viewModel::dismissStorageChoice,
        )
    }
}

@Composable
private fun ModelManagementCard(
    modelName: String,
    modelSizeMb: Int,
    isDownloaded: Boolean,
    isLoading: Boolean,
    isWaitingForNetwork: Boolean,
    progress: Float,
    currentFile: String,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val statusTint =
        animateColorAsState(
            targetValue =
                when {
                    isLoading -> MaterialTheme.colorScheme.tertiary
                    isDownloaded -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            animationSpec = tween(300, easing = FastOutSlowInEasing),
            label = "status_tint",
        ).value

    val iconContainerColor =
        animateColorAsState(
            targetValue =
                when {
                    isLoading -> MaterialTheme.colorScheme.tertiaryContainer
                    isDownloaded -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
            animationSpec = tween(300, easing = FastOutSlowInEasing),
            label = "icon_container_color",
        ).value

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header with Icon and Model Info
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(color = iconContainerColor, shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState =
                            when {
                                isLoading -> "loading"
                                isDownloaded -> "downloaded"
                                else -> "not_downloaded"
                            },
                        transitionSpec = {
                            fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.9f) togetherWith
                                fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.9f)
                        },
                        label = "statusIconTransition",
                    ) { state ->
                        when (state) {
                            "loading" -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = statusTint,
                                )
                            }
                            "downloaded" -> {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = statusTint,
                                )
                            }
                            else -> {
                                Icon(
                                    imageVector = Icons.Rounded.CloudDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = statusTint,
                                )
                            }
                        }
                    }
                }
                
                Spacer(Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = modelName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    val statusText = when {
                        isWaitingForNetwork -> stringResource(R.string.transcription_status_waiting)
                        isLoading -> stringResource(R.string.transcription_status_downloading)
                        isDownloaded -> stringResource(R.string.transcription_status_ready)
                        else -> stringResource(R.string.transcription_status_not_downloaded)
                    }
                    val sizeText = stringResource(R.string.transcription_size_value, modelSizeMb)
                    
                    Text(
                        text = "$statusText • $sizeText",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Progress Section
            PushDownReveal(visible = isLoading) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = when {
                                isWaitingForNetwork -> stringResource(R.string.transcription_download_waiting)
                                currentFile.isNotEmpty() ->
                                    stringResource(R.string.transcription_downloading_file, currentFile)
                                else -> stringResource(R.string.transcription_downloading)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!isWaitingForNetwork) {
                            Text(
                                text = "${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    if (isWaitingForNetwork) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = MaterialTheme.colorScheme.tertiary,
                            trackColor = MaterialTheme.colorScheme.tertiaryContainer,
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = MaterialTheme.colorScheme.tertiary,
                            trackColor = MaterialTheme.colorScheme.tertiaryContainer,
                        )
                    }
                }
            }

            // Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (!isDownloaded) {
                    if (isLoading) {
                        // The transfer is app-scoped work that keeps running when the user
                        // leaves; Cancel is the honest escape hatch (partials are kept, so
                        // a later download resumes instead of restarting).
                        OutlinedButton(onClick = onCancelDownload) {
                            Text(stringResource(R.string.transcription_cancel_download))
                        }
                    } else {
                        Button(onClick = onDownload) {
                            Icon(
                                imageVector = Icons.Rounded.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.transcription_download_model))
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.transcription_delete_model))
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(
    message: String,
    showRetry: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.transcription_dismiss))
            }
            if (showRetry) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.transcription_retry))
                }
            }
        }
    }
}

/**
 * PLT-07: explains WHY the app wants All-files-access (the 660MB model survives reinstall
 * and Clear data in shared Documents) and offers the internal-storage fallback the
 * downloader already supports, so declining the permission never dead-ends the download.
 */
@Composable
private fun StorageChoiceDialog(
    modelSizeMb: Int,
    onAllowAccess: () -> Unit,
    onUseAppStorage: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedAlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(stringResource(R.string.transcription_storage_choice_title))
        },
        text = {
            Text(stringResource(R.string.transcription_storage_choice_message, modelSizeMb))
        },
        confirmButton = {
            Button(onClick = onAllowAccess) {
                Text(stringResource(R.string.transcription_storage_choice_allow))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.transcription_cancel))
                }
                TextButton(onClick = onUseAppStorage) {
                    Text(stringResource(R.string.transcription_storage_choice_internal))
                }
            }
        },
    )
}

@Composable
private fun DeleteConfirmationDialog(
    modelName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedAlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text(stringResource(R.string.transcription_delete_model_confirm))
        },
        text = {
            Text(stringResource(R.string.transcription_delete_model_message, modelName))
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Text(stringResource(R.string.transcription_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.transcription_cancel))
            }
        },
    )
}
