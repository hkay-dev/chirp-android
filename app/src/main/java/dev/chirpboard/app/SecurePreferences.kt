package dev.chirpboard.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure storage for sensitive data using EncryptedSharedPreferences.
 * 
 * All API keys and secrets should be stored here, NOT in regular SharedPreferences.
 */
@Singleton
class SecurePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences? by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create EncryptedSharedPreferences", e)
            null
        }
    }
    
    var geminiApiKey: String?
        get() = prefs?.getString(KEY_GEMINI_API_KEY, null)
        set(value) {
            prefs?.edit()?.apply {
                if (value != null) {
                    putString(KEY_GEMINI_API_KEY, value)
                } else {
                    remove(KEY_GEMINI_API_KEY)
                }
                apply()
            }
        }
    
    fun hasApiKey(): Boolean = !geminiApiKey.isNullOrBlank()
    
    fun clearApiKey() {
        prefs?.edit()?.remove(KEY_GEMINI_API_KEY)?.apply()
    }
    
    /**
     * Check if secure storage is available.
     * May be unavailable on some devices due to KeyStore issues.
     */
    fun isAvailable(): Boolean = prefs != null
    
    companion object {
        private const val TAG = "SecurePreferences"
        private const val PREFS_NAME = "secure_prefs"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
    }
}
