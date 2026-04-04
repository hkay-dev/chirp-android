package dev.chirpboard.app.feature.transcription

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class WorkConstraintCheckerTest {

    private lateinit var mockContext: Context
    private lateinit var mockIntent: Intent
    private lateinit var classUnderTest: WorkConstraintChecker

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockIntent = mockk(relaxed = true)
        
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockkStatic(Environment::class)
        val mockFile = mockk<File>(relaxed = true)
        every { mockFile.path } returns "/data"
        every { Environment.getDataDirectory() } returns mockFile

        mockkConstructor(StatFs::class)

        // Default: 100% battery, charging, lots of storage
        setBatteryState(100, 100, BatteryManager.BATTERY_STATUS_CHARGING)
        setStorageMb(1000L)

        classUnderTest = WorkConstraintChecker(mockContext)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun setBatteryState(level: Int, scale: Int, status: Int) {
        every { mockContext.registerReceiver(null, any()) } returns mockIntent
        every { mockIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) } returns level
        every { mockIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1) } returns scale
        every { mockIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) } returns status
    }

    private fun setStorageMb(mb: Long) {
        every { anyConstructed<StatFs>().availableBytes } returns mb * 1024 * 1024
    }

    @Test
    fun `checkConstraints returns Ready when constraints met`() {
        val result = classUnderTest.checkConstraints()
        assertTrue(result is WorkConstraintChecker.ConstraintStatus.Ready)
    }

    @Test
    fun `checkConstraints returns BatteryLow when battery below threshold and not charging`() {
        setBatteryState(10, 100, BatteryManager.BATTERY_STATUS_DISCHARGING)
        val result = classUnderTest.checkConstraints()
        assertTrue(result is WorkConstraintChecker.ConstraintStatus.BatteryLow)
        assertEquals(10, (result as WorkConstraintChecker.ConstraintStatus.BatteryLow).currentPercent)
    }

    @Test
    fun `checkConstraints returns Ready when battery low but charging`() {
        setBatteryState(5, 100, BatteryManager.BATTERY_STATUS_CHARGING)
        val result = classUnderTest.checkConstraints()
        assertTrue(result is WorkConstraintChecker.ConstraintStatus.Ready)
    }

    @Test
    fun `checkConstraints returns StorageLow when storage below threshold`() {
        setStorageMb(50L) // MIN_STORAGE_MB is 100L
        val result = classUnderTest.checkConstraints()
        assertTrue(result is WorkConstraintChecker.ConstraintStatus.StorageLow)
        assertEquals(50L, (result as WorkConstraintChecker.ConstraintStatus.StorageLow).availableMb)
    }

    @Test
    fun `checkConstraints returns BatteryAndStorageLow when both conditions fail`() {
        setBatteryState(10, 100, BatteryManager.BATTERY_STATUS_DISCHARGING)
        setStorageMb(50L)
        val result = classUnderTest.checkConstraints()
        assertTrue(result is WorkConstraintChecker.ConstraintStatus.BatteryAndStorageLow)
        val status = result as WorkConstraintChecker.ConstraintStatus.BatteryAndStorageLow
        assertEquals(10, status.batteryPercent)
        assertEquals(50L, status.storageMb)
    }

    @Test
    fun `getConstraintMessage returns proper messages`() {
        assertNull(classUnderTest.getConstraintMessage(WorkConstraintChecker.ConstraintStatus.Ready))
        
        val batteryMsg = classUnderTest.getConstraintMessage(WorkConstraintChecker.ConstraintStatus.BatteryLow(10))
        assertTrue(batteryMsg?.contains("Battery low (10%)") == true)
        
        val storageMsg = classUnderTest.getConstraintMessage(WorkConstraintChecker.ConstraintStatus.StorageLow(50))
        assertTrue(storageMsg?.contains("Storage low (50MB free)") == true)
        
        val bothMsg = classUnderTest.getConstraintMessage(WorkConstraintChecker.ConstraintStatus.BatteryAndStorageLow(10, 50))
        assertTrue(bothMsg?.contains("Battery low (10%)") == true)
        assertTrue(bothMsg?.contains("storage low (50MB)") == true)
    }
}
