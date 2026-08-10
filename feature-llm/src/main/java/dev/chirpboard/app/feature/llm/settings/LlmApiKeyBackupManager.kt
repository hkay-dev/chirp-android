package dev.chirpboard.app.feature.llm.settings

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Passphrase-encrypted API-key payload (the CHIRPKEY container) plus its key count. */
class EncryptedKeyBackup(
    val bytes: ByteArray,
    val keyCount: Int,
)

/**
 * The device keystore / EncryptedSharedPreferences layer is unusable, so keys can neither be
 * snapshotted nor restored. Typed so callers can tell this apart from a wrong passphrase:
 * retyping the passphrase can never fix it, and the error UI must not suggest that it can.
 */
class SecureStorageUnavailableException : IllegalStateException("Secure storage unavailable on this device")

@Singleton
class LlmApiKeyBackupManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val preferences: LlmPreferences,
    ) {
        private val gson = Gson()
        fun suggestedBackupFileName(): String {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            return "chirp-llm-keys-$date.chirpkeys"
        }

        /**
         * Builds the passphrase-encrypted key snapshot in memory — the exact same CHIRPKEY
         * container [exportToUri] writes to disk. The unified Backup & Restore flow embeds
         * these bytes (base64) inside the chirp-backup JSON envelope so API keys are NEVER
         * serialized as plaintext.
         */
        suspend fun buildEncryptedSnapshot(passphrase: CharArray): Result<EncryptedKeyBackup> =
            withContext(Dispatchers.Default) {
                runCatching {
                    if (!preferences.isSecureStorageAvailable()) {
                        throw SecureStorageUnavailableException()
                    }

                    val snapshot = preferences.buildSettingsSnapshot()
                    if (snapshot.apiKeys.isEmpty()) {
                        error("No API keys are saved yet")
                    }

                    EncryptedKeyBackup(
                        bytes =
                            LlmApiKeyBackupCodec.encrypt(
                                payloadJson = gson.toJson(snapshot),
                                passphrase = passphrase,
                            ),
                        keyCount = snapshot.apiKeys.size,
                    )
                }
            }

        /**
         * Decrypts and applies a CHIRPKEY snapshot previously produced by
         * [buildEncryptedSnapshot] or [exportToUri]. Validation happens entirely before any
         * write: a wrong passphrase or corrupted payload leaves the stored keys untouched.
         * Returns the number of restored API keys.
         */
        suspend fun restoreEncryptedSnapshot(
            encrypted: ByteArray,
            passphrase: CharArray,
        ): Result<Int> =
            withContext(Dispatchers.Default) {
                runCatching {
                    if (!preferences.isSecureStorageAvailable()) {
                        throw SecureStorageUnavailableException()
                    }

                    val payloadJson = LlmApiKeyBackupCodec.decrypt(encrypted, passphrase)
                    val snapshot = gson.fromJson(payloadJson, LlmSettingsSnapshot::class.java)
                        ?: error("Backup file is not valid")

                    if (snapshot.version != LlmSettingsSnapshot.CURRENT_VERSION) {
                        error("Unsupported backup version")
                    }

                    preferences.applySettingsSnapshot(snapshot)
                    snapshot.apiKeys.size
                }
            }

        suspend fun exportToUri(
            uri: Uri,
            passphrase: CharArray,
        ): Result<Int> =
            withContext(Dispatchers.IO) {
                buildEncryptedSnapshot(passphrase).mapCatching { backup ->
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(backup.bytes)
                    } ?: error("Could not write backup file")

                    backup.keyCount
                }
            }

        suspend fun importFromUri(
            uri: Uri,
            passphrase: CharArray,
        ): Result<Int> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val encrypted =
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            input.readBytes()
                        } ?: error("Could not read backup file")

                    restoreEncryptedSnapshot(encrypted, passphrase).getOrThrow()
                }
            }
    }
