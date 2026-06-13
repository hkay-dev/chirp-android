package dev.chirpboard.app.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import dev.chirpboard.app.core.ui.motion.PushDownReveal
import dev.chirpboard.app.core.ui.motion.animatePushDownLayout
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.R
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.chirpboard.app.core.audio.AudioInputDevicePolicy
import dev.chirpboard.app.core.audio.RecordingOutputFormat
import dev.chirpboard.app.core.audio.RecordingQualityPreset
import dev.chirpboard.app.core.ui.components.ChirpSettingsDetailScaffold
import dev.chirpboard.app.core.ui.components.InputDeviceListContent
import dev.chirpboard.app.core.ui.components.InputDevicePickerUiState
import dev.chirpboard.app.core.ui.components.SettingsDropdownListItem
import dev.chirpboard.app.core.ui.components.SettingsSectionHeader
import dev.chirpboard.app.core.ui.theme.ChirpSpacing

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
    val manualDeviceKey by viewModel.manualDeviceKey.collectAsStateWithLifecycle()
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
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = ChirpSpacing.ScreenHorizontal,
                                vertical = ChirpSpacing.Small,
                            ),
                    verticalArrangement = Arrangement.spacedBy(ChirpSpacing.Small),
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
                    // PROP-4: a short generic line for every policy; the USB > Bluetooth > wired >
                    // built-in priority is only meaningful under Automatic, so it surfaces only then.
                    supportingText = stringResource(R.string.audio_settings_input_device_policy_help),
                    options = AudioInputDevicePolicy.entries,
                    selectedOption = inputDevicePolicy,
                    optionLabel = { inputDevicePolicyLabel(it) },
                    onOptionSelected = viewModel::setInputDevicePolicy,
                    additionalSupportingContent = {
                        if (inputDevicePolicy == AudioInputDevicePolicy.Automatic) {
                            Spacer(modifier = Modifier.height(ChirpSpacing.ExtraSmall))
                            Text(
                                text = stringResource(R.string.audio_settings_input_device_priority_help),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }

            activeInputDeviceLabel?.let { label ->
                item {
                    FixedValueListItem(
                        title = stringResource(R.string.audio_settings_active_input),
                        value = label,
                    )
                }
            }

            item(key = "manual_input_devices") {
                PushDownReveal(visible = inputDevicePolicy == AudioInputDevicePolicy.Manual) {
                    // Shared picker: 'Automatic (recommended)', every connected device, the
                    // 'not connected' row for an absent manual preference, the Bluetooth-names
                    // request, and the priority explainer — in lockstep with the record/keyboard
                    // pickers and at the house bodyLarge/bodyMedium scale.
                    val bluetoothPermissionLauncher =
                        rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestPermission(),
                        ) { granted ->
                            if (granted) {
                                viewModel.onBluetoothPermissionGranted()
                            }
                        }
                    val pickerState =
                        InputDevicePickerUiState(
                            devices = availableInputDevices,
                            policy = inputDevicePolicy,
                            manualKey = manualDeviceKey,
                        )
                    InputDeviceListContent(
                        state = pickerState,
                        onSelectAutomatic = {
                            viewModel.setInputDevicePolicy(AudioInputDevicePolicy.Automatic)
                        },
                        onSelectDevice = viewModel::setManualInputDevice,
                        onRequestBluetoothNames = {
                            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        },
                        modifier = Modifier.animatePushDownLayout(),
                    )
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

            // INS-7: reserve space under the list for the global mini-player sibling bar.
            item { Spacer(modifier = Modifier.height(ChirpSpacing.MiniPlayerClearance)) }
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

/**
 * A read-only settings row at the house scale (bodyLarge+Medium title, bodyMedium subtitle).
 * The free-form [value] becomes the full-width supporting line so long Bluetooth/USB device
 * names get a full line instead of ellipsizing mid-word next to the title.
 */
@Composable
private fun FixedValueListItem(
    title: String,
    value: String,
) {
    ListItem(
        modifier = Modifier.fillMaxWidth(),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        },
        supportingContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
