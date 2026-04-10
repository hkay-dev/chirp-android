package dev.chirpboard.app.feature.recording.ui.studio

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import dev.chirpboard.app.core.util.formatForHeader
import dev.chirpboard.app.core.util.formatAsDuration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Event
import dev.chirpboard.app.core.util.formatAsHumanReadableDuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.FileOpen
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.core.ui.components.LoadingState
import dev.chirpboard.app.feature.recording.ui.studio.tabs.ChatTab
import dev.chirpboard.app.feature.recording.ui.studio.tabs.SummaryTab
import dev.chirpboard.app.feature.recording.ui.studio.tabs.TranscriptTab
import androidx.compose.ui.res.stringResource
import dev.chirpboard.app.feature.recording.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ProcessingStudioScreen(
    recordingId: String,
    onNavigateBack: () -> Unit,
    viewModel: ProcessingStudioViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (state.isLoading) {
        LoadingState()
        return
    }

    val tabs = listOf(stringResource(R.string.rec_transcript), stringResource(R.string.rec_summary), stringResource(R.string.rec_chat))
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()

    val context = LocalContext.current
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showShareMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.rec_details)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { showShareMenu = true }) {
                                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.desc_share))
                            }
                            DropdownMenu(expanded = showShareMenu, onDismissRequest = { showShareMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.rec_share_audio)) },
                                    onClick = { showShareMenu = false; viewModel.shareAudio(context) }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.rec_share_transcript)) },
                                    onClick = { showShareMenu = false; viewModel.shareTranscript(context) }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.rec_share_both)) },
                                    onClick = { showShareMenu = false; viewModel.shareBoth(context) }
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { showOptionsMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.desc_more_options))
                            }
                            DropdownMenu(expanded = showOptionsMenu, onDismissRequest = { showOptionsMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.rec_edit_title)) },
                                    onClick = { showOptionsMenu = false; viewModel.startEditingTitle() },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.desc_edit)) }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.rec_delete)) },
                                    onClick = { showOptionsMenu = false; viewModel.deleteRecording { onNavigateBack() } },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.desc_delete), tint = MaterialTheme.colorScheme.error) }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    )
                )
                PrimaryTabRow(
                    selectedTabIndex = pagerState.currentPage,
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            },
                            text = { Text(title) }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            // Metadata Bar
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (state.isEditingTitle) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = state.editedTitle,
                            onValueChange = viewModel::updateEditedTitle,
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            )
                        )
                        IconButton(onClick = viewModel::cancelEditingTitle) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.desc_cancel))
                        }
                        IconButton(onClick = viewModel::saveTitle) {
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.desc_save))
                        }
                    }
                } else {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val dateStr = remember(state.createdAt) {
                        java.util.Date(state.createdAt).formatForHeader()
                    }
                    val durationStr = remember(state.durationMs) {
                        state.durationMs.formatAsHumanReadableDuration()
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Event,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = dateStr,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = durationStr,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                    val source = state.source
                    if (source != null) {
                        val sourceIcon = remember(source) {
                            when (source) {
                                RecordingSource.APP -> Icons.Default.Mic
                                RecordingSource.KEYBOARD -> Icons.Default.Keyboard
                                RecordingSource.WIDGET -> Icons.Default.Widgets
                                RecordingSource.IMPORTED -> Icons.Default.AudioFile
                            }
                        }
                        val sourceTextRes = remember(source) {
                            when (source) {
                                RecordingSource.APP -> R.string.rec_source_app
                                RecordingSource.KEYBOARD -> R.string.rec_source_keyboard
                                RecordingSource.WIDGET -> R.string.rec_source_widget
                                RecordingSource.IMPORTED -> R.string.rec_source_imported
                            }
                        }
                        val sourceText = stringResource(sourceTextRes)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = sourceIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = sourceText,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                        }
                    }
                }
            }
            
            if (state.status == RecordingStatus.FAILED) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.Error,
                            contentDescription = "Error"
                        )
                        Text(
                            text = state.errorMessage ?: stringResource(R.string.rec_status_failed),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> TranscriptTab(
                        words = state.transcriptWords,
                        status = state.status,
                        onWordClicked = viewModel::onWordClicked
                    )
                    1 -> SummaryTab(
                        summaryMarkdown = state.summary,
                        status = state.status
                    )
                    2 -> ChatTab(
                        messages = state.chatMessages,
                        onSendMessage = viewModel::onSendChatMessage
                    )
                }
            }
        }
    }
}