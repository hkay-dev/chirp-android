package dev.chirpboard.app.core.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
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
         * Bumped whenever input devices are hot-plugged so settings UI can refresh its
         * device list while open instead of showing a stale one-shot snapshot.
         */
        private val _devicesChangedTick = MutableStateFlow(0L)
        val devicesChangedTick: StateFlow<Long> = _devicesChangedTick.asStateFlow()

        private var activeDeviceId: Int? = null
        private var onActiveDeviceLost: (() -> Unit)? = null

        private val deviceCallback =
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    if (addedDevices.any { it.isSource }) {
                        _devicesChangedTick.value += 1
                    }
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    if (removedDevices.any { it.isSource }) {
                        _devicesChangedTick.value += 1
                    }
                    val lostActive =
                        activeDeviceId?.let { activeId ->
                            removedDevices.any { it.id == activeId }
                        } ?: false
                    if (lostActive) {
                        Log.w(TAG, "Active input device disconnected")
                        onActiveDeviceLost?.invoke()
                    }
                }
            }

        init {
            audioManager.registerAudioDeviceCallback(deviceCallback, null)
        }

        suspend fun listInputDevices(): List<AudioInputDeviceSummary> = inputDevices().map(::summaryFor)

        suspend fun resolvePreferredDevice(): AudioDeviceInfo? {
            val settings = audioSettingsStore.currentSettings()
            val devices = inputDevices()
            val resolved =
                when (settings.inputDevicePolicy) {
                    AudioInputDevicePolicy.Manual -> {
                        val manualKey = settings.manualDeviceAddress
                        devices.firstOrNull { device -> matchesSelectionKey(device, manualKey) }
                            ?: rankDevices(devices).firstOrNull()
                    }
                    AudioInputDevicePolicy.PreferBuiltIn -> {
                        devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                            ?: rankDevices(devices).firstOrNull()
                    }
                    AudioInputDevicePolicy.Automatic -> rankDevices(devices).firstOrNull()
                }
            activeDeviceId = resolved?.id
            _activeDeviceLabel.value = resolved?.let { summaryFor(it).productName }
            return resolved
        }

        fun applyPreferredDevice(
            recorder: MediaRecorder,
            device: AudioDeviceInfo?,
        ) {
            if (device == null) {
                return
            }
            recorder.setPreferredDevice(device)
            activeDeviceId = device.id
            _activeDeviceLabel.value = summaryFor(device).productName
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
                _activeDeviceLabel.value = summaryFor(device).productName
            }
            return record
        }

        /**
         * Best-effort correction of the optimistic active-device label from the platform's
         * actual capture routing. [AudioRecord.getRoutedDevice] is only populated once the
         * stream is live, so engines call this after the first successful read. Keeping
         * [activeDeviceId] honest also makes device-lost detection track the real device.
         */
        fun refreshActiveDeviceFromRouting(record: AudioRecord) {
            val routed = runCatching { record.routedDevice }.getOrNull() ?: return
            if (routed.id == activeDeviceId) return
            Log.i(TAG, "Capture routed to ${routed.productName} (type=${routed.type}); updating active device")
            activeDeviceId = routed.id
            _activeDeviceLabel.value = summaryFor(routed).productName
        }

        fun setOnActiveDeviceLostListener(listener: (() -> Unit)?) {
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
            _activeDeviceLabel.value = null
        }

        companion object {
            private const val TAG = "AudioInputDeviceSelector"
            private const val FALLBACK_PRODUCT_NAME = "Unknown device"
            private const val COMPOSITE_KEY_PREFIX = "device:"

            fun rankDevices(devices: List<AudioDeviceInfo>): List<AudioDeviceInfo> {
                if (devices.isEmpty()) return emptyList()
                return devices.sortedWith(
                    compareBy(
                        { devicePriority(it.type) },
                        { it.productName?.toString().orEmpty() },
                    ),
                )
            }

            fun devicePriority(type: Int): Int =
                when (type) {
                    AudioDeviceInfo.TYPE_BUILTIN_MIC -> 0
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    -> 1
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_AUX_LINE,
                    -> 2
                    AudioDeviceInfo.TYPE_BLE_HEADSET -> 3
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 4
                    else -> 5
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

            private fun summaryFor(device: AudioDeviceInfo): AudioInputDeviceSummary {
                val productName = device.productName?.toString().orEmpty()
                return AudioInputDeviceSummary(
                    id = device.id,
                    productName = productName.ifBlank { FALLBACK_PRODUCT_NAME },
                    typeLabel = typeLabel(device.type),
                    address = device.address,
                    selectionKey = selectionKeyFor(device.type, device.address, productName),
                )
            }
        }

        private fun inputDevices(): List<AudioDeviceInfo> {
            return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        }
    }
