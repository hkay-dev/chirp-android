package dev.chirpboard.app.feature.llm.settings

import androidx.annotation.VisibleForTesting
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal object LlmApiKeyBackupCodec {
    private val magicBytes = "CHIRPKEY1".encodeToByteArray()

    /** v1 wrote 120k PBKDF2 iterations; kept only so old backup files stay restorable. */
    private const val VERSION_1: Byte = 1

    /** v2 (SEC-10): PBKDF2-HMAC-SHA256 iterations raised to the OWASP-current 600k. */
    private const val VERSION_2: Byte = 2
    private const val VERSION: Byte = VERSION_2

    private const val PBKDF2_ITERATIONS_V1 = 120_000
    private const val PBKDF2_ITERATIONS_V2 = 600_000
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val KEY_LENGTH_BITS = 256
    private const val GCM_TAG_LENGTH_BITS = 128

    fun encrypt(
        payloadJson: String,
        passphrase: CharArray,
    ): ByteArray = encryptWithVersion(payloadJson, passphrase, VERSION)

    /** Internal seam so tests can produce v1-format files and prove the compat decrypt path. */
    @VisibleForTesting
    internal fun encryptWithVersion(
        payloadJson: String,
        passphrase: CharArray,
        version: Byte,
    ): ByteArray {
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt, iterationsFor(version))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(payloadJson.toByteArray(StandardCharsets.UTF_8))

        return buildList {
            addAll(magicBytes.toList())
            add(version)
            addAll(salt.toList())
            addAll(iv.toList())
            addAll(ciphertext.toList())
        }.toByteArray()
    }

    fun decrypt(
        encrypted: ByteArray,
        passphrase: CharArray,
    ): String {
        require(encrypted.size > magicBytes.size + 1 + SALT_LENGTH + IV_LENGTH + 16) {
            "Backup file is not valid"
        }

        val magic = encrypted.copyOfRange(0, magicBytes.size)
        if (!magic.contentEquals(magicBytes)) {
            throw IllegalArgumentException("Backup file is not valid")
        }

        var offset = magicBytes.size
        val version = encrypted[offset]
        offset += 1
        if (version != VERSION_1 && version != VERSION_2) {
            throw IllegalArgumentException("Unsupported backup version")
        }

        val salt = encrypted.copyOfRange(offset, offset + SALT_LENGTH)
        offset += SALT_LENGTH
        val iv = encrypted.copyOfRange(offset, offset + IV_LENGTH)
        offset += IV_LENGTH
        val ciphertext = encrypted.copyOfRange(offset, encrypted.size)

        val key = deriveKey(passphrase, salt, iterationsFor(version))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

        return try {
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            throw IllegalArgumentException("Incorrect passphrase or corrupted backup file")
        }
    }

    private fun iterationsFor(version: Byte): Int =
        if (version == VERSION_1) PBKDF2_ITERATIONS_V1 else PBKDF2_ITERATIONS_V2

    private fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
    ): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_LENGTH_BITS)
        try {
            val keyBytes = factory.generateSecret(spec).encoded
            try {
                return SecretKeySpec(keyBytes, "AES")
            } finally {
                // SEC-10: SecretKeySpec copies the bytes, so the intermediate can be zeroed.
                keyBytes.fill(0)
            }
        } finally {
            // SEC-10: drop the spec's internal passphrase copy as soon as derivation is done.
            // The caller-owned CharArray is zeroed by its owner (LlmSettingsViewModel).
            spec.clearPassword()
        }
    }
}
