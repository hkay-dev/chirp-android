package dev.chirpboard.app.feature.keyboard.service

import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

/**
 * Handles phone call state changes to stop recording during calls.
 * Supports both modern TelephonyCallback (API 31+) and legacy PhoneStateListener.
 */
class PhoneCallHandler(
    private val telephonyManager: TelephonyManager,
    private val executor: Executor
) {
    var onCallStateChanged: ((isInCall: Boolean) -> Unit)? = null
    
    private var isRegistered = false
    
    // Modern callback for API 31+
    @RequiresApi(Build.VERSION_CODES.S)
    private inner class CallStateCallback : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            handleCallState(state)
        }
    }
    
    // Legacy listener for API < 31
    @Suppress("DEPRECATION")
    private val legacyListener = object : PhoneStateListener() {
        @Deprecated("Deprecated in API 31")
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            handleCallState(state)
        }
    }
    
    private var modernCallback: Any? = null // Store as Any to avoid class loading issues on older APIs
    
    private fun handleCallState(state: Int) {
        val isInCall = state == TelephonyManager.CALL_STATE_RINGING ||
                       state == TelephonyManager.CALL_STATE_OFFHOOK
        
        Log.d(TAG, "Call state changed: $state, isInCall: $isInCall")
        onCallStateChanged?.invoke(isInCall)
    }
    
    fun register() {
        if (isRegistered) return
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = CallStateCallback()
                modernCallback = callback
                telephonyManager.registerTelephonyCallback(executor, callback)
                Log.d(TAG, "Registered TelephonyCallback (API 31+)")
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.listen(legacyListener, PhoneStateListener.LISTEN_CALL_STATE)
                Log.d(TAG, "Registered PhoneStateListener (legacy)")
            }
            isRegistered = true
        } catch (e: SecurityException) {
            // READ_PHONE_STATE permission not granted
            Log.w(TAG, "Cannot register call handler: permission denied", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register call handler", e)
        }
    }
    
    fun unregister() {
        if (!isRegistered) return
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (modernCallback as? TelephonyCallback)?.let {
                    telephonyManager.unregisterTelephonyCallback(it)
                }
                modernCallback = null
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.listen(legacyListener, PhoneStateListener.LISTEN_NONE)
            }
            isRegistered = false
            Log.d(TAG, "Unregistered call handler")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister call handler", e)
        }
    }
    
    companion object {
        private const val TAG = "PhoneCallHandler"
    }
}
