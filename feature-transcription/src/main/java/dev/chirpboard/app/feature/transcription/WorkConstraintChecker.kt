package dev.chirpboard.app.feature.transcription

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks device constraints that may block transcription WorkManager jobs.
 * Provides user-friendly messages when constraints are not met.
 */
@Singleton
class WorkConstraintChecker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    sealed class ConstraintStatus {
        object Ready : ConstraintStatus()
        data class BatteryLow(val currentPercent: Int) : ConstraintStatus()
        data class StorageLow(val availableMb: Long) : ConstraintStatus()
        data class BatteryAndStorageLow(val batteryPercent: Int, val storageMb: Long) : ConstraintStatus()
    }
    
    companion object {
        private const val TAG = "WorkConstraintChecker"
        private const val MIN_BATTERY_PERCENT = 15
        private const val MIN_STORAGE_MB = 100L
    }
    
    fun checkConstraints(): ConstraintStatus {
        val batteryPercent = getBatteryPercent()
        val storageMb = getAvailableStorageMb()
        
        val batteryOk = batteryPercent >= MIN_BATTERY_PERCENT || isCharging()
        val storageOk = storageMb >= MIN_STORAGE_MB
        
        Log.d(TAG, "Battery: $batteryPercent% (charging: ${isCharging()}), Storage: ${storageMb}MB")
        
        return when {
            !batteryOk && !storageOk -> ConstraintStatus.BatteryAndStorageLow(batteryPercent, storageMb)
            !batteryOk -> ConstraintStatus.BatteryLow(batteryPercent)
            !storageOk -> ConstraintStatus.StorageLow(storageMb)
            else -> ConstraintStatus.Ready
        }
    }
    
    fun getConstraintMessage(status: ConstraintStatus): String? {
        return when (status) {
            is ConstraintStatus.Ready -> null
            is ConstraintStatus.BatteryLow -> 
                "Battery low (${status.currentPercent}%). Transcription will start when charging or battery is above $MIN_BATTERY_PERCENT%."
            is ConstraintStatus.StorageLow -> 
                "Storage low (${status.availableMb}MB free). Transcription needs at least ${MIN_STORAGE_MB}MB."
            is ConstraintStatus.BatteryAndStorageLow -> 
                "Battery low (${status.batteryPercent}%) and storage low (${status.storageMb}MB). Please charge and free up space."
        }
    }
    
    private fun getBatteryPercent(): Int {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) {
            (level * 100 / scale)
        } else {
            100 // Assume full if unknown
        }
    }
    
    private fun isCharging(): Boolean {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || 
               status == BatteryManager.BATTERY_STATUS_FULL
    }
    
    private fun getAvailableStorageMb(): Long {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            stat.availableBytes / (1024 * 1024)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to get storage info", e)
            Long.MAX_VALUE // Assume OK if unknown
        }
    }
}
