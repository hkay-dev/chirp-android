package dev.chirpboard.app

import java.security.MessageDigest

/**
 * Detects legacy placeholder Gemini keys without embedding the raw secrets in source.
 */
internal object KnownGeminiPlaceholderKeys {
    private val PLACEHOLDER_KEY_SHA256: Set<String> =
        setOf(
            "fe3187ef44aa01ca9f9538e0b6342b46fc06182397baad62cf8ffa3ba7e63fde",
            "57d326ed60433e08cb728736673c7a5a3549647255dc504d8a15b3b0f37b1e51",
        )

    fun isPlaceholder(key: String): Boolean {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            return true
        }
        return PLACEHOLDER_KEY_SHA256.contains(sha256(trimmed))
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
