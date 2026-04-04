package dev.chirpboard.app.feature.recording.ui.profile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.chirpboard.app.core.ui.components.AnimatedAlertDialog
import dev.chirpboard.app.core.ui.components.EmptyState
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileListScreen(
    viewModel: ProfilesViewModel = hiltViewModel(),
    onProfileClick: (UUID) -> Unit,
    onAddProfile: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val profiles by viewModel.profiles.collectAsState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    var profileToDelete by remember { mutableStateOf<dev.chirpboard.app.data.entity.Profile?>(null) }

    // Delete confirmation dialog
    profileToDelete?.let { profile ->
        AnimatedAlertDialog(
            onDismissRequest = { profileToDelete = null },
            title = { Text("Delete Profile") },
            text = { Text("Are you sure you want to delete \"${profile.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProfile(profile)
                        profileToDelete = null
                    },
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { profileToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Profiles") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddProfile,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Profile",
                )
            }
        },
    ) { paddingValues ->
        AnimatedContent(
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
                    title = "No profiles yet",
                    description = "Create a profile to save your recording preferences",
                    actionLabel = "Create Profile",
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
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = 88.dp, // Extra padding for FAB
                        ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = profiles,
                        key = { it.id },
                    ) { profile ->
                        ProfileCard(
                            profile = profile,
                            onClick = { onProfileClick(profile.id) },
                            onDelete = { profileToDelete = profile },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}
