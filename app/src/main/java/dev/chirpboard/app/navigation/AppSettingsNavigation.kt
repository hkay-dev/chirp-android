package dev.chirpboard.app.navigation

import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.chirpboard.app.R
import dev.chirpboard.app.debug.DevMenuScreen
import dev.chirpboard.app.feature.llm.settings.LlmSettingsScreen
import dev.chirpboard.app.feature.llm.settings.ProcessingPromptEditorScreen
import dev.chirpboard.app.feature.llm.settings.ProcessingPromptEditorViewModel
import dev.chirpboard.app.feature.llm.settings.ProcessingPromptSettingsScreen
import dev.chirpboard.app.feature.obsidian.settings.ObsidianSettingsScreen
import dev.chirpboard.app.feature.recording.ui.profile.ProfileEditorScreen
import dev.chirpboard.app.feature.recording.ui.profile.ProfileListScreen
import dev.chirpboard.app.feature.recording.ui.replacement.WordReplacementsScreen
import dev.chirpboard.app.feature.recording.ui.tag.TagManagementScreen
import dev.chirpboard.app.feature.transcription.settings.TranscriptionSettingsScreen
import dev.chirpboard.app.shortcut.ProfilePinShortcutViewModel
import dev.chirpboard.app.ui.settings.AboutScreen
import dev.chirpboard.app.ui.settings.AudioSettingsScreen
import dev.chirpboard.app.ui.settings.BackupRestoreScreen
import dev.chirpboard.app.ui.settings.KeyboardSettingsScreen
import dev.chirpboard.app.ui.settings.SettingsScreen

internal fun NavGraphBuilder.appSettingsNavigation(navController: NavHostController) {
    composable(Screen.Settings.route) {
        SettingsScreen(
            onNavigateBack = { navController.popBackStack() },
            onNavigateToTranscriptionSettings = {
                navController.navigate(Screen.TranscriptionSettings.createRoute())
            },
            onNavigateToLlmSettings = { navController.navigate(Screen.LlmSettings.route) },
            onNavigateToAudioSettings = { navController.navigate(Screen.AudioSettings.route) },
            onNavigateToObsidianSettings = { navController.navigate(Screen.ObsidianSettings.route) },
            onNavigateToKeyboardSettings = { navController.navigate(Screen.KeyboardSettings.route) },
            onNavigateToBackupRestore = { navController.navigate(Screen.BackupRestore.route) },
            onNavigateToProfiles = { navController.navigate(Screen.Profiles.route) },
            onNavigateToTags = { navController.navigate(Screen.Tags.route) },
            onNavigateToWordReplacements = { navController.navigate(Screen.WordReplacements.route) },
            onNavigateToAbout = { navController.navigate(Screen.About.route) },
            onNavigateToDevMenu = { navController.navigate(Screen.DevMenu.route) },
        )
    }

    composable(
        route = Screen.TranscriptionSettings.route,
        arguments =
            listOf(
                navArgument("autoDownload") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
    ) {
        TranscriptionSettingsScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable(Screen.LlmSettings.route) {
        LlmSettingsScreen(
            onNavigateBack = { navController.popBackStack() },
            onNavigateToPromptSettings = { navController.navigate(Screen.ProcessingPromptSettings.route) },
        )
    }

    composable(Screen.ProcessingPromptSettings.route) {
        ProcessingPromptSettingsScreen(
            onNavigateBack = { navController.popBackStack() },
            onEditPreset = { presetId ->
                navController.navigate(Screen.ProcessingPromptEditor.createRoute(presetId))
            },
            onAddPreset = {
                navController.navigate(
                    Screen.ProcessingPromptEditor.createRoute(ProcessingPromptEditorViewModel.NEW_PRESET_ID),
                )
            },
        )
    }

    composable(
        route = Screen.ProcessingPromptEditor.route,
        arguments =
            listOf(
                navArgument("presetId") {
                    type = NavType.StringType
                },
            ),
    ) {
        ProcessingPromptEditorScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable(Screen.AudioSettings.route) {
        AudioSettingsScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable(Screen.ObsidianSettings.route) {
        ObsidianSettingsScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable(Screen.KeyboardSettings.route) {
        KeyboardSettingsScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable(Screen.BackupRestore.route) {
        BackupRestoreScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable(Screen.Profiles.route) {
        val context = LocalContext.current
        val pinShortcutViewModel: ProfilePinShortcutViewModel = hiltViewModel()
        ProfileListScreen(
            onProfileClick = { profileId ->
                navController.navigate(Screen.ProfileEditor.createRoute(profileId.toString()))
            },
            onAddProfile = {
                navController.navigate(Screen.ProfileEditor.createRoute())
            },
            onNavigateBack = { navController.popBackStack() },
            onAddToHomeScreen = { profileId ->
                if (pinShortcutViewModel.isRequestPinShortcutSupported()) {
                    pinShortcutViewModel.requestPinShortcut(profileId)
                } else {
                    Toast.makeText(
                        context,
                        R.string.add_to_home_unsupported,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
    }

    composable(
        route = Screen.ProfileEditor.route,
        arguments =
            listOf(
                navArgument("profileId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) {
        ProfileEditorScreen(
            onNavigateBack = { navController.popBackStack() },
            onSaved = { navController.popBackStack() },
        )
    }

    composable(Screen.Tags.route) {
        TagManagementScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable(Screen.WordReplacements.route) {
        WordReplacementsScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable(Screen.About.route) {
        AboutScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable(Screen.DevMenu.route) {
        DevMenuScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }
}
