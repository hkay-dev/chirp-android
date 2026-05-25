package dev.chirpboard.app.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import dev.chirpboard.app.R
import dev.chirpboard.app.core.ui.components.EmptyState
import dev.chirpboard.app.core.ui.components.LoadingState
import dev.chirpboard.app.core.ui.motion.ChirpMotion
import kotlinx.coroutines.flow.collect

/**
 * Material 3 motion: shared axis forward/backward transitions.
 * Durations are intentionally matched and slightly long to mask frame hitches.
 */
private val navSlideDivisor = ChirpMotion.NAV_SLIDE_OFFSET_DIVISOR

/**
 * Main navigation host for the app.
 * Uses Material 3 fade-through transitions for all screen changes.
 */
@Composable
internal fun AppNavHost(
    navController: NavHostController = androidx.navigation.compose.rememberNavController(),
    incomingSharedAudioRequest: SharedAudioRequest? = null,
    onStartupPromptGateChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val sharedAudioHandoffViewModel: SharedAudioHandoffViewModel = hiltViewModel()
    val sharedAudioState by sharedAudioHandoffViewModel.uiState.collectAsStateWithLifecycle()
    val sharedAudioNavigationTarget by sharedAudioHandoffViewModel.navigationTarget.collectAsStateWithLifecycle()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    LaunchedEffect(incomingSharedAudioRequest?.token) {
        sharedAudioHandoffViewModel.onIncomingRequest(incomingSharedAudioRequest)
    }

    LaunchedEffect(sharedAudioNavigationTarget) {
        val target = sharedAudioNavigationTarget ?: return@LaunchedEffect
        navController.navigate(Screen.ProcessingStudio.createRoute(target.toString())) {
            popUpTo(Screen.Home.route) { inclusive = false }
            launchSingleTop = true
        }
        sharedAudioHandoffViewModel.onNavigationHandled()
    }

    LaunchedEffect(sharedAudioState, sharedAudioNavigationTarget, currentRoute) {
        val canShowStartupPrompts =
            sharedAudioState is SharedAudioIntakeState.Idle &&
                sharedAudioNavigationTarget == null &&
                currentRoute == Screen.Home.route
        onStartupPromptGateChanged(canShowStartupPrompts)
    }

    Box(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.fillMaxSize(),
            enterTransition = {
                fadeIn(
                    animationSpec =
                        tween(
                            durationMillis = ChirpMotion.NAV_TRANSITION_MS,
                            easing = FastOutSlowInEasing,
                        ),
                ) +
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Start,
                        animationSpec =
                            tween(
                                durationMillis = ChirpMotion.NAV_TRANSITION_MS,
                                easing = FastOutSlowInEasing,
                            ),
                        initialOffset = { it / navSlideDivisor },
                    )
            },
            exitTransition = {
                fadeOut(
                    animationSpec =
                        tween(
                            durationMillis = ChirpMotion.NAV_FADE_MS,
                            easing = FastOutSlowInEasing,
                        ),
                ) +
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Start,
                        animationSpec =
                            tween(
                                durationMillis = ChirpMotion.NAV_FADE_MS,
                                easing = FastOutSlowInEasing,
                            ),
                        targetOffset = { it / navSlideDivisor },
                    )
            },
            popEnterTransition = {
                fadeIn(
                    animationSpec =
                        tween(
                            durationMillis = ChirpMotion.NAV_TRANSITION_MS,
                            easing = FastOutSlowInEasing,
                        ),
                ) +
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.End,
                        animationSpec =
                            tween(
                                durationMillis = ChirpMotion.NAV_TRANSITION_MS,
                                easing = FastOutSlowInEasing,
                            ),
                        initialOffset = { it / navSlideDivisor },
                    )
            },
            popExitTransition = {
                fadeOut(
                    animationSpec =
                        tween(
                            durationMillis = ChirpMotion.NAV_FADE_MS,
                            easing = FastOutSlowInEasing,
                        ),
                ) +
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.End,
                        animationSpec =
                            tween(
                                durationMillis = ChirpMotion.NAV_FADE_MS,
                                easing = FastOutSlowInEasing,
                            ),
                        targetOffset = { it / navSlideDivisor },
                    )
            },
        ) {
            appRecordingNavigation(navController)
            appSettingsNavigation(navController)
        }

        when (val state = sharedAudioState) {
            SharedAudioIntakeState.Idle -> {
                Unit
            }

            is SharedAudioIntakeState.Loading -> {
                LoadingState(
                    message = stringResource(R.string.shared_audio_handoff_loading),
                    modifier = Modifier.fillMaxSize(),
                )
            }

            is SharedAudioIntakeState.Failure -> {
                SharedAudioIntakeFailure(
                    message = state.message,
                    onRetry = sharedAudioHandoffViewModel::retry,
                    onDismiss = sharedAudioHandoffViewModel::dismissFailure,
                )
            }
        }
    }
}

@Composable
private fun SharedAudioIntakeFailure(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        EmptyState(
            icon = Icons.Default.AudioFile,
            title = stringResource(R.string.shared_audio_handoff_failed_title),
            description = message,
            actionLabel = stringResource(R.string.shared_audio_handoff_retry),
            onAction = onRetry,
            modifier = Modifier.fillMaxSize(),
        )
        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
        ) {
            Text(stringResource(R.string.dismiss))
        }
    }
}
