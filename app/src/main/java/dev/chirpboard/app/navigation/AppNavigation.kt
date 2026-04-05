package dev.chirpboard.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.chirpboard.app.core.ui.components.AnimatedAlertDialog
import dev.chirpboard.app.debug.DevMenuScreen
import dev.chirpboard.app.download.ModelReadinessState
import dev.chirpboard.app.download.ModelReadinessUnavailableReason
import dev.chirpboard.app.feature.llm.settings.LlmSettingsScreen
import dev.chirpboard.app.feature.obsidian.settings.ObsidianSettingsScreen
import dev.chirpboard.app.feature.recording.ui.HomeScreen
import dev.chirpboard.app.feature.recording.ui.RecordScreen
import dev.chirpboard.app.feature.recording.ui.RecordingDetailScreen
import dev.chirpboard.app.feature.recording.ui.profile.ProfileEditorScreen
import dev.chirpboard.app.feature.recording.ui.profile.ProfileListScreen
import dev.chirpboard.app.feature.recording.ui.replacement.WordReplacementsScreen
import dev.chirpboard.app.feature.recording.ui.tag.TagManagementScreen
import dev.chirpboard.app.feature.transcription.settings.TranscriptionSettingsScreen
import dev.chirpboard.app.ui.settings.AboutScreen
import dev.chirpboard.app.ui.settings.AudioSettingsScreen
import dev.chirpboard.app.ui.settings.KeyboardSettingsScreen
import dev.chirpboard.app.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.collect
import java.util.UUID

/**
 * Material 3 motion: shared axis forward/backward transitions.
 */
private const val TRANSITION_DURATION = 300
private const val FADE_DURATION = 250
private const val SLIDE_OFFSET_DIVISOR = 10

private data class RecordEntryDialogContent(
    val title: String,
    val message: String,
    val openSettingsOnConfirm: Boolean,
)

/**
 * Navigation routes for the app.
 */
sealed class Screen(
    val route: String,
) {
    object Home : Screen("home")

    object Record : Screen("record?autoStart={autoStart}") {
        fun createRoute(autoStart: Boolean = true) = "record?autoStart=$autoStart"
    }

    object RecordingDetail : Screen("recording/{recordingId}") {
        fun createRoute(recordingId: String) = "recording/$recordingId"
    }

    object Settings : Screen("settings")

    object TranscriptionSettings : Screen("settings/transcription")

    object LlmSettings : Screen("settings/llm")

    object AudioSettings : Screen("settings/audio")

    object ObsidianSettings : Screen("settings/obsidian")

    object KeyboardSettings : Screen("settings/keyboard")

    object Profiles : Screen("profiles")

    object ProfileEditor : Screen("profiles/edit?profileId={profileId}") {
        fun createRoute(profileId: String? = null) =
            if (profileId != null) {
                "profiles/edit?profileId=$profileId"
            } else {
                "profiles/edit"
            }
    }

    object Tags : Screen("tags")

    object WordReplacements : Screen("word-replacements")

    object About : Screen("about")

    object DevMenu : Screen("dev-menu")
}

