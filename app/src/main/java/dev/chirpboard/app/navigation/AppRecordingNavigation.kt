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
import dev.chirpboard.app.core.storage.AllFilesAccessRequester
import dev.chirpboard.app.core.ui.components.AnimatedAlertDialog
import dev.chirpboard.app.feature.recording.ui.HomeScreen
import dev.chirpboard.app.feature.recording.ui.HomeViewModel
import dev.chirpboard.app.feature.recording.ui.RecordScreen
import dev.chirpboard.app.feature.studio.ProcessingStudioScreen

/**
 * What the dialog's confirm button does when the model isn't ready (ERR-22 follow-up):
 * - [DOWNLOAD_MODEL] routes to the Transcription settings download flow;
 * - [GRANT_STORAGE] launches the system All-Files-Access page so a previously-downloaded
 *   model in public storage becomes readable again;
 * - [DISMISS] just closes the dialog (errors with no actionable follow-up).
 */
internal enum class RecordEntryConfirmAction {
    DOWNLOAD_MODEL,
    GRANT_STORAGE,
    DISMISS,
}

internal data class RecordEntryDialogContent(
    val title: String,
    val message: String,
    val confirmLabelRes: Int,
    val confirmAction: RecordEntryConfirmAction,
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
            recordEntryViewModel.verifyModelOnHomeVisible()
        }

        LaunchedEffect(recordEntryViewModel) {
            recordEntryViewModel.events.collect { event ->
                when (event) {
                    is HomeRecordEntryEvent.NavigateToRecord -> {
                        navController.navigate(
                            Screen.Record.createRoute(
                                autoStart = event.autoStart,
                                profileId = event.profileId?.toString(),
                            ),
                        ) {
                            // A double-tap on the record button emits two events; single-top
                            // keeps the second from stacking a duplicate Record entry.
                            launchSingleTop = true
                        }
                    }

                    is HomeRecordEntryEvent.ShowModelRequired -> {
                        dialogContent =
                            when (event.reason) {
                                ModelReadinessUnavailableReason.MISSING_MODEL_FILES -> {
                                    RecordEntryDialogContent(
                                        title = context.getString(R.string.record_entry_model_required_title),
                                        message = context.getString(R.string.record_entry_model_required_message),
                                        confirmLabelRes = R.string.record_entry_download_model,
                                        confirmAction = RecordEntryConfirmAction.DOWNLOAD_MODEL,
                                    )
                                }

                                ModelReadinessUnavailableReason.INTEGRITY_MISMATCH -> {
                                    RecordEntryDialogContent(
                                        title = context.getString(R.string.record_entry_model_integrity_failed_title),
                                        message = context.getString(R.string.record_entry_model_integrity_failed_message),
                                        confirmLabelRes = R.string.record_entry_download_model,
                                        confirmAction = RecordEntryConfirmAction.DOWNLOAD_MODEL,
                                    )
                                }

                                ModelReadinessUnavailableReason.STORAGE_ACCESS_DENIED -> {
                                    // ERR-22 follow-up: the model lives in public storage, so the
                                    // fix is to grant All-Files-Access — offer to open that settings
                                    // page instead of dead-ending on Dismiss.
                                    RecordEntryDialogContent(
                                        title = context.getString(R.string.record_entry_model_storage_denied_title),
                                        message = context.getString(R.string.record_entry_model_storage_denied_message),
                                        confirmLabelRes = R.string.record_entry_grant_storage_access,
                                        confirmAction = RecordEntryConfirmAction.GRANT_STORAGE,
                                    )
                                }
                            }
                    }

                    is HomeRecordEntryEvent.ShowError -> {
                        // I18N-05: event.message carries developer diagnostics; log it and show
                        // the actionable resource copy instead of interpolating raw text.
                        android.util.Log.w("AppRecordingNavigation", "Model readiness check failed: ${event.message}")
                        dialogContent =
                            RecordEntryDialogContent(
                                title = context.getString(R.string.record_entry_model_check_error_title),
                                message = context.getString(R.string.record_entry_model_check_error_message),
                                confirmLabelRes = R.string.dismiss,
                                confirmAction = RecordEntryConfirmAction.DISMISS,
                            )
                    }
                }
            }
        }

        HomeScreen(
            onRecordingClick = { item ->
                if (item.isLiveCapture) {
                    navController.navigate(Screen.Record.createRoute(autoStart = false)) {
                        launchSingleTop = true
                    }
                } else {
                    navController.navigateToStudio(item.id)
                }
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
                            val action = content?.confirmAction ?: RecordEntryConfirmAction.DISMISS
                            dialogContent = null
                            when (action) {
                                RecordEntryConfirmAction.DOWNLOAD_MODEL ->
                                    navController.navigate(
                                        Screen.TranscriptionSettings.createRoute(autoDownload = true),
                                    )
                                // The model is in public storage; opening the system
                                // All-Files-Access page lets the user grant read access. The next
                                // record tap re-runs the readiness check, so no extra state is kept.
                                // On builds where no settings surface launches, re-open the dialog
                                // with manual navigation steps instead of silently doing nothing.
                                RecordEntryConfirmAction.GRANT_STORAGE ->
                                    if (!AllFilesAccessRequester.openSettings(context)) {
                                        dialogContent =
                                            RecordEntryDialogContent(
                                                title =
                                                    context.getString(
                                                        R.string.record_entry_model_storage_denied_title,
                                                    ),
                                                message =
                                                    context.getString(
                                                        R.string.record_entry_storage_settings_unavailable,
                                                    ),
                                                confirmLabelRes = R.string.dismiss,
                                                confirmAction = RecordEntryConfirmAction.DISMISS,
                                            )
                                    }
                                RecordEntryConfirmAction.DISMISS -> Unit
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
