package dev.chirpboard.app

import androidx.annotation.VisibleForTesting
import java.security.MessageDigest

/**
 * Detects legacy placeholder Gemini keys that earlier app versions shipped as defaults.
 *
 * Only SHA-256 digests are stored. The original defaults were removed from the repository and
 * revoked at the provider, but existing installations may still need to recognize them during
 * migration.
 */
internal object KnownGeminiPlaceholderKeys {
    @VisibleForTesting
    internal val PLACEHOLDER_KEY_SHA256: Set<String> =
        setOf(
            "57d326ed60433e08cb728736673c7a5a3549647255dc504d8a15b3b0f37b1e51",
            "d7db792d52d8be2941d5a492654370ed935da931173feabfab6e2d699c276ef1",
            "fe3187ef44aa01ca9f9538e0b6342b46fc06182397baad62cf8ffa3ba7e63fde",
        )

    fun isPlaceholder(key: String): Boolean = isPlaceholder(key, PLACEHOLDER_KEY_SHA256)

    @VisibleForTesting
    internal fun isPlaceholder(
        key: String,
        knownDigests: Set<String>,
    ): Boolean {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            return true
        }
        return sha256(trimmed) in knownDigests
    }

    @VisibleForTesting
    internal fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
