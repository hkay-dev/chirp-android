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
 *
 * Failed initialization attempts are not cached so callers can retry once
 * model files become available or a concurrent load finishes.
 */
object RecognizerManager {
    private const val TAG = "RecognizerManager"

    @Volatile
    private var recognizer: SherpaRecognizer? = null

    private val mutex = Mutex()

    fun peekReadyRecognizer(): SherpaRecognizer? = recognizer?.takeIf { it.isReady }

    suspend fun initializeRecognizer(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                recognizer?.takeIf { it.isReady }?.let { return@withContext true }

                recognizer?.let { stale ->
                    Log.d(TAG, "Discarding stale recognizer before re-initialization")
                    stale.release()
                    recognizer = null
                }

                Log.d(TAG, "Creating SherpaRecognizer singleton...")
                val rec = SherpaRecognizer(context.applicationContext)
                val success = rec.initialize()
                if (success) {
                    recognizer = rec
                    Log.d(TAG, "Recognizer initialized successfully")
                } else {
                    rec.release()
                    Log.e(TAG, "Failed to initialize recognizer")
                }
                success
            }
        }

    suspend fun releaseRecognizer() {
        mutex.withLock {
            Log.d(TAG, "Releasing SherpaRecognizer singleton from memory...")
            recognizer?.release()
            recognizer = null
        }
    }
}
