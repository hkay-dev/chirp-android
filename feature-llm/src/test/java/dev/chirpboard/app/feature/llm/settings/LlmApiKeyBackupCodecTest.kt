package dev.chirpboard.app.feature.llm.settings

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmApiKeyBackupCodecTest {
    private val gson = Gson()

    @Test
    fun `encrypt and decrypt round trip`() {
        val snapshot =
            LlmSettingsSnapshot(
                activeProvider = "gemini",
                models = mapOf("gemini" to "gemini-3.1-flash-lite", "openai" to "gpt-5.5"),
                apiKeys = mapOf("gemini" to "secret-gemini", "openai" to "secret-openai"),
            )
        val passphrase = "backup-passphrase".toCharArray()

        val encrypted = LlmApiKeyBackupCodec.encrypt(gson.toJson(snapshot), passphrase)
        val decryptedJson = LlmApiKeyBackupCodec.decrypt(encrypted, passphrase)
        val restored = gson.fromJson(decryptedJson, LlmSettingsSnapshot::class.java)

        assertEquals(snapshot, restored)
    }

    @Test
    fun `decrypt fails with wrong passphrase`() {
        val encrypted =
            LlmApiKeyBackupCodec.encrypt(
                payloadJson = """{"version":1,"activeProvider":"gemini","models":{},"apiKeys":{"gemini":"abc"}}""",
                passphrase = "correct-passphrase".toCharArray(),
            )

        val result =
            runCatching {
                LlmApiKeyBackupCodec.decrypt(encrypted, "wrong-passphrase".toCharArray())
            }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Incorrect passphrase") == true)
    }

    @Test
    fun `decrypt fails for invalid file`() {
        val result =
            runCatching {
                LlmApiKeyBackupCodec.decrypt("not-a-backup".encodeToByteArray(), "passphrase".toCharArray())
            }

        assertTrue(result.isFailure)
    }
}
