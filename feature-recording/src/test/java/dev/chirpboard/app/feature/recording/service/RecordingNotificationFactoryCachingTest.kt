package dev.chirpboard.app.feature.recording.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * PRF-5: the notification's launch/action PendingIntents are identical for the life of
 * the process, so repeated notification posts (state transitions, warning-line updates)
 * must REUSE the cached instances — every rebuild costs a binder round-trip into
 * system_server. These tests pin the update-not-rebuild contract at the factory seam.
 */
class RecordingNotificationFactoryCachingTest {
    private val applicationContext = mockk<android.content.Context>(relaxed = true)
    private val service =
        mockk<Service>(relaxed = true) {
            every { this@mockk.applicationContext } returns this@RecordingNotificationFactoryCachingTest.applicationContext
        }

    @Before
    fun setUp() {
        mockkStatic(PendingIntent::class)
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setAction(any()) } returns mockk()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun launchPendingIntent_isCreatedOnceAndReusedAcrossPosts() {
        val factory = RecordingNotificationFactory()
        val launchIntent = mockk<Intent>(relaxed = true)
        every { service.packageManager.getLaunchIntentForPackage(any()) } returns launchIntent
        val cachedPendingIntent = mockk<PendingIntent>()
        every { PendingIntent.getActivity(any(), any(), any(), any()) } returns cachedPendingIntent

        val first = factory.launchPendingIntent(service)
        val second = factory.launchPendingIntent(service)

        assertSame(cachedPendingIntent, first)
        assertSame(first, second)
        verify(exactly = 1) { PendingIntent.getActivity(any(), any(), any(), any()) }
    }

    @Test
    fun serviceActionPendingIntents_areCreatedOncePerActionAndReused() {
        val factory = RecordingNotificationFactory()
        every { PendingIntent.getService(any(), any(), any(), any()) } answers { mockk() }

        val stop = factory.stopActionPendingIntent(service)
        val resume = factory.resumeActionPendingIntent(service)
        val pause = factory.pauseActionPendingIntent(service)

        // A second post (e.g. silence-warning refresh) must not touch system_server again.
        assertSame(stop, factory.stopActionPendingIntent(service))
        assertSame(resume, factory.resumeActionPendingIntent(service))
        assertSame(pause, factory.pauseActionPendingIntent(service))
        verify(exactly = 3) { PendingIntent.getService(any(), any(), any(), any()) }
    }
}
