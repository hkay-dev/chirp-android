package dev.chirpboard.app

import androidx.annotation.VisibleForTesting

/**
 * Detects legacy placeholder Gemini keys that earlier app versions shipped as defaults.
 *
 * These are dummy/revoked placeholder values, not live secrets, so they are stored as plain
 * constants in this single location. Hashing them added no protection (the raw values are
 * recoverable from git history and were already duplicated verbatim in tests) while obscuring
 * what the set actually contains.
 */
internal object KnownGeminiPlaceholderKeys {
    @VisibleForTesting
    val PLACEHOLDER_KEYS: Set<String> =
        setOf(
            "REMOVED_GOOGLE_API_KEY",
            "REMOVED_GOOGLE_API_KEY",
        )

    fun isPlaceholder(key: String): Boolean {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            return true
        }
        return PLACEHOLDER_KEYS.contains(trimmed)
    }
}
