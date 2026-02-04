package dev.chirpboard.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.chirpboard.app.AboutScreen
import dev.chirpboard.app.KeyboardSettingsScreen
import dev.chirpboard.app.NewSettingsScreen
import dev.chirpboard.app.feature.llm.settings.LlmSettingsScreen
import dev.chirpboard.app.feature.obsidian.settings.ObsidianSettingsScreen
import dev.chirpboard.app.feature.recording.ui.HomeScreen
import dev.chirpboard.app.feature.recording.ui.RecordScreen
import dev.chirpboard.app.feature.recording.ui.RecordingDetailScreen
import dev.chirpboard.app.feature.recording.ui.profile.ProfileEditorScreen
import dev.chirpboard.app.feature.recording.ui.profile.ProfileListScreen
import dev.chirpboard.app.feature.recording.ui.replacement.WordReplacementsScreen
import dev.chirpboard.app.feature.recording.ui.tag.TagManagementScreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.chirpboard.app.download.ModelDownloader
import java.util.UUID

/**
 * Navigation routes for the app.
 */
sealed class Screen(val route: String) {
    object Home : Screen("home")
    
    object Record : Screen("record?autoStart={autoStart}") {
        fun createRoute(autoStart: Boolean = true) = "record?autoStart=$autoStart"
    }
    
    object RecordingDetail : Screen("recording/{recordingId}") {
        fun createRoute(recordingId: String) = "recording/$recordingId"
    }
    
    object Settings : Screen("settings")
    object LlmSettings : Screen("settings/llm")
    object ObsidianSettings : Screen("settings/obsidian")
    object KeyboardSettings : Screen("settings/keyboard")
    
    object Profiles : Screen("profiles")
    
    object ProfileEditor : Screen("profiles/edit?profileId={profileId}") {
        fun createRoute(profileId: String? = null) = 
            if (profileId != null) "profiles/edit?profileId=$profileId" 
            else "profiles/edit"
    }
    
    object Tags : Screen("tags")
    object WordReplacements : Screen("word-replacements")
    object About : Screen("about")
}

/**
 * Main navigation host for the app.
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = modifier
    ) {
        // Home Screen
        composable(Screen.Home.route) {
            val context = LocalContext.current
            var showModelRequiredDialog by remember { mutableStateOf(false) }
            
            HomeScreen(
                onRecordingClick = { recording ->
                    navController.navigate(Screen.RecordingDetail.createRoute(recording.id.toString()))
                },
                onRecordClick = {
                    if (ModelDownloader(context).isModelDownloaded()) {
                        navController.navigate(Screen.Record.createRoute())
                    } else {
                        showModelRequiredDialog = true
                    }
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                }
            )
            
            if (showModelRequiredDialog) {
                AlertDialog(
                    onDismissRequest = { showModelRequiredDialog = false },
                    title = { Text("Model Required") },
                    text = { Text("The transcription model must be downloaded before you can record. Go to Settings to download it.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showModelRequiredDialog = false
                                navController.navigate(Screen.Settings.route)
                            }
                        ) {
                            Text("Go to Settings")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showModelRequiredDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
        
        // Record Screen (full-screen recording interface)
        composable(
            route = Screen.Record.route,
            arguments = listOf(
                navArgument("autoStart") {
                    type = NavType.BoolType
                    defaultValue = true
                }
            )
        ) { backStackEntry ->
            val autoStart = backStackEntry.arguments?.getBoolean("autoStart") ?: true
            RecordScreen(
                onNavigateBack = { navController.popBackStack() },
                onRecordingComplete = { recordingId ->
                    // Pop the Record screen and navigate to the recording's detail/transcribe screen
                    navController.popBackStack()
                    navController.navigate(Screen.RecordingDetail.createRoute(recordingId))
                },
                autoStart = autoStart
            )
        }
        
        // Recording Detail Screen
        composable(
            route = Screen.RecordingDetail.route,
            arguments = listOf(
                navArgument("recordingId") { type = NavType.StringType }
            )
        ) {
            RecordingDetailScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
        
        // Settings Screen
        composable(Screen.Settings.route) {
            NewSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToLlmSettings = { navController.navigate(Screen.LlmSettings.route) },
                onNavigateToObsidianSettings = { navController.navigate(Screen.ObsidianSettings.route) },
                onNavigateToKeyboardSettings = { navController.navigate(Screen.KeyboardSettings.route) },
                onNavigateToProfiles = { navController.navigate(Screen.Profiles.route) },
                onNavigateToTags = { navController.navigate(Screen.Tags.route) },
                onNavigateToWordReplacements = { navController.navigate(Screen.WordReplacements.route) },
                onNavigateToAbout = { navController.navigate(Screen.About.route) }
            )
        }
        
        // LLM Settings Screen
        composable(Screen.LlmSettings.route) {
            LlmSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        // Obsidian Settings Screen
        composable(Screen.ObsidianSettings.route) {
            ObsidianSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        // Keyboard Settings Screen
        composable(Screen.KeyboardSettings.route) {
            KeyboardSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        // Profiles List Screen
        composable(Screen.Profiles.route) {
            ProfileListScreen(
                onProfileClick = { profileId ->
                    navController.navigate(Screen.ProfileEditor.createRoute(profileId.toString()))
                },
                onAddProfile = {
                    navController.navigate(Screen.ProfileEditor.createRoute())
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        // Profile Editor Screen
        composable(
            route = Screen.ProfileEditor.route,
            arguments = listOf(
                navArgument("profileId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) {
            ProfileEditorScreen(
                onNavigateBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }
        
        // Tags Management Screen
        composable(Screen.Tags.route) {
            TagManagementScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        // Word Replacements Screen
        composable(Screen.WordReplacements.route) {
            WordReplacementsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        // About Screen
        composable(Screen.About.route) {
            AboutScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
