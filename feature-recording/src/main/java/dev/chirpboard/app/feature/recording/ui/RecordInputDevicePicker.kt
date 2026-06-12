package dev.chirpboard.app.feature.recording.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.core.audio.ActiveInputDevice
import dev.chirpboard.app.core.audio.AudioInputDevicePolicy
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.core.audio.AudioInputDeviceSummary
import dev.chirpboard.app.core.audio.AudioSettingsStore
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.ui.components.InputDeviceChip
import dev.chirpboard.app.core.ui.components.InputDeviceFallbackNotice
import dev.chirpboard.app.core.ui.components.InputDevicePickerUiState
import dev.chirpboard.app.core.ui.components.InputDeviceSheet
import dev.chirpboard.app.core.ui.theme.ChirpSpacing
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backing state for the input-device picker on the record screen (and reusable by any
 * Hilt-composable surface): the live device list, the persisted preference, the device
 * the current session actually selected, and the selection actions. Selection applies
 * to the NEXT capture start — never a silent mid-recording swap.
 */
@HiltViewModel
class InputDevicePickerViewModel
    @Inject
    constructor(
        private val inputDeviceSelector: AudioInputDeviceSelector,
        private val audioSettingsStore: AudioSettingsStore,
        recordingStateManager: RecordingStateManager,
    ) : ViewModel() {
        val pickerState: StateFlow<InputDevicePickerUiState> =
            combine(
                inputDeviceSelector.availableDevices,
                audioSettingsStore.settings,
                inputDeviceSelector.activeDevice,
                recordingStateManager.state,
            ) { devices, settings, activeDevice, recordingState ->
                InputDevicePickerUiState(
                    devices = devices,
                    policy = settings.inputDevicePolicy,
                    manualKey = settings.manualDeviceAddress,
                    manualName = settings.manualDeviceName,
                    activeDevice = activeDevice,
                    sessionLive = recordingState.isActive,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = InputDevicePickerUiState(),
            )

        val activeDevice: StateFlow<ActiveInputDevice?> = inputDeviceSelector.activeDevice

        fun selectAutomatic() {
            viewModelScope.launch {
                audioSettingsStore.setInputDevicePolicy(AudioInputDevicePolicy.Automatic)
            }
        }

        fun selectDevice(device: AudioInputDeviceSummary) {
            viewModelScope.launch {
                audioSettingsStore.setManualDevice(device.selectionKey, device.productName)
                audioSettingsStore.setInputDevicePolicy(AudioInputDevicePolicy.Manual)
            }
        }

        /** Re-enumerates after a BLUETOOTH_CONNECT grant so real names replace type labels. */
        fun refreshDevices() {
            inputDeviceSelector.refreshDevices()
        }
    }

/**
 * Full record-screen variant of the shared input-device picker: the device chip
 * (active/next mic), the transient preferred-absent fallback notice, and the device
 * sheet with the BLUETOOTH_CONNECT request flow. Self-contained — drop it into the
 * record screen column with no other wiring.
 */
@Composable
fun RecordInputDevicePicker(
    modifier: Modifier = Modifier,
    viewModel: InputDevicePickerViewModel = hiltViewModel(),
) {
    val state by viewModel.pickerState.collectAsStateWithLifecycle()
    val activeDevice by viewModel.activeDevice.collectAsStateWithLifecycle()
    var sheetOpen by remember { mutableStateOf(false) }

    val bluetoothPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.refreshDevices()
            }
        }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ChirpSpacing.ExtraSmall),
    ) {
        InputDeviceChip(
            state = state,
            onClick = { sheetOpen = true },
        )
        InputDeviceFallbackNotice(activeDevice = activeDevice)
    }

    if (sheetOpen) {
        InputDeviceSheet(
            state = state,
            onSelectAutomatic = viewModel::selectAutomatic,
            onSelectDevice = viewModel::selectDevice,
            onRequestBluetoothNames = {
                bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            },
            onDismiss = { sheetOpen = false },
        )
    }
}
