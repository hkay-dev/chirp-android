package dev.chirpboard.app.feature.recording.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.ui.components.EmptyState
import dev.chirpboard.app.core.ui.theme.ChirpShapes
import dev.chirpboard.app.core.util.formatAsDuration
import dev.chirpboard.app.data.entity.Recording

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onRecordingClick: (Recording) -> Unit,
    onRecordClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val displayItems by viewModel.displayItems.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val recordingState by viewModel.recordingState.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    
    var searchActive by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Show error messages
    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
            viewModel.clearError()
        }
    }
    
    LaunchedEffect(recordingState) {
        if (recordingState is RecordingState.Error) {
            val error = recordingState as RecordingState.Error
            snackbarHostState.showSnackbar(
                message = error.message,
                duration = SnackbarDuration.Short
            )
            viewModel.clearError()
        }
    }
    
    Scaffold(
        topBar = {
            Column {
                // Search bar with settings action
                SearchBar(
                    inputField = {
                        SearchBarDefaults.InputField(
                            query = searchQuery,
                            onQueryChange = viewModel::onSearchQueryChange,
                            onSearch = { searchActive = false },
                            expanded = searchActive,
                            onExpandedChange = { searchActive = it },
                            placeholder = { Text("Search recordings") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search"
                                )
                            },
                            trailingIcon = {
                                if (searchActive || searchQuery.isNotEmpty()) {
                                    IconButton(onClick = {
                                        viewModel.onSearchQueryChange("")
                                        searchActive = false
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Clear search"
                                        )
                                    }
                                } else {
                                    IconButton(onClick = onSettingsClick) {
                                        Icon(
                                            imageVector = Icons.Default.Settings,
                                            contentDescription = "Settings"
                                        )
                                    }
                                }
                            }
                        )
                    },
                    expanded = searchActive,
                    onExpandedChange = { searchActive = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (searchActive) 0.dp else 16.dp)
                ) {
                    // Search suggestions could go here in the future
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onRecordClick,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null
                    )
                },
                text = { Text("Record") }
            )
        },
        floatingActionButtonPosition = FabPosition.Center,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        AnimatedContent(
            targetState = displayItems.isEmpty() && searchQuery.isBlank(),
            transitionSpec = {
                fadeIn(tween(200, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(200, easing = FastOutSlowInEasing))
            },
            label = "home_content"
        ) { showEmpty ->
            if (showEmpty) {
                EmptyState(
                    icon = Icons.Default.MicNone,
                    title = "No recordings yet",
                    description = "Tap Record to capture audio and get instant transcriptions. Create profiles and tags in Settings to stay organized.",
                    modifier = Modifier.padding(paddingValues)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 96.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Stats header — show when not searching
                    if (searchQuery.isBlank() && stats.totalRecordings > 0) {
                        item(key = "stats") {
                            StatsHeader(
                                stats = stats,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem()
                            )
                        }
                        
                        item(key = "spacer") {
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                    
                    // Search results count
                    if (searchQuery.isNotBlank()) {
                        item(key = "search_results") {
                            Text(
                                text = "${displayItems.size} result${if (displayItems.size != 1) "s" else ""}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(bottom = 4.dp)
                                    .animateItem()
                            )
                        }
                    }
                    
                    // Recording cards
                    items(
                        items = displayItems,
                        key = { it.recording.id }
                    ) { item ->
                        RecordingCard(
                            item = item,
                            onClick = { onRecordingClick(item.recording) },
                            onDelete = { viewModel.deleteRecording(item.recording) },
                            onShare = { viewModel.shareRecording(item.recording) },
                            onRetryTranscription = if (item.recording.status == dev.chirpboard.app.data.model.RecordingStatus.FAILED) {
                                { viewModel.retryTranscription(item.recording) }
                            } else null,
                            onGenerateTitle = if (item.recording.status == dev.chirpboard.app.data.model.RecordingStatus.COMPLETED) {
                                { viewModel.generateTitle(item.recording) }
                            } else null,
                            onGenerateSummary = if (item.recording.status == dev.chirpboard.app.data.model.RecordingStatus.COMPLETED) {
                                { viewModel.generateSummary(item.recording) }
                            } else null,
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact stats header showing recording counts and total duration.
 */
@Composable
private fun StatsHeader(
    stats: HomeStats,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = ChirpShapes.Medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatItem(
                value = stats.totalRecordings.toString(),
                label = "Recordings"
            )
            
            StatItem(
                value = stats.totalDurationMs.formatAsDuration(),
                label = "Total"
            )

            if (stats.processingCount > 0) {
                StatItem(
                    value = stats.processingCount.toString(),
                    label = "Processing",
                    valueColor = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    value: String,
    label: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
