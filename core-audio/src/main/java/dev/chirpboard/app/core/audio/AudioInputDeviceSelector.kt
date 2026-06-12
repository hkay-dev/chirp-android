package dev.chirpboard.app.core.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioInputDeviceSelector
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val audioSettingsStore: AudioSettingsStore,
    ) {
        private val audioManager = context.getSystemService(AudioManager::class.java)
        private val _activeDeviceLabel = MutableStateFlow<String?>(null)
        val activeDeviceLabel: StateFlow<String?> = _activeDeviceLabel.asStateFlow()

        /**
         * The device the current capture session actually selected (with any
         * preferred-device fallback annotation), shared app-wide so every surface can
         * display it. Null while no capture is live.
         */
        private val _activeDevice = MutableStateFlow<ActiveInputDevice?>(null)
        val activeDevice: StateFlow<ActiveInputDevice?> = _activeDevice.asStateFlow()

        /**
         * Live snapshot of connected input devices, refreshed by [AudioDeviceCallback]
         * on hot-plug so pickers on every surface share one always-current list.
         */
        private val _availableDevices = MutableStateFlow<List<AudioInputDeviceSummary>>(emptyList())
        val availableDevices: StateFlow<List<AudioInputDeviceSummary>> = _availableDevices.asStateFlow()

        /**
         * Bumped whenever input devices are hot-plugged so settings UI can refresh its
         * device list while open instead of showing a stale one-shot snapshot.
         */
        private val _devicesChangedTick = MutableStateFlow(0L)
        val devicesChangedTick: StateFlow<Long> = _devicesChangedTick.asStateFlow()

        private var activeDeviceId: Int? = null
        private var onActiveDeviceLost: ((lostDeviceName: String?) -> Unit)? = null

        private val deviceCallback =
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    if (addedDevices.any { it.isSource }) {
                        _devicesChangedTick.value += 1
                        refreshAvailableDevices()
                    }
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    if (removedDevices.any { it.isSource }) {
                        _devicesChangedTick.value += 1
                        refreshAvailableDevices()
                    }
                    val activeId = activeDeviceId
                    val lostActive = activeId != null && removedDevices.any { it.id == activeId }
                    if (lostActive) {
                        val lostName =
                            _activeDevice.value?.summary?.productName
                                ?: removedDevices
                                    .firstOrNull { it.id == activeId }
                                    ?.let { summaryFor(it).productName }
                        Log.w(TAG, "Active input device disconnected: $lostName")
                        onActiveDeviceLost?.invoke(lostName)
                    }
                }
            }

        init {
            audioManager.registerAudioDeviceCallback(deviceCallback, null)
            refreshAvailableDevices()
        }

        fun listInputDevices(): List<AudioInputDeviceSummary> = inputDevices().map(::summaryFor)

        /**
         * Whether the app may read real Bluetooth device names/addresses. Without the
         * grant pickers degrade to type labels ("Bluetooth") and composite selection keys.
         */
        fun hasBluetoothConnectPermission(): Boolean =
            runCatching {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)

        /**
         * Re-enumerates devices immediately — pickers call this after a BLUETOOTH_CONNECT
         * grant so real Bluetooth names replace the type-label placeholders.
         */
        fun refreshDevices() {
            _devicesChangedTick.value += 1
            refreshAvailableDevices()
        }

        /**
         * Capture-start device selection: the persisted preference when its device is
         * present, otherwise the highest-priority connected device (USB > Bluetooth >
         * wired > built-in). Publishes the choice (including the preferred-absent
         * fallback annotation) on [activeDevice] for every surface to display.
         */
        suspend fun resolvePreferredDevice(): AudioDeviceInfo? {
            val settings = audioSettingsStore.currentSettings()
            val devices = inputDevices()
            val summaries = devices.map(::summaryFor)
            val choice =
                chooseInputDevice(
                    devices = summaries,
                    policy = settings.inputDevicePolicy,
                    manualKey = settings.manualDeviceAddress,
                )
            val resolvedSummary = choice.device
            val resolved = resolvedSummary?.let { summary -> devices.firstOrNull { it.id == summary.id } }
            val fallbackName =
                if (choice.preferredMissing) {
                    settings.manualDeviceName
                        ?: displayNameFromSelectionKey(settings.manualDeviceAddress)
                } else {
                    null
                }
            activeDeviceId = resolved?.id
            _activeDevice.value = resolvedSummary?.let { ActiveInputDevice(it, fallbackName) }
            _activeDeviceLabel.value = resolvedSummary?.productName
            Log.i(
                TAG,
                "Input device selected: ${resolvedSummary?.productName} " +
                    "(${resolvedSummary?.typeLabel}, policy=${settings.inputDevicePolicy}, " +
                    "preferredMissing=${choice.preferredMissing})",
            )
            return resolved
        }

        @SuppressLint("MissingPermission")
        suspend fun buildAudioRecord(
            audioSource: Int,
            sampleRate: Int,
            channelConfig: Int,
            audioFormat: Int,
            bufferSize: Int,
        ): AudioRecord {
            val device = resolvePreferredDevice()
            val record = AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, bufferSize)
            if (device != null) {
                record.setPreferredDevice(device)
                activeDeviceId = device.id
            }
            return record
        }

        /**
         * Best-effort correction of the optimistic active-device label from the platform's
         * actual capture routing. [AudioRecord.getRoutedDevice] is only populated once the
         * stream is live, so engines call this after the first successful read. Keeping
         * [activeDeviceId] honest also makes device-lost detection track the real device.
         * Always logs the effective route so on-device routing verification has a record.
         */
        fun refreshActiveDeviceFromRouting(record: AudioRecord) {
            val routed = runCatching { record.routedDevice }.getOrNull() ?: return
            val summary = summaryFor(routed)
            Log.i(TAG, "Effective capture route: ${summary.productName} (${summary.typeLabel}, id=${routed.id})")
            if (routed.id == activeDeviceId) return
            Log.i(TAG, "Routing differs from requested device; updating active device")
            activeDeviceId = routed.id
            _activeDevice.value = ActiveInputDevice(summary, _activeDevice.value?.fallbackFromPreferredName)
            _activeDeviceLabel.value = summary.productName
        }

        fun setOnActiveDeviceLostListener(listener: ((lostDeviceName: String?) -> Unit)?) {
            onActiveDeviceLost = listener
        }

        /**
         * Clears active-device state after a capture ends. Deliberately does NOT clear the
         * device-lost listener: its lifecycle belongs to whoever registered it (the
         * recording service holds it for its whole lifetime), and nulling it here used to
         * leave the next capture of a surviving service instance without any device-lost
         * handling.
         */
        fun clearActiveDevice() {
            activeDeviceId = null
            _activeDevice.value = null
            _activeDeviceLabel.value = null
        }

        private fun refreshAvailableDevices() {
            _availableDevices.value = inputDevices().map(::summaryFor)
        }

        private fun summaryFor(device: AudioDeviceInfo): AudioInputDeviceSummary =
            summaryFor(device, hasBluetoothConnectPermission())

        companion object {
            private const val TAG = "AudioInputDeviceSelector"
            private const val FALLBACK_PRODUCT_NAME = "Unknown device"
            private const val COMPOSITE_KEY_PREFIX = "device:"
            private const val COMPOSITE_KEY_PARTS = 3

            /** Fallback-priority order, best first; mirrored by [priorityOf]. */
            val PRIORITY_ORDER =
                listOf(
                    AudioInputDeviceKind.Usb,
                    AudioInputDeviceKind.BluetoothLe,
                    AudioInputDeviceKind.Bluetooth,
                    AudioInputDeviceKind.WiredHeadset,
                    AudioInputDeviceKind.BuiltIn,
                    AudioInputDeviceKind.Other,
                )

            fun kindFor(type: Int): AudioInputDeviceKind =
                when (type) {
                    AudioDeviceInfo.TYPE_BUILTIN_MIC -> AudioInputDeviceKind.BuiltIn
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    -> AudioInputDeviceKind.Usb
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_AUX_LINE,
                    -> AudioInputDeviceKind.WiredHeadset
                    AudioDeviceInfo.TYPE_BLE_HEADSET -> AudioInputDeviceKind.BluetoothLe
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioInputDeviceKind.Bluetooth
                    else -> AudioInputDeviceKind.Other
                }

            /**
             * Fallback priority when the preferred device is absent (or none is set):
             * an explicitly connected external mic (USB, then Bluetooth, then wired)
             * almost always signals intent to use it, so the built-in mic ranks last.
             */
            fun priorityOf(kind: AudioInputDeviceKind): Int = PRIORITY_ORDER.indexOf(kind)

            fun devicePriority(type: Int): Int = priorityOf(kindFor(type))

            fun rankDevices(devices: List<AudioInputDeviceSummary>): List<AudioInputDeviceSummary> =
                devices.sortedWith(compareBy({ priorityOf(it.kind) }, { it.productName }))

            /**
             * Pure capture-start selection algorithm (unit-tested as a matrix):
             * the manual preference when its device is connected; otherwise the
             * highest-priority connected device per [priorityOf]. [InputDeviceChoice.preferredMissing]
             * reports a stored-but-absent manual preference so surfaces can show a
             * transient "using X instead" notice.
             */
            fun chooseInputDevice(
                devices: List<AudioInputDeviceSummary>,
                policy: AudioInputDevicePolicy,
                manualKey: String?,
            ): InputDeviceChoice {
                val ranked = rankDevices(devices)
                return when (policy) {
                    AudioInputDevicePolicy.Manual -> {
                        val preferred = devices.firstOrNull { summaryMatchesSelectionKey(it, manualKey) }
                        InputDeviceChoice(
                            device = preferred ?: ranked.firstOrNull(),
                            preferredMissing = preferred == null && !manualKey.isNullOrBlank(),
                        )
                    }
                    AudioInputDevicePolicy.PreferBuiltIn ->
                        InputDeviceChoice(
                            device =
                                devices.firstOrNull { it.kind == AudioInputDeviceKind.BuiltIn }
                                    ?: ranked.firstOrNull(),
                            preferredMissing = false,
                        )
                    AudioInputDevicePolicy.Automatic ->
                        InputDeviceChoice(device = ranked.firstOrNull(), preferredMissing = false)
                }
            }

            fun typeLabel(type: Int): String =
                when (type) {
                    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in"
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    -> "USB"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
                    AudioDeviceInfo.TYPE_AUX_LINE -> "Line in"
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
                    AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE"
                    else -> "Other"
                }

            /**
             * Stable persistence key for a manual device selection. Uses the hardware
             * address when present; otherwise a type+name composite. Bluetooth and wired
             * devices report a blank address without BLUETOOTH_CONNECT, and the old
             * behavior of persisting the transient numeric id (or blank, which deleted
             * the stored key) silently broke manual selection for exactly those devices.
             */
            fun selectionKeyFor(
                type: Int,
                address: String?,
                productName: String?,
            ): String =
                address?.takeIf { it.isNotBlank() }
                    ?: "$COMPOSITE_KEY_PREFIX$type:${productName.orEmpty()}"

            fun matchesSelectionKey(
                device: AudioDeviceInfo,
                key: String?,
            ): Boolean {
                if (key.isNullOrBlank()) return false
                val address = device.address
                if (address.isNotBlank() && address == key) return true
                return selectionKeyFor(device.type, address, device.productName?.toString()) == key
            }

            fun summaryMatchesSelectionKey(
                summary: AudioInputDeviceSummary,
                key: String?,
            ): Boolean {
                if (key.isNullOrBlank()) return false
                return summary.selectionKey == key ||
                    (summary.address?.isNotBlank() == true && summary.address == key)
            }

            /**
             * Best-effort display name recovered from a composite selection key, used to
             * name a missing preferred device when no display name was persisted with it.
             */
            fun displayNameFromSelectionKey(key: String?): String? {
                if (key == null || !key.startsWith(COMPOSITE_KEY_PREFIX)) return null
                return key
                    .split(":", limit = COMPOSITE_KEY_PARTS)
                    .getOrNull(2)
                    ?.takeIf { it.isNotBlank() }
            }

            /**
             * Builds the UI summary for a device. The selection key is always derived from
             * the RAW product name so persisted manual selections stay stable; only the
             * displayed name degrades. A Bluetooth device whose name is unavailable
             * (blank, or the platform substituted the phone's own model because the app
             * lacks BLUETOOTH_CONNECT) is labeled by its type and flagged so pickers can
             * offer the permission rationale.
             */
            fun summaryFor(
                device: AudioDeviceInfo,
                hasBluetoothPermission: Boolean,
            ): AudioInputDeviceSummary {
                val kind = kindFor(device.type)
                val rawName = device.productName?.toString().orEmpty()
                val isBluetooth =
                    kind == AudioInputDeviceKind.Bluetooth || kind == AudioInputDeviceKind.BluetoothLe
                val nameHidden =
                    isBluetooth && !hasBluetoothPermission && (rawName.isBlank() || rawName == Build.MODEL)
                val label = typeLabel(device.type)
                val displayName =
                    when {
                        nameHidden -> label
                        kind == AudioInputDeviceKind.BuiltIn -> BUILT_IN_DISPLAY_NAME
                        rawName.isNotBlank() -> rawName
                        else -> FALLBACK_PRODUCT_NAME
                    }
                return AudioInputDeviceSummary(
                    id = device.id,
                    productName = displayName,
                    typeLabel = label,
                    kind = kind,
                    address = device.address,
                    selectionKey = selectionKeyFor(device.type, device.address, rawName),
                    bluetoothNameHidden = nameHidden,
                )
            }

            /** Friendlier than echoing the phone's model name for the built-in mic. */
            const val BUILT_IN_DISPLAY_NAME = "Built-in microphone"
        }

        private fun inputDevices(): List<AudioDeviceInfo> {
            return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        }
    }
