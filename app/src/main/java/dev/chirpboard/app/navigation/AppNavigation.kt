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
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import dev.chirpboard.app.core.playback.RecordingPlaybackController
import dev.chirpboard.app.core.playback.RecordingPlaybackState
import dev.chirpboard.app.core.ui.components.EmptyState
import dev.chirpboard.app.core.ui.motion.ChirpMotion
import dev.chirpboard.app.core.ui.motion.ChirpMotion.layoutSizeSpring
import dev.chirpboard.app.core.ui.motion.ChirpMotion.miniPlayerHideTransition
import dev.chirpboard.app.core.ui.motion.ChirpMotion.miniPlayerRevealTransition
import dev.chirpboard.app.core.ui.playback.RecordingMiniPlayerBar
import dev.chirpboard.app.core.ui.playback.rememberRecordingPlaybackController
import dev.chirpboard.app.core.ui.playback.shouldShowGlobalMiniPlayer
import java.util.UUID
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold

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
    pendingDeepLinkRoute: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
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
    // Project the high-frequency playback state down to only the fields the mini-player
    // *visibility* decision depends on (recordingId/active/loading/error), deduped, so the
    // 10 Hz position tick no longer recomposes the navigation root (the parent of every
    // screen). The full state — and its last-active latching — is collected inside the
    // mini-player scope below (CMP-12).
    val visibilityState by remember(playbackController) {
        playbackController.state
            .map { it.toVisibilityProjection() }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(RecordingPlaybackState().toVisibilityProjection())
    val showGlobalMiniPlayer =
        shouldShowGlobalMiniPlayer(
            playbackState = visibilityState,
            currentRoute = currentRoute,
            studioRecordingId = studioRecordingId,
        )
    val showSharedAudioOverlay =
        sharedAudioState is SharedAudioIntakeState.Loading ||
            sharedAudioState is SharedAudioIntakeState.Failure

    LaunchedEffect(incomingSharedAudioRequest?.token) {
        sharedAudioHandoffViewModel.onIncomingRequest(incomingSharedAudioRequest)
    }

    // One-shot deep links from MainActivity (keyboard-settings gear alias / launcher
    // shortcut). Consumed immediately so recompositions and rotations never re-navigate.
    LaunchedEffect(pendingDeepLinkRoute) {
        val route = pendingDeepLinkRoute ?: return@LaunchedEffect
        navController.navigate(route) {
            launchSingleTop = true
            popUpTo(Screen.Home.route) { inclusive = false }
        }
        onDeepLinkConsumed()
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
                // The full (10 Hz) playback state — including position ticks — is collected
                // here, inside the mini-player scope, so those ticks recompose only the bar
                // and never the navigation root above (CMP-12).
                GlobalMiniPlayer(
                    playbackController = playbackController,
                    onOpenRecording = { id -> navController.navigateToStudio(id) },
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

/**
 * Reduces [RecordingPlaybackState] to only the fields [shouldShowGlobalMiniPlayer] reads,
 * zeroing the high-frequency position/duration so a deduped projection ignores 10 Hz ticks
 * while preserving the exact visibility decision (CMP-12).
 */
internal fun RecordingPlaybackState.toVisibilityProjection(): RecordingPlaybackState =
    RecordingPlaybackState(
        recordingId = recordingId,
        isLoading = isLoading,
        errorMessage = errorMessage,
        hasStartedPlayback = hasStartedPlayback,
    )

/**
 * Owns the full (position-ticking) playback state inside the mini-player's own scope so the
 * 10 Hz tick recomposes only the bar, not the navigation root (CMP-12). The last active
 * state is latched so the bar keeps its content during the fade-out instead of blanking.
 */
@Composable
private fun GlobalMiniPlayer(
    playbackController: RecordingPlaybackController,
    onOpenRecording: (UUID) -> Unit,
) {
    // Latch in the flow, not with a composition-time write (that was a backwards
    // write). Holding every non-idle state also fixes error frames: the old
    // isActive || isLoading condition never latched them (isActive is false once
    // errorMessage is set), so the bar stayed visible showing a stale spinner
    // instead of the failure message it exists to keep on screen (AUD-12).
    val displayState by remember(playbackController) {
        playbackController.state.runningFold(playbackController.state.value) { latched, next ->
            if (next.isIdle) latched else next
        }
    }.collectAsStateWithLifecycle(playbackController.state.value)
    RecordingMiniPlayerBar(
        state = displayState,
        onPlayPause = playbackController::togglePlayPause,
        onSeek = playbackController::seekTo,
        onStop = playbackController::stop,
        onOpenRecording = {
            displayState.recordingId?.let(onOpenRecording)
        },
        modifier = Modifier.fillMaxWidth(),
    )
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
            icon = Icons.Rounded.AudioFile,
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
