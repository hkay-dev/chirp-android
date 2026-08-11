package dev.chirpboard.app.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.chirpboard.app.core.audio.ActiveInputDevice
import dev.chirpboard.app.core.audio.AudioInputDeviceKind
import dev.chirpboard.app.core.audio.AudioInputDevicePolicy
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.core.audio.AudioInputDeviceSummary
import dev.chirpboard.app.core.ui.R
import dev.chirpboard.app.core.ui.haptics.ChirpHaptics
import dev.chirpboard.app.core.ui.theme.ChirpSpacing
import kotlinx.coroutines.delay

/**
 * Everything the shared input-device picker needs to render, on any surface
 * (record screen, keyboard panel, recognition dialog, settings). Surfaces build it
 * from [dev.chirpboard.app.core.audio.AudioInputDeviceSelector.availableDevices] +
 * the persisted audio settings; the picker itself stays stateless.
 */
data class InputDevicePickerUiState(
    val devices: List<AudioInputDeviceSummary> = emptyList(),
    val policy: AudioInputDevicePolicy = AudioInputDevicePolicy.DEFAULT,
    val manualKey: String? = null,
    val manualName: String? = null,
    /** The device the LIVE capture session selected, when one is running. */
    val activeDevice: ActiveInputDevice? = null,
    /** True while a capture is live on this surface — selection applies to the NEXT start. */
    val sessionLive: Boolean = false,
) {
    /**
     * The connected device matching the manual preference, if any. Resolved through the
     * grant/revoke-tolerant finder so a selection persisted before a BLUETOOTH_CONNECT
     * change still resolves (exact key matches always win).
     */
    val manualDevice: AudioInputDeviceSummary?
        get() = AudioInputDeviceSelector.findDeviceForSelectionKey(devices, manualKey)

    /** Whether a manual preference exists but its device is not currently connected. */
    val manualDeviceMissing: Boolean
        get() = policy == AudioInputDevicePolicy.Manual && !manualKey.isNullOrBlank() && manualDevice == null

    /** Display name for an absent manual preference. */
    val missingManualName: String
        get() =
            manualName
                ?: AudioInputDeviceSelector.displayNameFromSelectionKey(manualKey)
                ?: ""

    /** True when nameless Bluetooth devices are present (BLUETOOTH_CONNECT not granted). */
    val bluetoothNamesHidden: Boolean
        get() = devices.any { it.bluetoothNameHidden }

    /**
     * What the chip shows: the live session's device when capturing, otherwise the
     * device the next capture WILL use (preferred when connected, else the
     * priority-ranked fallback).
     */
    fun chipDevice(): AudioInputDeviceSummary? =
        activeDevice?.summary.takeIf { sessionLive }
            ?: AudioInputDeviceSelector.chooseInputDevice(devices, policy, manualKey).device
}

/** Icon family for a device kind, shared by every picker surface. */
fun AudioInputDeviceKind.icon(): ImageVector =
    when (this) {
        AudioInputDeviceKind.BuiltIn -> Icons.Rounded.Mic
        AudioInputDeviceKind.Usb -> Icons.Rounded.Usb
        AudioInputDeviceKind.WiredHeadset -> Icons.Rounded.Headset
        AudioInputDeviceKind.Bluetooth -> Icons.Rounded.Bluetooth
        AudioInputDeviceKind.BluetoothLe -> Icons.Rounded.Bluetooth
        AudioInputDeviceKind.Other -> Icons.Rounded.GraphicEq
    }

/**
 * Compact chip showing the active/next input device (icon + name). The shared entry
 * point for opening a device picker on any surface.
 */
@Composable
fun InputDeviceChip(
    state: InputDevicePickerUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val device = state.chipDevice()
    val label = device?.productName ?: stringResource(R.string.input_device_sheet_title)
    ChirpPill(
        label = label,
        modifier = modifier,
        icon = device?.kind?.icon() ?: Icons.Rounded.Mic,
        onClick = onClick,
        containerColor = containerColor,
        contentColor = contentColor,
        contentDescription = stringResource(R.string.desc_input_device_chip, label),
    )
}

/**
 * The device list shared by the bottom-sheet and dropdown variants: 'Automatic
 * (recommended)' first, then every connected device (with per-kind icons), an
 * unavailable row for an absent manual preference, the Bluetooth-names permission
 * affordance, and the priority-order explainer. Selection applies on the NEXT
 * capture start; a live session shows a note saying so.
 */
