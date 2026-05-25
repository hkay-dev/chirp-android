package dev.chirpboard.app.core.recording

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecordingPermissionGuardTest {
    private val context = mockk<Context>()

    @Before
    fun setUp() {
        mockkStatic(ContextCompat::class)
    }

    @After
    fun tearDown() {
        unmockkStatic(ContextCompat::class)
    }

    @Test
    fun `hasRecordAudioPermission returns true when granted`() {
        every {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
        } returns PackageManager.PERMISSION_GRANTED

        assertTrue(RecordingPermissionGuard.hasRecordAudioPermission(context))
    }

    @Test
    fun `hasRecordAudioPermission returns false when denied`() {
        every {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
        } returns PackageManager.PERMISSION_DENIED

        assertFalse(RecordingPermissionGuard.hasRecordAudioPermission(context))
    }
}
