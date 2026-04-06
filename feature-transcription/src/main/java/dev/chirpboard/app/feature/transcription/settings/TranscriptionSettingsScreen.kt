package dev.chirpboard.app.feature.transcription.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.core.ui.components.SettingsSectionHeader
import dev.chirpboard.app.feature.transcription.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptionSettingsScreen(
    viewModel: TranscriptionSettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

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
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.transcription_desc_back),
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
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Model Status Section
            SettingsSectionHeader(
                title = stringResource(R.string.transcription_section_model),
                modifier = Modifier.padding(horizontal = 0.dp),
            )

            ModelStatusCard(
                modelName = uiState.modelName,
                modelSizeMb = uiState.modelSizeMb,
                isDownloaded = uiState.isDownloaded,
                isLoading = uiState.isLoading,
            )

            // Download Progress
            if (uiState.isLoading) {
                DownloadProgressCard(
                    progress = uiState.downloadProgress,
                    currentFile = uiState.currentFile,
                )
            }

            // Error Message
            uiState.errorMessage?.let { error ->
                ErrorCard(
                    message = error,
                    onDismiss = viewModel::dismissError,
                )
            }

            // Model Actions Section
            Spacer(modifier = Modifier.height(8.dp))
            SettingsSectionHeader(
                title = stringResource(R.string.transcription_section_actions),
                modifier = Modifier.padding(horizontal = 0.dp),
            )

            ModelActionsCard(
                isDownloaded = uiState.isDownloaded,
                isLoading = uiState.isLoading,
                onDownload = viewModel::downloadModel,
                onDelete = viewModel::showDeleteConfirmation,
            )

            // Help text
            Text(
                text = stringResource(R.string.transcription_model_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Delete Confirmation Dialog
    AnimatedVisibility(
        visible = uiState.showDeleteConfirmation,
        enter =
            fadeIn(tween(200, easing = FastOutSlowInEasing)) +
                scaleIn(tween(200), initialScale = 0.85f),
        exit =
            fadeOut(tween(200)) +
                scaleOut(tween(200), targetScale = 0.85f),
    ) {
        DeleteConfirmationDialog(
            modelName = uiState.modelName,
            onConfirm = viewModel::deleteModel,
            onDismiss = viewModel::dismissDeleteConfirmation,
        )
    }
}

@Composable
private fun ModelStatusCard(
    modelName: String,
    modelSizeMb: Int,
    isDownloaded: Boolean,
    isLoading: Boolean,
) {
    val statusTint =
        animateColorAsState(
            targetValue =
                when {
                    isLoading -> MaterialTheme.colorScheme.tertiary
                    isDownloaded -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outline
                },
            animationSpec = tween(300, easing = FastOutSlowInEasing),
            label = "status_tint",
        ).value

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Model name row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.transcription_model_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = modelName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }

            // Model size row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.transcription_size_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.transcription_size_value, modelSizeMb),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            // Status row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.transcription_status_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            when (state) {
                                "loading" -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = statusTint,
                                    )
                                    Text(
                                        text = stringResource(R.string.transcription_status_downloading),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = statusTint,
                                    )
                                }

                                "downloaded" -> {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = statusTint,
                                    )
                                    Text(
                                        text = stringResource(R.string.transcription_status_ready),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = statusTint,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }

                                else -> {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = statusTint,
                                    )
                                    Text(
                                        text = stringResource(R.string.transcription_status_not_downloaded),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = statusTint,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadProgressCard(
    progress: Float,
    currentFile: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text =
                        if (currentFile.isNotEmpty()) {
                            stringResource(
                                R.string.transcription_downloading_file,
                                currentFile,
                            )
                        } else {
                            stringResource(R.string.transcription_downloading)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                color = MaterialTheme.colorScheme.tertiary,
                trackColor = MaterialTheme.colorScheme.tertiaryContainer,
            )
        }
    }
}

@Composable
private fun ErrorCard(
    message: String,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
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
        }
    }
}

@Composable
private fun ModelActionsCard(
    isDownloaded: Boolean,
    isLoading: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!isDownloaded) {
                Button(
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.transcription_downloading))
                    } else {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
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
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
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

@Composable
private fun DeleteConfirmationDialog(
    modelName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
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
