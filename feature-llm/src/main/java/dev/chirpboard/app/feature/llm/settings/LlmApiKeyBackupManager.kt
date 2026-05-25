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

        suspend fun exportToUri(
            uri: Uri,
            passphrase: CharArray,
        ): Result<Int> =
            withContext(Dispatchers.IO) {
                runCatching {
                    if (!preferences.isSecureStorageAvailable()) {
                        error("Secure storage unavailable on this device")
                    }

                    val snapshot = preferences.buildSettingsSnapshot()
                    if (snapshot.apiKeys.isEmpty()) {
                        error("No API keys are saved yet")
                    }

                    val encrypted =
                        LlmApiKeyBackupCodec.encrypt(
                            payloadJson = gson.toJson(snapshot),
                            passphrase = passphrase,
                        )

                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(encrypted)
                    } ?: error("Could not write backup file")

                    snapshot.apiKeys.size
                }
            }

        suspend fun importFromUri(
            uri: Uri,
            passphrase: CharArray,
        ): Result<Int> =
            withContext(Dispatchers.IO) {
                runCatching {
                    if (!preferences.isSecureStorageAvailable()) {
                        error("Secure storage unavailable on this device")
                    }

                    val encrypted =
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            input.readBytes()
                        } ?: error("Could not read backup file")

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
    }
