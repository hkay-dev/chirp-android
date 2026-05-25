package dev.chirpboard.app.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import dev.chirpboard.app.core.ui.motion.ChirpMotion
import dev.chirpboard.app.core.ui.motion.ChirpMotion.layoutSizeSpring
import dev.chirpboard.app.core.ui.motion.ChirpMotion.miniPlayerHideTransition
import dev.chirpboard.app.core.ui.motion.ChirpMotion.miniPlayerRevealTransition
import dev.chirpboard.app.core.ui.playback.RecordingMiniPlayerBar
import dev.chirpboard.app.core.ui.playback.rememberRecordingPlaybackController
import dev.chirpboard.app.core.ui.playback.shouldShowGlobalMiniPlayer
import kotlinx.coroutines.flow.collect

/**
 * Material 3 motion: shared axis forward/backward transitions.
 * Durations are intentionally matched and slightly long to mask frame hitches.
 */
private val navSlideDivisor = ChirpMotion.NAV_SLIDE_OFFSET_DIVISOR

private val sharedAudioOverlayFadeIn = fadeIn(tween(ChirpMotion.STUDIO_REVEAL_MS, easing = FastOutSlowInEasing))
private val sharedAudioOverlayFadeOut = fadeOut(tween(ChirpMotion.STUDIO_HIDE_MS, easing = FastOutSlowInEasing))

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
    val studioRecordingId = currentBackStackEntry?.arguments?.getString("recordingId")
    val playbackController = rememberRecordingPlaybackController()
    val playbackState by playbackController.state.collectAsStateWithLifecycle()
    val showGlobalMiniPlayer =
        shouldShowGlobalMiniPlayer(
            playbackState = playbackState,
            currentRoute = currentRoute,
            studioRecordingId = studioRecordingId,
        )
    var miniPlayerDisplayState by remember { mutableStateOf(playbackState) }
    if (playbackState.isActive || playbackState.isLoading) {
        miniPlayerDisplayState = playbackState
    }
    val showSharedAudioOverlay =
        sharedAudioState is SharedAudioIntakeState.Loading ||
            sharedAudioState is SharedAudioIntakeState.Failure

    LaunchedEffect(incomingSharedAudioRequest?.token) {
        sharedAudioHandoffViewModel.onIncomingRequest(incomingSharedAudioRequest)
    }

    LaunchedEffect(sharedAudioNavigationTarget) {
        val target = sharedAudioNavigationTarget ?: return@LaunchedEffect
        navController.navigateToStudio(target) {
            popUpTo(Screen.Home.route) { inclusive = false }
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
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .animateContentSize(animationSpec = layoutSizeSpring),
        ) {
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                modifier = Modifier.weight(1f),
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

            AnimatedVisibility(
                visible = showGlobalMiniPlayer,
                enter = miniPlayerRevealTransition,
                exit = miniPlayerHideTransition,
            ) {
                RecordingMiniPlayerBar(
                    state = miniPlayerDisplayState,
                    onPlayPause = playbackController::togglePlayPause,
                    onSeek = playbackController::seekTo,
                    onStop = playbackController::stop,
                    onOpenRecording = {
                        playbackState.recordingId?.let { id ->
                            navController.navigateToStudio(id)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        AnimatedVisibility(
            visible = showSharedAudioOverlay,
            enter = sharedAudioOverlayFadeIn,
            exit = sharedAudioOverlayFadeOut,
        ) {
            SharedAudioIntakeOverlay(
                state = sharedAudioState,
                onRetry = sharedAudioHandoffViewModel::retry,
                onDismiss = sharedAudioHandoffViewModel::dismissFailure,
            )
        }
    }
}

@Composable
private fun SharedAudioIntakeOverlay(
    state: SharedAudioIntakeState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is SharedAudioIntakeState.Loading -> {
                Card(
                    modifier = Modifier.padding(horizontal = 32.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.shared_audio_handoff_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            is SharedAudioIntakeState.Failure -> {
                SharedAudioIntakeFailure(
                    message = state.message,
                    onRetry = onRetry,
                    onDismiss = onDismiss,
                )
            }

            SharedAudioIntakeState.Idle -> {
                Unit
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
