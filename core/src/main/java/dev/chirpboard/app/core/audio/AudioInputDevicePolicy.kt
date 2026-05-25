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
)
