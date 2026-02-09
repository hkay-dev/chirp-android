package dev.chirpboard.app.debug

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevMenuScreen(
    onNavigateBack: () -> Unit,
    viewModel: DevMenuViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val reliabilityEvents by ReliabilityEventLogger.events.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    
    LaunchedEffect(uiState.message) {
        uiState.message?.let { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
            viewModel.clearMessage()
        }
    }
    
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Developer Menu") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // API Key Section
            item {
                DevSection(
                    title = "API Key",
                    icon = Icons.Default.Key
                ) {
                    OutlinedTextField(
                        value = uiState.apiKeyInput,
                        onValueChange = viewModel::onApiKeyChange,
                        label = { Text("Gemini API Key") },
                        placeholder = { Text("AIza...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = viewModel::saveApiKey,
                            enabled = uiState.apiKeyInput.isNotBlank()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save Key")
                        }
                        
                        if (uiState.hasApiKey) {
                            OutlinedButton(onClick = viewModel::clearApiKey) {
                                Text("Clear")
                            }
                        }
                    }
                    
                    if (uiState.hasApiKey) {
                        Text(
                            text = "API key is configured",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            
            // Dummy Recordings Section
            item {
                DevSection(
                    title = "Dummy Recordings",
                    icon = Icons.Default.PlaylistAdd
                ) {
                    Text(
                        text = "Generate fake recordings for testing UI states. These have no audio but are otherwise identical to real recordings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Quick actions
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilledTonalButton(
                            onClick = { viewModel.generateDummyRecordings(5) },
                            enabled = !uiState.isGenerating,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (uiState.isGenerating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Add 5")
                            }
                        }
                        
                        FilledTonalButton(
                            onClick = { viewModel.generateDummyRecordings(20) },
                            enabled = !uiState.isGenerating,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Add 20")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Specific states
                    Text(
                        text = "Add specific states:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = viewModel::addTranscribingRecording,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Transcribing", style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedButton(
                                onClick = viewModel::addEnhancingRecording,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Enhancing", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = viewModel::addPendingRecording,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Pending", style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedButton(
                                onClick = viewModel::addFailedRecording,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Failed", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = viewModel::addCompletedWithSummary,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("With Summary", style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedButton(
                                onClick = viewModel::addCompletedWithTags,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("With Tags", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            // Reliability Timeline
            item {
                DevSection(
                    title = "Reliability Timeline",
                    icon = Icons.Default.BugReport
                ) {
                    Text(
                        text = "Latest structured reliability lifecycle events (debug only).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(onClick = { ReliabilityEventLogger.clear() }) {
                        Text("Clear Timeline")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val latestEvents = reliabilityEvents.takeLast(20).reversed()
                    if (latestEvents.isEmpty()) {
                        Text(
                            text = "No reliability events yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        latestEvents.forEach { event ->
                            Text(
                                text = "${event.stage} ${event.outcome} [${event.reasonCode ?: "n/a"}]",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            
            // Danger Zone
            item {
                DevSection(
                    title = "Danger Zone",
                    icon = Icons.Default.Delete,
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                ) {
                    Text(
                        text = "These actions cannot be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Button(
                        onClick = viewModel::deleteAllRecordings,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        enabled = !uiState.isGenerating
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete All Recordings")
                    }
                }
            }
            
            // Info
            item {
                Text(
                    text = "This menu is only visible in debug builds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun DevSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            content()
        }
    }
}
