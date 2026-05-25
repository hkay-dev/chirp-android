package dev.chirpboard.app.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.R
import dev.chirpboard.app.core.audio.AudioInputDevicePolicy
import dev.chirpboard.app.core.audio.RecordingQualityPreset
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
    val inputDevicePolicy by viewModel.inputDevicePolicy.collectAsStateWithLifecycle()
    val availableInputDevices by viewModel.availableInputDevices.collectAsStateWithLifecycle()
    val activeInputDeviceLabel by viewModel.activeInputDeviceLabel.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.audio_settings_title),
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.desc_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors =
                    TopAppBarDefaults.largeTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
            )
        },
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
                InputDevicePolicyListItem(
                    currentPolicy = inputDevicePolicy,
                    onPolicySelected = viewModel::setInputDevicePolicy,
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

            if (inputDevicePolicy == AudioInputDevicePolicy.Manual) {
                items(availableInputDevices, key = { it.id }) { device ->
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

            item { SettingsSectionHeader(title = stringResource(R.string.audio_settings_section_output)) }
            item {
                RecordingQualityListItem(
                    currentPreset = recordingQualityPreset,
                    onPresetSelected = viewModel::setRecordingQualityPreset,
                )
            }
            item {
                FixedValueListItem(
                    title = stringResource(R.string.audio_settings_output_format),
                    supportingText = stringResource(R.string.audio_settings_output_format_help),
                    value = viewModel.savedFormatLabel,
                )
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun InputDevicePolicyListItem(
    currentPolicy: AudioInputDevicePolicy,
    onPolicySelected: (AudioInputDevicePolicy) -> Unit,
) {
    var isDropdownExpanded by remember { mutableStateOf(false) }

    ListItem(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { isDropdownExpanded = true },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                text = stringResource(R.string.audio_settings_input_device_policy),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = {
            Text(
                text = stringResource(R.string.audio_settings_input_device_policy_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier =
                        Modifier
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = inputDevicePolicyLabel(currentPolicy),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(
                    expanded = isDropdownExpanded,
                    onDismissRequest = { isDropdownExpanded = false },
                ) {
                    AudioInputDevicePolicy.entries.forEach { policy ->
                        DropdownMenuItem(
                            text = { Text(inputDevicePolicyLabel(policy)) },
                            onClick = {
                                onPolicySelected(policy)
                                isDropdownExpanded = false
                            },
                            trailingIcon =
                                if (policy == currentPolicy) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = stringResource(R.string.desc_selected),
                                        )
                                    }
                                } else {
                                    null
                                },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun inputDevicePolicyLabel(policy: AudioInputDevicePolicy): String =
    when (policy) {
        AudioInputDevicePolicy.Automatic -> stringResource(R.string.audio_settings_input_policy_automatic)
        AudioInputDevicePolicy.PreferBuiltIn -> stringResource(R.string.audio_settings_input_policy_builtin)
        AudioInputDevicePolicy.Manual -> stringResource(R.string.audio_settings_input_policy_manual)
    }

@Composable
private fun RecordingQualityListItem(
    currentPreset: RecordingQualityPreset,
    onPresetSelected: (RecordingQualityPreset) -> Unit,
) {
    var isDropdownExpanded by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isDropdownExpanded = true },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                text = stringResource(R.string.audio_settings_recording_quality),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = {
            Text(
                text = stringResource(R.string.audio_settings_recording_quality_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = recordingQualityLabel(currentPreset),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(
                    expanded = isDropdownExpanded,
                    onDismissRequest = { isDropdownExpanded = false },
                ) {
                    RecordingQualityPreset.entries.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(recordingQualityLabel(preset)) },
                            onClick = {
                                onPresetSelected(preset)
                                isDropdownExpanded = false
                            },
                            trailingIcon = if (preset == currentPreset) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = stringResource(R.string.desc_selected),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        },
    )
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
private fun recordingQualityLabel(preset: RecordingQualityPreset): String =
    when (preset) {
        RecordingQualityPreset.Low -> stringResource(R.string.audio_settings_quality_low)
        RecordingQualityPreset.Balanced -> stringResource(R.string.audio_settings_quality_balanced)
        RecordingQualityPreset.High -> stringResource(R.string.audio_settings_quality_high)
    }