/**
 * Main navigation host for the app.
 * Uses Material 3 fade-through transitions for all screen changes.
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = modifier,
        enterTransition = {
            fadeIn(
                animationSpec =
                    tween(
                        durationMillis = TRANSITION_DURATION,
                        easing = FastOutSlowInEasing,
                    ),
            ) +
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec =
                        tween(
                            durationMillis = TRANSITION_DURATION,
                            easing = FastOutSlowInEasing,
                        ),
                    initialOffset = { it / SLIDE_OFFSET_DIVISOR },
                )
        },
        exitTransition = {
            fadeOut(
                animationSpec =
                    tween(
                        durationMillis = FADE_DURATION,
                        easing = FastOutSlowInEasing,
                    ),
            ) +
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec =
                        tween(
                            durationMillis = FADE_DURATION,
                            easing = FastOutSlowInEasing,
                        ),
                    targetOffset = { it / SLIDE_OFFSET_DIVISOR },
                )
        },
        popEnterTransition = {
            fadeIn(
                animationSpec =
                    tween(
                        durationMillis = TRANSITION_DURATION,
                        easing = FastOutSlowInEasing,
                    ),
            ) +
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec =
                        tween(
                            durationMillis = TRANSITION_DURATION,
                            easing = FastOutSlowInEasing,
                        ),
                    initialOffset = { it / SLIDE_OFFSET_DIVISOR },
                )
        },
        popExitTransition = {
            fadeOut(
                animationSpec =
                    tween(
                        durationMillis = FADE_DURATION,
                        easing = FastOutSlowInEasing,
                    ),
            ) +
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec =
                        tween(
                            durationMillis = FADE_DURATION,
                            easing = FastOutSlowInEasing,
                        ),
                    targetOffset = { it / SLIDE_OFFSET_DIVISOR },
                )
        },
    ) {
        // Home Screen
        composable(Screen.Home.route) {
            val recordEntryViewModel: HomeRecordEntryViewModel = hiltViewModel()
            val readinessState by recordEntryViewModel.readinessState.collectAsStateWithLifecycle()
            var dialogContent by remember { mutableStateOf<RecordEntryDialogContent?>(null) }

            LaunchedEffect(recordEntryViewModel) {
                recordEntryViewModel.warmupOnHomeVisible()
            }

            LaunchedEffect(recordEntryViewModel) {
                recordEntryViewModel.events.collect { event ->
                    when (event) {
                        HomeRecordEntryEvent.NavigateToRecord -> {
                            navController.navigate(Screen.Record.createRoute())
                        }

                        is HomeRecordEntryEvent.ShowModelRequired -> {
                            dialogContent =
                                when (event.reason) {
                                    ModelReadinessUnavailableReason.MISSING_MODEL_FILES -> {
                                        RecordEntryDialogContent(
                                            title = "Model Required",
                                            message = "The transcription model must be downloaded before you can record. Go to Settings to download it.",
                                            openSettingsOnConfirm = true,
                                        )
                                    }

                                    ModelReadinessUnavailableReason.INTEGRITY_MISMATCH -> {
                                        RecordEntryDialogContent(
                                            title = "Model Integrity Check Failed",
                                            message = "The local model files look corrupted. Re-download the model from Settings before recording.",
                                            openSettingsOnConfirm = true,
                                        )
                                    }
                                }
                        }

                        is HomeRecordEntryEvent.ShowError -> {
                            dialogContent =
                                RecordEntryDialogContent(
                                    title = "Model Check Error",
                                    message = "Could not verify model readiness. ${event.message}",
                                    openSettingsOnConfirm = false,
                                )
                        }
                    }
                }
            }

            HomeScreen(
                onRecordingClick = { recording ->
                    navController.navigate(Screen.RecordingDetail.createRoute(recording.id.toString()))
                },
                onRecordClick = {
                    recordEntryViewModel.onRecordTapped()
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
                                val shouldOpenSettings = content?.openSettingsOnConfirm == true
                                dialogContent = null
                                if (shouldOpenSettings) {
                                    navController.navigate(Screen.Settings.route)
                                }
                            },
                        ) {
                            Text(if (content?.openSettingsOnConfirm == true) "Go to Settings" else "Dismiss")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { dialogContent = null }) {
                            Text("Cancel")
                        }
                    },
                )
            }
        }

        // Record Screen (full-screen recording interface)
        composable(
            route = Screen.Record.route,
            arguments =
                listOf(
                    navArgument("autoStart") {
                        type = NavType.BoolType
                        defaultValue = true
                    },
                ),
        ) { backStackEntry ->
            val autoStart = backStackEntry.arguments?.getBoolean("autoStart") ?: true
            RecordScreen(
                onNavigateBack = { navController.popBackStack() },
                onRecordingComplete = { recordingId ->
                    // Pop the Record screen and navigate to the recording's detail/transcribe screen
                    navController.popBackStack()
                    navController.navigate(Screen.RecordingDetail.createRoute(recordingId))
                },
                autoStart = autoStart,
            )
        }

        // Recording Detail Screen
        composable(
            route = Screen.RecordingDetail.route,
            arguments =
                listOf(
                    navArgument("recordingId") { type = NavType.StringType },
                ),
        ) {
            RecordingDetailScreen(
                onBackClick = { navController.popBackStack() },
            )
        }

        // Settings Screen
        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToTranscriptionSettings = { navController.navigate(Screen.TranscriptionSettings.route) },
                onNavigateToLlmSettings = { navController.navigate(Screen.LlmSettings.route) },
                onNavigateToAudioSettings = { navController.navigate(Screen.AudioSettings.route) },
                onNavigateToObsidianSettings = { navController.navigate(Screen.ObsidianSettings.route) },
                onNavigateToKeyboardSettings = { navController.navigate(Screen.KeyboardSettings.route) },
                onNavigateToProfiles = { navController.navigate(Screen.Profiles.route) },
                onNavigateToTags = { navController.navigate(Screen.Tags.route) },
                onNavigateToWordReplacements = { navController.navigate(Screen.WordReplacements.route) },
                onNavigateToAbout = { navController.navigate(Screen.About.route) },
                onNavigateToDevMenu = { navController.navigate(Screen.DevMenu.route) },
            )
        }

        // Transcription Settings Screen
        composable(Screen.TranscriptionSettings.route) {
            TranscriptionSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        // LLM Settings Screen
        composable(Screen.LlmSettings.route) {
            LlmSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        // Audio Settings Screen
        composable(Screen.AudioSettings.route) {
            AudioSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        // Obsidian Settings Screen
        composable(Screen.ObsidianSettings.route) {
            ObsidianSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        // Keyboard Settings Screen
        composable(Screen.KeyboardSettings.route) {
            KeyboardSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
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
                onNavigateBack = { navController.popBackStack() },
            )
        }

        // Profile Editor Screen
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

        // Tags Management Screen
        composable(Screen.Tags.route) {
            TagManagementScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        // Word Replacements Screen
        composable(Screen.WordReplacements.route) {
            WordReplacementsScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        // About Screen
        composable(Screen.About.route) {
            AboutScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        // Dev Menu - always available (controlled by Settings screen visibility)
        composable(Screen.DevMenu.route) {
            DevMenuScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
