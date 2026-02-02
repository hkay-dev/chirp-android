package dev.parakeeboard.app.feature.llm.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmSettingsScreen(
    viewModel: LlmSettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LLM Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status card
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (uiState.isKeyConfigured) {
                            Icons.Default.CheckCircle
                        } else {
                            Icons.Default.Warning
                        },
                        contentDescription = null,
                        tint = if (uiState.isKeyConfigured) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = if (uiState.isKeyConfigured) {
                            "API Key Configured"
                        } else {
                            "API Key Not Set"
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            // API Key input
            OutlinedTextField(
                value = uiState.apiKey,
                onValueChange = viewModel::updateApiKey,
                label = { Text("Gemini API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = {
                    Text("Enter your Google AI Studio API key")
                }
            )

            // Buttons row
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::saveApiKey,
                    enabled = uiState.apiKey.isNotBlank()
                ) {
                    Text("Save")
                }

                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = uiState.isKeyConfigured && !uiState.isTestingConnection
                ) {
                    if (uiState.isTestingConnection) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Testing...")
                    } else {
                        Text("Test Connection")
                    }
                }

                if (uiState.isKeyConfigured) {
                    TextButton(onClick = viewModel::clearApiKey) {
                        Text("Clear")
                    }
                }
            }

            // Test result
            uiState.connectionTestResult?.let { result ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when (result) {
                            is LlmSettingsViewModel.ConnectionTestResult.Success ->
                                MaterialTheme.colorScheme.primaryContainer
                            is LlmSettingsViewModel.ConnectionTestResult.Error ->
                                MaterialTheme.colorScheme.errorContainer
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = when (result) {
                                is LlmSettingsViewModel.ConnectionTestResult.Success ->
                                    Icons.Default.CheckCircle
                                is LlmSettingsViewModel.ConnectionTestResult.Error ->
                                    Icons.Default.Warning
                            },
                            contentDescription = null,
                            tint = when (result) {
                                is LlmSettingsViewModel.ConnectionTestResult.Success ->
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                is LlmSettingsViewModel.ConnectionTestResult.Error ->
                                    MaterialTheme.colorScheme.onErrorContainer
                            }
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = when (result) {
                                is LlmSettingsViewModel.ConnectionTestResult.Success ->
                                    "Connection successful!"
                                is LlmSettingsViewModel.ConnectionTestResult.Error ->
                                    "Error: ${result.message}"
                            },
                            color = when (result) {
                                is LlmSettingsViewModel.ConnectionTestResult.Success ->
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                is LlmSettingsViewModel.ConnectionTestResult.Error ->
                                    MaterialTheme.colorScheme.onErrorContainer
                            }
                        )
                    }
                }
            }

            // Help text
            Text(
                text = "Get your API key from Google AI Studio (aistudio.google.com)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
