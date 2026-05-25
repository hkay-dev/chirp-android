package dev.chirpboard.app.navigation

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.chirpboard.app.R
import dev.chirpboard.app.core.modelreadiness.ModelReadinessState
import dev.chirpboard.app.core.modelreadiness.ModelReadinessUnavailableReason
import dev.chirpboard.app.core.ui.components.AnimatedAlertDialog
import dev.chirpboard.app.feature.recording.ui.HomeScreen
import dev.chirpboard.app.feature.recording.ui.HomeViewModel
import dev.chirpboard.app.feature.recording.ui.RecordScreen
import dev.chirpboard.app.feature.studio.ProcessingStudioScreen

internal data class RecordEntryDialogContent(
    val title: String,
    val message: String,
    val confirmLabelRes: Int,
    val navigateToTranscriptionDownload: Boolean,
)

internal fun NavGraphBuilder.appRecordingNavigation(navController: NavHostController) {
    composable(Screen.Home.route) {
        val recordEntryViewModel: HomeRecordEntryViewModel = hiltViewModel()
        val homeViewModel: HomeViewModel = hiltViewModel()
        val readinessState by recordEntryViewModel.readinessState.collectAsStateWithLifecycle()
        val openStudioForRecordingId by homeViewModel.openStudioForRecordingId.collectAsStateWithLifecycle()
        val context = LocalContext.current
        var dialogContent by remember { mutableStateOf<RecordEntryDialogContent?>(null) }

        LaunchedEffect(openStudioForRecordingId) {
            val recordingId = openStudioForRecordingId ?: return@LaunchedEffect
            navController.navigateToStudio(recordingId)
            homeViewModel.consumeOpenStudioNavigation()
        }

        LaunchedEffect(recordEntryViewModel) {
            recordEntryViewModel.warmupOnHomeVisible()
        }

        LaunchedEffect(recordEntryViewModel) {
            recordEntryViewModel.events.collect { event ->
                when (event) {
                    is HomeRecordEntryEvent.NavigateToRecord -> {
                        navController.navigate(
                            Screen.Record.createRoute(profileId = event.profileId?.toString()),
                        )
                    }

                    is HomeRecordEntryEvent.ShowModelRequired -> {
                        dialogContent =
                            when (event.reason) {
                                ModelReadinessUnavailableReason.MISSING_MODEL_FILES -> {
                                    RecordEntryDialogContent(
                                        title = context.getString(R.string.record_entry_model_required_title),
                                        message = context.getString(R.string.record_entry_model_required_message),
                                        confirmLabelRes = R.string.record_entry_download_model,
                                        navigateToTranscriptionDownload = true,
                                    )
                                }

                                ModelReadinessUnavailableReason.INTEGRITY_MISMATCH -> {
                                    RecordEntryDialogContent(
                                        title = context.getString(R.string.record_entry_model_integrity_failed_title),
                                        message = context.getString(R.string.record_entry_model_integrity_failed_message),
                                        confirmLabelRes = R.string.record_entry_download_model,
                                        navigateToTranscriptionDownload = true,
                                    )
                                }
                            }
                    }

                    is HomeRecordEntryEvent.ShowError -> {
                        dialogContent =
                            RecordEntryDialogContent(
                                title = context.getString(R.string.record_entry_model_check_error_title),
                                message = context.getString(R.string.record_entry_model_check_error_message, event.message),
                                confirmLabelRes = R.string.dismiss,
                                navigateToTranscriptionDownload = false,
                            )
                    }
                }
            }
        }

        HomeScreen(
            onRecordingClick = { id ->
                navController.navigateToStudio(id)
            },
            onRecordClick = {
                recordEntryViewModel.onRecordTapped()
            },
            onQuickStartClick = { profileId ->
                recordEntryViewModel.onRecordTapped(profileId)
            },
            onImportAudio = { uri ->
                homeViewModel.importAudio(uri)
            },
            isRecordEntryChecking = readinessState is ModelReadinessState.Checking,
            onSettingsClick = {
                navController.navigate(Screen.Settings.route)
            },
        )

        if (dialogContent != null) {
            val content = dialogContent
            AnimatedAlertDialog(
                onDismissRequest = { dialogContent = null },
                title = { Text(content?.title.orEmpty()) },
                text = { Text(content?.message.orEmpty()) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val shouldOpenDownload = content?.navigateToTranscriptionDownload == true
                            dialogContent = null
                            if (shouldOpenDownload) {
                                navController.navigate(Screen.TranscriptionSettings.createRoute(autoDownload = true))
                            }
                        },
                    ) {
                        Text(stringResource(content?.confirmLabelRes ?: R.string.dismiss))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { dialogContent = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }

    composable(
        route = Screen.Record.route,
        arguments =
            listOf(
                navArgument("autoStart") {
                    type = NavType.BoolType
                    defaultValue = true
                },
                navArgument("profileId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) { backStackEntry ->
        val autoStart = backStackEntry.arguments?.getBoolean("autoStart") ?: true
        RecordScreen(
            onNavigateBack = { navController.popBackStack() },
            onRecordingComplete = { recordingId ->
                navController.navigateToStudio(recordingId) {
                    popUpTo(Screen.Home.route) { inclusive = false }
                }
            },
            autoStart = autoStart,
        )
    }

    composable(
        route = Screen.ProcessingStudio.route,
        arguments =
            listOf(
                navArgument("recordingId") { type = NavType.StringType },
            ),
    ) { backStackEntry ->
        val recordingId = backStackEntry.arguments?.getString("recordingId") ?: ""
        ProcessingStudioScreen(
            recordingId = recordingId,
            onNavigateBack = { navController.popBackStack() },
        )
    }
}
