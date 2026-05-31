package dev.chirpboard.app.feature.keyboard.service

import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import java.util.concurrent.Executor

/**
 * Handles phone call state changes to stop recording during calls.
 */
class PhoneCallHandler(
    private val telephonyManager: TelephonyManager,
    private val executor: Executor
) {
    var onCallStateChanged: ((isInCall: Boolean) -> Unit)? = null
    
    private var isRegistered = false
    
    private inner class CallStateCallback : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            handleCallState(state)
        }
    }
    
    private var callback: TelephonyCallback? = null
    
    private fun handleCallState(state: Int) {
        val isInCall = state == TelephonyManager.CALL_STATE_RINGING ||
                       state == TelephonyManager.CALL_STATE_OFFHOOK
        
        Log.d(TAG, "Call state changed: $state, isInCall: $isInCall")
        onCallStateChanged?.invoke(isInCall)
    }
    
    fun register() {
        if (isRegistered) return
        
        try {
            val newCallback = CallStateCallback()
            callback = newCallback
            telephonyManager.registerTelephonyCallback(executor, newCallback)
            isRegistered = true
            Log.d(TAG, "Registered TelephonyCallback")
        } catch (e: SecurityException) {
            // READ_PHONE_STATE permission not granted
            Log.w(TAG, "Cannot register call handler: permission denied", e)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to register call handler", e)
        }
    }
    
    fun unregister() {
        if (!isRegistered) return
        
        try {
            callback?.let {
                telephonyManager.unregisterTelephonyCallback(it)
            }
            callback = null
            isRegistered = false
            Log.d(TAG, "Unregistered call handler")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to unregister call handler", e)
        }
    }
    
    companion object {
        private const val TAG = "PhoneCallHandler"
    }
}
