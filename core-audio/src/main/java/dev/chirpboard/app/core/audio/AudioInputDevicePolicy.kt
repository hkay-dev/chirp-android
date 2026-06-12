package dev.chirpboard.app.core.audio

enum class AudioInputDevicePolicy(
    val storageValue: String,
) {
    Automatic("automatic"),
    PreferBuiltIn("prefer_built_in"),
    Manual("manual"),
    ;

    companion object {
        val DEFAULT = Automatic

        fun fromStorageValue(value: String?): AudioInputDevicePolicy =
            entries.firstOrNull { it.storageValue == value } ?: DEFAULT
    }
}

/**
 * Coarse device family for an audio input, decoupled from the raw
 * [android.media.AudioDeviceInfo] type ints so UI surfaces can choose icons and the
 * selector can rank fallback priority without referencing platform constants.
 */
enum class AudioInputDeviceKind {
    BuiltIn,
    Usb,
    WiredHeadset,
    BluetoothLe,
    Bluetooth,
    Other,
}

data class AudioInputDeviceSummary(
    val id: Int,
    /**
     * Human-readable display name. For Bluetooth devices whose real name is hidden
     * (no BLUETOOTH_CONNECT grant) this degrades to the type label; see
     * [bluetoothNameHidden].
     */
    val productName: String,
    val typeLabel: String,
    val kind: AudioInputDeviceKind,
    val address: String?,
    /**
     * Stable key persisted for manual selection: the hardware address when present,
     * otherwise a type+name composite (devices with blank addresses — Bluetooth without
     * BLUETOOTH_CONNECT, wired headsets — could previously never be selected manually).
     */
    val selectionKey: String,
    /**
     * True when this is a Bluetooth device whose real product name is unavailable
     * because the app lacks BLUETOOTH_CONNECT. Pickers use this to offer the
     * runtime-permission rationale; everything else keeps working off the type label.
     */
    val bluetoothNameHidden: Boolean = false,
)

/**
 * The input device a capture session actually selected at start, published app-wide so
 * every surface (record screen, keyboard, recognition dialog, settings) can display it.
 */
data class ActiveInputDevice(
    val summary: AudioInputDeviceSummary,
    /**
     * When the user's preferred device was absent at capture start, the name of that
     * missing device — surfaces show a transient "using [summary] instead" notice.
     * Null when the preference was honored (or none was set).
     */
    val fallbackFromPreferredName: String? = null,
)

/** Result of the capture-start selection algorithm over the live device list. */
data class InputDeviceChoice(
    val device: AudioInputDeviceSummary?,
    /** True when a manual preference exists but its device is not currently connected. */
    val preferredMissing: Boolean,
)
