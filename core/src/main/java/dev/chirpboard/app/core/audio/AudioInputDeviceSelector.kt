package dev.chirpboard.app.core.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
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

        private var activeDeviceId: Int? = null
        private var onActiveDeviceLost: (() -> Unit)? = null

        private val deviceCallback =
            object : AudioDeviceCallback() {
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.registerAudioDeviceCallback(deviceCallback, null)
            }
        }

        suspend fun listInputDevices(): List<AudioInputDeviceSummary> =
            inputDevices().map { device ->
                AudioInputDeviceSummary(
                    id = device.id,
                    productName = device.productName?.toString().orEmpty().ifBlank { "Unknown device" },
                    typeLabel = typeLabel(device.type),
                    address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) device.address else null,
                )
            }

        suspend fun resolvePreferredDevice(): AudioDeviceInfo? {
            val settings = audioSettingsStore.currentSettings()
            val devices = inputDevices()
            val resolved =
                when (settings.inputDevicePolicy) {
                    AudioInputDevicePolicy.Manual -> {
                        val manualAddress = settings.manualDeviceAddress
                        devices.firstOrNull { device ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                device.address == manualAddress
                            } else {
                                device.id.toString() == manualAddress
                            }
                        } ?: rankDevices(devices).firstOrNull()
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
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || device == null) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    Log.w(TAG, "Preferred input device requires API 28+")
                }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && device != null) {
                record.setPreferredDevice(device)
                activeDeviceId = device.id
                _activeDeviceLabel.value = summaryFor(device).productName
            }
            return record
        }

        fun setOnActiveDeviceLostListener(listener: (() -> Unit)?) {
            onActiveDeviceLost = listener
        }

        fun clearActiveDevice() {
            activeDeviceId = null
            _activeDeviceLabel.value = null
        }

        companion object {
            private const val TAG = "AudioInputDeviceSelector"

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
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 3
                    else -> 4
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
                    else -> "Other"
                }

            private fun summaryFor(device: AudioDeviceInfo): AudioInputDeviceSummary =
                AudioInputDeviceSummary(
                    id = device.id,
                    productName = device.productName?.toString().orEmpty().ifBlank { "Unknown device" },
                    typeLabel = typeLabel(device.type),
                    address =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            device.address
                        } else {
                            device.id.toString()
                        },
                )
        }

        private fun inputDevices(): List<AudioDeviceInfo> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
            return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        }
    }
