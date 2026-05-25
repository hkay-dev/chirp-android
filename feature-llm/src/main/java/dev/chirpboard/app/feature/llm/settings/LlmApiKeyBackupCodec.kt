package dev.chirpboard.app.feature.llm.settings

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal object LlmApiKeyBackupCodec {
    private val magicBytes = "CHIRPKEY1".encodeToByteArray()
    private const val VERSION: Byte = 1
    private const val PBKDF2_ITERATIONS = 120_000
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val KEY_LENGTH_BITS = 256
    private const val GCM_TAG_LENGTH_BITS = 128

    fun encrypt(
        payloadJson: String,
        passphrase: CharArray,
    ): ByteArray {
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(payloadJson.toByteArray(StandardCharsets.UTF_8))

        return buildList {
            addAll(magicBytes.toList())
            add(VERSION)
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
        if (version != VERSION) {
            throw IllegalArgumentException("Unsupported backup version")
        }

        val salt = encrypted.copyOfRange(offset, offset + SALT_LENGTH)
        offset += SALT_LENGTH
        val iv = encrypted.copyOfRange(offset, offset + IV_LENGTH)
        offset += IV_LENGTH
        val ciphertext = encrypted.copyOfRange(offset, encrypted.size)

        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

        return try {
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            throw IllegalArgumentException("Incorrect passphrase or corrupted backup file")
        }
    }

    private fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
    ): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val secret = factory.generateSecret(spec)
        return SecretKeySpec(secret.encoded, "AES")
    }
}
