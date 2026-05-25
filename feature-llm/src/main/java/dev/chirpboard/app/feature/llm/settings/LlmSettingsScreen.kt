package dev.chirpboard.app.feature.llm.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.chirpboard.app.core.ui.motion.PushDownReveal
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.core.ui.R as CoreR
import dev.chirpboard.app.feature.llm.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmSettingsScreen(
    viewModel: LlmSettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToPromptSettings: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val saveBackupLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri: Uri? ->
            if (uri != null) {
                viewModel.completeBackup(uri)
            } else {
                viewModel.cancelPendingBackupOperation()
            }
        }

    val openBackupLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri != null) {
                viewModel.completeRestore(uri)
            } else {
                viewModel.cancelPendingBackupOperation()
            }
        }

    LaunchedEffect(viewModel) {
        viewModel.filePickerRequest.collect { request ->
            when (request) {
                is LlmSettingsViewModel.FilePickerRequest.Save -> {
                    saveBackupLauncher.launch(request.suggestedName)
                }

                LlmSettingsViewModel.FilePickerRequest.Open -> {
                    openBackupLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                }
            }
        }
    }

    uiState.passphraseDialog?.let { mode ->
        LlmApiKeyPassphraseDialog(
            mode = mode,
            onDismiss = viewModel::cancelPassphraseDialog,
            onConfirm = viewModel::submitPassphrase,
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.llm_settings_title),
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                LlmSettingsMasterToggleCard(
                    uiState = uiState,
                    onToggle = { viewModel.setLlmEnabled(!uiState.llmEnabled) },
                )
            }

            item {
                PushDownReveal(visible = uiState.llmEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        LlmSettingsApiKeySection(
                            uiState = uiState,
                            onProviderChanged = viewModel::setActiveProvider,
                            onModelChanged = viewModel::setSelectedModel,
                            onApiKeyChanged = viewModel::updateApiKey,
                            onSave = viewModel::saveApiKey,
                            onTestConnection = viewModel::testConnection,
                            onClear = viewModel::clearApiKey,
                            onDismissTestResult = viewModel::dismissTestResult,
                        )

                        LlmSettingsBackupSection(
                            configuredKeyCount = uiState.configuredKeyCount,
                            isSecureStorageAvailable = uiState.isSecureStorageAvailable,
                            backupMessage = uiState.backupMessage,
                            onDismissBackupMessage = viewModel::dismissBackupMessage,
                            onStartBackup = viewModel::startBackup,
                            onStartRestore = viewModel::startRestore,
                        )
                    }
                }
            }

            item {
                PushDownReveal(visible = uiState.llmEnabled) {
                    LlmSettingsProcessingSection(
                        uiState = uiState,
                        onSetAutoTitle = viewModel::setAutoTitle,
                        onSetAutoSummary = viewModel::setAutoSummary,
                        onManagePrompts = onNavigateToPromptSettings,
                    )
                }
            }
        }
    }
}
