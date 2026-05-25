package dev.chirpboard.app.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.chirpboard.app.core.ui.motion.PushDownReveal
import dev.chirpboard.app.core.ui.motion.animatePushDownLayout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.R
import dev.chirpboard.app.core.audio.AudioInputDevicePolicy
import dev.chirpboard.app.core.audio.RecordingOutputFormat
import dev.chirpboard.app.core.audio.RecordingQualityPreset
import dev.chirpboard.app.core.ui.components.ChirpSettingsDetailScaffold
import dev.chirpboard.app.core.ui.components.SettingsDropdownListItem
import dev.chirpboard.app.core.ui.components.SettingsSectionHeader

/**
 * Settings screen for audio-related options including microphone gain
 * and saved recording quality.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSettingsScreen(
    viewModel: AudioSettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
) {
    val microphoneGain by viewModel.microphoneGain.collectAsStateWithLifecycle()
    val recordingQualityPreset by viewModel.recordingQualityPreset.collectAsStateWithLifecycle()
    val outputFormat by viewModel.outputFormat.collectAsStateWithLifecycle()
    val inputDevicePolicy by viewModel.inputDevicePolicy.collectAsStateWithLifecycle()
    val availableInputDevices by viewModel.availableInputDevices.collectAsStateWithLifecycle()
    val activeInputDeviceLabel by viewModel.activeInputDeviceLabel.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    ChirpSettingsDetailScaffold(
        title = stringResource(R.string.audio_settings_title),
        onNavigateBack = onNavigateBack,
        scrollBehavior = scrollBehavior,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
        ) {
            item { SettingsSectionHeader(title = stringResource(R.string.audio_settings_section_input)) }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val displayedGain by animateFloatAsState(
                        targetValue = microphoneGain,
                        animationSpec = tween(200),
                        label = "gainDisplay",
                    )
                    Text(
                        text = stringResource(R.string.audio_settings_microphone_gain_value, displayedGain),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Slider(
                        value = microphoneGain,
                        onValueChange = viewModel::setMicrophoneGain,
                        valueRange = 1.0f..5.0f,
                        steps = 39,
                    )
                    Text(
                        text = stringResource(R.string.audio_settings_microphone_gain_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SettingsDropdownListItem(
                    title = stringResource(R.string.audio_settings_input_device_policy),
                    supportingText = stringResource(R.string.audio_settings_input_device_policy_help),
                    options = AudioInputDevicePolicy.entries,
                    selectedOption = inputDevicePolicy,
                    optionLabel = { inputDevicePolicyLabel(it) },
                    onOptionSelected = viewModel::setInputDevicePolicy,
                )
            }

            activeInputDeviceLabel?.let { label ->
                item {
                    FixedValueListItem(
                        title = stringResource(R.string.audio_settings_active_input),
                        supportingText = stringResource(R.string.audio_settings_active_input_help),
                        value = label,
                    )
                }
            }

            item(key = "manual_input_devices") {
                PushDownReveal(visible = inputDevicePolicy == AudioInputDevicePolicy.Manual) {
                    Column(modifier = Modifier.animatePushDownLayout()) {
                        availableInputDevices.forEach { device ->
                            ListItem(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.setManualInputDevice(device.address ?: device.id.toString()) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text(device.productName) },
                                supportingContent = { Text(device.typeLabel) },
                            )
                        }
                    }
                }
            }

            item { SettingsSectionHeader(title = stringResource(R.string.audio_settings_section_output)) }
            item {
                SettingsDropdownListItem(
                    title = stringResource(R.string.audio_settings_recording_quality),
                    supportingText = stringResource(R.string.audio_settings_recording_quality_help),
                    options = RecordingQualityPreset.entries,
                    selectedOption = recordingQualityPreset,
                    optionLabel = { recordingQualityLabel(it) },
                    onOptionSelected = viewModel::setRecordingQualityPreset,
                )
            }
            item {
                SettingsDropdownListItem(
                    title = stringResource(R.string.audio_settings_output_format),
                    supportingText = stringResource(R.string.audio_settings_output_format_help),
                    options = RecordingOutputFormat.entries,
                    selectedOption = outputFormat,
                    optionLabel = { recordingOutputFormatLabel(it) },
                    onOptionSelected = viewModel::setOutputFormat,
                )
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun inputDevicePolicyLabel(policy: AudioInputDevicePolicy): String =
    when (policy) {
        AudioInputDevicePolicy.Automatic -> stringResource(R.string.audio_settings_input_policy_automatic)
        AudioInputDevicePolicy.PreferBuiltIn -> stringResource(R.string.audio_settings_input_policy_builtin)
        AudioInputDevicePolicy.Manual -> stringResource(R.string.audio_settings_input_policy_manual)
    }

@Composable
private fun FixedValueListItem(
    title: String,
    supportingText: String,
    value: String,
) {
    ListItem(
        modifier = Modifier.fillMaxWidth(),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun recordingOutputFormatLabel(format: RecordingOutputFormat): String =
    when (format) {
        RecordingOutputFormat.M4A -> stringResource(R.string.audio_settings_format_m4a)
        RecordingOutputFormat.MP3 -> stringResource(R.string.audio_settings_format_mp3)
        RecordingOutputFormat.WAV -> stringResource(R.string.audio_settings_format_wav)
    }

@Composable
private fun recordingQualityLabel(preset: RecordingQualityPreset): String =
    when (preset) {
        RecordingQualityPreset.Low -> stringResource(R.string.audio_settings_quality_low)
        RecordingQualityPreset.Balanced -> stringResource(R.string.audio_settings_quality_balanced)
        RecordingQualityPreset.High -> stringResource(R.string.audio_settings_quality_high)
    }
