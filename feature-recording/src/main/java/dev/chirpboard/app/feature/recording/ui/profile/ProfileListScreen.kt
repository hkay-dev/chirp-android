package dev.chirpboard.app.feature.recording.ui.profile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.core.ui.components.AnimatedAlertDialog
import dev.chirpboard.app.core.ui.components.ChirpPrimaryFab
import dev.chirpboard.app.core.ui.components.ChirpSettingsDetailScaffold
import dev.chirpboard.app.core.ui.components.EmptyState
import dev.chirpboard.app.core.ui.components.RepositoryErrorSnackbarEffect
import dev.chirpboard.app.core.ui.R as CoreR
import dev.chirpboard.app.core.ui.motion.animatePushDownLayout
import dev.chirpboard.app.core.ui.theme.ChirpSpacing
import dev.chirpboard.app.feature.recording.R
import java.util.UUID

@Stable
data class ProfileItemState(
    val profile: dev.chirpboard.app.data.entity.Profile,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileListScreen(
    viewModel: ProfilesViewModel = hiltViewModel(),
    onProfileClick: (UUID) -> Unit,
    onAddProfile: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    RepositoryErrorSnackbarEffect(
        errorMessage = errorMessage,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
    )

    // LIF-12: the pending delete decision survives rotation/process death; id-keyed and
    // re-resolved from the live list so it clears if the profile disappears.
    var profileToDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val profileToDelete =
        remember(profileToDeleteId, profiles) {
            profileToDeleteId?.let { id -> profiles.firstOrNull { it.id.toString() == id } }
        }

    // Delete confirmation dialog
    profileToDelete?.let { profile ->
        AnimatedAlertDialog(
            onDismissRequest = { profileToDeleteId = null },
            title = { Text(stringResource(R.string.rec_delete_profile)) },
            text = { Text(stringResource(R.string.rec_delete_profile_confirm, profile.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProfile(profile)
                        profileToDeleteId = null
                    },
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Text(stringResource(CoreR.string.rec_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { profileToDeleteId = null }) {
                    Text(stringResource(CoreR.string.rec_cancel))
                }
            },
        )
    }

    ChirpSettingsDetailScaffold(
        title = stringResource(R.string.rec_profiles),
        onNavigateBack = onNavigateBack,
        snackbarHostState = snackbarHostState,
        floatingActionButton = {
            ChirpPrimaryFab(onClick = onAddProfile) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.desc_add_profile),
                )
            }
        },
    ) { paddingValues ->
        AnimatedContent(
            modifier = Modifier.animatePushDownLayout(),
            targetState = profiles.isEmpty(),
            transitionSpec = {
                fadeIn(tween(200, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(200, easing = FastOutSlowInEasing))
            },
            label = "profiles_content",
        ) { isEmpty ->
            if (isEmpty) {
                EmptyState(
                    icon = Icons.Default.Person,
                    title = stringResource(R.string.rec_no_profiles_yet),
                    description = stringResource(R.string.rec_empty_profiles_description),
                    actionLabel = stringResource(R.string.rec_create_profile),
                    onAction = onAddProfile,
                    modifier = Modifier.padding(paddingValues),
                )
            } else {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                    contentPadding =
                        PaddingValues(
                            top = ChirpSpacing.Small,
                            bottom = ChirpSpacing.MiniPlayerClearance,
                        ),
                ) {
                    items(
                        items = profiles,
                        key = { it.id },
                        contentType = { "profile" },
                    ) { profile ->
                        ProfileCard(
                            profileItem = ProfileItemState(profile),
                            onClick = { onProfileClick(profile.id) },
                            onDelete = { profileToDeleteId = profile.id.toString() },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}
