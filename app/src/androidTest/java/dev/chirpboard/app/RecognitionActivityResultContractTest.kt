package dev.chirpboard.app

import android.app.Activity
import android.app.PendingIntent
import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RecognitionActivityResultContractTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun resultPayloadIncludesStandardAndLegacyCompatibilityKeys() {
        val result = buildRecognitionActivityResult("hello world")

        assertEquals(
            arrayListOf("hello world"),
            result.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS),
        )
        assertArrayEquals(
            floatArrayOf(1f),
            result.getFloatArrayExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES),
            0f,
        )
        assertEquals("hello world", result.getStringExtra(SearchManager.QUERY))
    }

    @Test
    fun requestWithoutPendingIntentUsesActivityResult() {
        var deliveredCode: Int? = null
        var deliveredData: Intent? = null
        val data = buildRecognitionActivityResult("direct")

        val channel =
            deliverRecognitionActivityResult(
                context = context,
                request = Intent(),
                resultCode = Activity.RESULT_OK,
                data = data,
            ) { code, result ->
                deliveredCode = code
                deliveredData = result
            }

        assertEquals(RecognitionActivityResultChannel.ACTIVITY_RESULT, channel)
        assertEquals(Activity.RESULT_OK, deliveredCode)
        assertTrue(deliveredData === data)
    }

    @Test
    fun requestWithPendingIntentSendsMergedPayloadOnlyThroughPendingChannel() {
        val action = "${context.packageName}.test.RECOGNITION_RESULT.${UUID.randomUUID()}"
        val received = AtomicReference<Intent>()
        val receivedLatch = CountDownLatch(1)
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    received.set(intent)
                    receivedLatch.countDown()
                }
            }
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)

        try {
            val pendingIntent =
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
            val callerBundle = Bundle().apply { putString("caller-marker", "preserved") }
            val request =
                Intent().apply {
                    putExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT, pendingIntent)
                    putExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE, callerBundle)
                }
            var activityResultCalled = false

            val channel =
                deliverRecognitionActivityResult(
                    context = context,
                    request = request,
                    resultCode = Activity.RESULT_OK,
                    data = buildRecognitionActivityResult("pending"),
                ) { _, _ -> activityResultCalled = true }

            assertEquals(RecognitionActivityResultChannel.PENDING_INTENT, channel)
            assertFalse(activityResultCalled)
            assertTrue(receivedLatch.await(2, TimeUnit.SECONDS))
            assertEquals(
                arrayListOf("pending"),
                received.get().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS),
            )
            assertEquals("pending", received.get().getStringExtra(SearchManager.QUERY))
            assertEquals("preserved", received.get().getStringExtra("caller-marker"))
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    @Test
    fun cancelledPendingIntentFallsBackToActivityResult() {
        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                1,
                Intent("${context.packageName}.test.CANCELLED_RESULT").setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            ).also(PendingIntent::cancel)
        val request =
            Intent().putExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT, pendingIntent)
        var activityResultCalled = false

        val channel =
            deliverRecognitionActivityResult(
                context = context,
                request = request,
                resultCode = Activity.RESULT_OK,
                data = buildRecognitionActivityResult("fallback"),
            ) { _, _ -> activityResultCalled = true }

        assertEquals(RecognitionActivityResultChannel.ACTIVITY_RESULT_FALLBACK, channel)
        assertTrue(activityResultCalled)
    }
}