@Composable
fun InputDeviceListContent(
    state: InputDevicePickerUiState,
    onSelectAutomatic: () -> Unit,
    onSelectDevice: (AudioInputDeviceSummary) -> Unit,
    onRequestBluetoothNames: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.input_device_sheet_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = ChirpSpacing.Large, vertical = ChirpSpacing.Small),
        )

        InputDeviceRow(
            icon = Icons.Rounded.Autorenew,
            title = stringResource(R.string.input_device_automatic),
            supporting = null,
            selected = state.policy == AudioInputDevicePolicy.Automatic,
            onClick = {
                ChirpHaptics.tap(context)
                onSelectAutomatic()
            },
        )

        state.devices.forEach { device ->
            InputDeviceRow(
                icon = device.kind.icon(),
                title = device.productName,
                supporting = device.typeLabel,
                selected = isDeviceSelected(state, device),
                onClick = {
                    ChirpHaptics.tap(context)
                    onSelectDevice(device)
                },
            )
        }

        if (state.manualDeviceMissing && state.missingManualName.isNotBlank()) {
            InputDeviceRow(
                icon = Icons.Rounded.Mic,
                title = state.missingManualName,
                supporting = stringResource(R.string.input_device_not_connected),
                selected = true,
                enabled = false,
                onClick = {},
            )
        }

        if (state.bluetoothNamesHidden && onRequestBluetoothNames != null) {
            HorizontalDivider(modifier = Modifier.padding(vertical = ChirpSpacing.ExtraSmall))
            InputDeviceRow(
                icon = Icons.Rounded.Bluetooth,
                title = stringResource(R.string.input_device_bt_names_action),
                supporting = stringResource(R.string.input_device_bt_names_rationale),
                selected = false,
                onClick = {
                    ChirpHaptics.tap(context)
                    onRequestBluetoothNames()
                },
            )
        }

        if (state.sessionLive) {
            Text(
                text = stringResource(R.string.input_device_session_live_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = ChirpSpacing.Large, vertical = ChirpSpacing.ExtraSmall),
            )
        }

        Text(
            text = stringResource(R.string.input_device_priority_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = ChirpSpacing.Large, vertical = ChirpSpacing.Small),
        )
    }
}

/** Bottom-sheet variant of the picker, for activity-hosted surfaces. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputDeviceSheet(
    state: InputDevicePickerUiState,
    onSelectAutomatic: () -> Unit,
    onSelectDevice: (AudioInputDeviceSummary) -> Unit,
    onRequestBluetoothNames: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        InputDeviceListContent(
            state = state,
            onSelectAutomatic = {
                onSelectAutomatic()
                onDismiss()
            },
            onSelectDevice = { device ->
                onSelectDevice(device)
                onDismiss()
            },
            onRequestBluetoothNames = onRequestBluetoothNames,
            modifier = Modifier.padding(bottom = ChirpSpacing.Large),
        )
    }
}

/**
 * Transient one-line notice for the preferred-device fallback at capture start:
 * "Using Built-in microphone — Buds isn't connected". Auto-hides after a few
 * seconds; reappears for each new fallback session.
 */
@Composable
fun InputDeviceFallbackNotice(
    activeDevice: ActiveInputDevice?,
    modifier: Modifier = Modifier,
) {
    val fallbackFrom = activeDevice?.fallbackFromPreferredName
    var visible by remember(activeDevice) { mutableStateOf(fallbackFrom != null) }
    LaunchedEffect(activeDevice) {
        if (fallbackFrom != null) {
            delay(FALLBACK_NOTICE_MS)
            visible = false
        }
    }
    AnimatedVisibility(
        visible = visible && fallbackFrom != null,
        modifier = modifier,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        if (activeDevice != null && fallbackFrom != null) {
            Text(
                text =
                    stringResource(
                        R.string.input_device_fallback_notice,
                        activeDevice.summary.productName,
                        fallbackFrom,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun InputDeviceRow(
    icon: ImageVector,
    title: String,
    supporting: String?,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val contentColor =
        if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    ListItem(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = contentColor,
            )
        },
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
            )
        },
        supportingContent =
            supporting?.let {
                {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        trailingContent = {
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = stringResource(R.string.desc_input_device_selected),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}

/**
 * Whether [device] should show the selection check: an explicit manual match, or the
 * built-in mic while the legacy "prefer built-in" policy is active (selecting it by
 * row converts the preference to a robust manual selection).
 */
private fun isDeviceSelected(
    state: InputDevicePickerUiState,
    device: AudioInputDeviceSummary,
): Boolean =
    when (state.policy) {
        // Resolved against the whole list (not per-row key matching) so the relaxed
        // Bluetooth fallback can never check more than one row.
        AudioInputDevicePolicy.Manual -> state.manualDevice?.id == device.id
        AudioInputDevicePolicy.PreferBuiltIn -> device.kind == AudioInputDeviceKind.BuiltIn
        AudioInputDevicePolicy.Automatic -> false
    }

private const val FALLBACK_NOTICE_MS = 6_000L
