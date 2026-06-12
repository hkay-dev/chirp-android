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

data class AudioInputDeviceSummary(
    val id: Int,
    val productName: String,
    val typeLabel: String,
    val address: String?,
    /**
     * Stable key persisted for manual selection: the hardware address when present,
     * otherwise a type+name composite (devices with blank addresses — Bluetooth without
     * BLUETOOTH_CONNECT, wired headsets — could previously never be selected manually).
     */
    val selectionKey: String,
)
