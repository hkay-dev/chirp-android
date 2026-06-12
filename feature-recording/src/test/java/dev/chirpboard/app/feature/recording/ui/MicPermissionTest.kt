package dev.chirpboard.app.feature.recording.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.core.app.ActivityCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ERR-7: the mic-permission re-request affordance. The permanent-denial decision drives
 * whether the record surfaces re-launch the system dialog or deep-link to app settings —
 * misclassifying it produces either a dead Retry loop or a needless settings round trip.
 */
class MicPermissionTest {
    @Before
    fun setup() {
        mockkStatic(ActivityCompat::class)
    }

    @After
    fun tearDown() {
        unmockkStatic(ActivityCompat::class)
    }

    @Test
    fun `findActivity unwraps a context wrapper chain to the activity`() {
        val activity = mockk<Activity>()
        val inner = mockk<ContextWrapper> { every { baseContext } returns activity }
        val outer = mockk<ContextWrapper> { every { baseContext } returns inner }

        assertSame(activity, outer.findActivity())
    }

    @Test
    fun `findActivity returns null for a non-activity context`() {
        val wrapper = mockk<ContextWrapper> { every { baseContext } returns mockk<Context>() }

        assertNull(wrapper.findActivity())
    }

    @Test
    fun `denial is not treated as permanent without an activity to ask`() {
        // A non-activity context cannot show the system dialog; reporting "permanently
        // denied" here would wrongly steer the UI to the settings deep link.
        assertFalse(isMicPermissionPermanentlyDenied(mockk<Context>()))
    }

    @Test
    fun `denial is permanent only when the system will no longer show the dialog`() {
        val activity = mockk<Activity>()
        every {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
        } returns false
        assertTrue(isMicPermissionPermanentlyDenied(activity))

        every {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
        } returns true
        assertFalse(isMicPermissionPermanentlyDenied(activity))
    }
}
