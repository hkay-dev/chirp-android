package dev.chirpboard.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Singleton manager for SherpaRecognizer to ensure model is loaded once
 * and shared across keyboard service and voice recognition activity.
 */
object RecognizerManager {
    private const val TAG = "RecognizerManager"
    
    @Volatile
    private var recognizer: SherpaRecognizer? = null
    
    private val mutex = Mutex()
    
    suspend fun getRecognizer(context: Context): SherpaRecognizer {
        recognizer?.let { return it }
        
        return mutex.withLock {
            recognizer ?: createRecognizer(context).also {
                recognizer = it
            }
        }
    }
    
    private suspend fun createRecognizer(context: Context): SherpaRecognizer = withContext(Dispatchers.IO) {
        Log.d(TAG, "Creating SherpaRecognizer singleton...")
        val rec = SherpaRecognizer(context.applicationContext)
        val success = rec.initialize()
        if (!success) {
            Log.e(TAG, "Failed to initialize recognizer")
        } else {
            Log.d(TAG, "Recognizer initialized successfully")
        }
        rec
    }
}
