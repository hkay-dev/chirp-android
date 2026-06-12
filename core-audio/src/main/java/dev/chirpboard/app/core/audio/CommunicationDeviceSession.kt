package dev.chirpboard.app.core.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the process-wide communication-device route needed for classic Bluetooth (SCO)
 * microphone capture (MIC-006). [android.media.AudioRecord.setPreferredDevice] is
 * best-effort and does not, by contract, bring up the SCO link: on OEM builds whose
 * audio policy does not auto-activate it for an explicitly selected SCO input, picking
 * a classic-BT headset mic captures the built-in mic or pure silence. The supported
 * API-31+ recipe is [AudioManager.setCommunicationDevice] for the session's duration
 * with [AudioManager.clearCommunicationDevice] on stop — deliberately WITHOUT setting
 * MODE_IN_COMMUNICATION, which capture routing does not need and which would change
 * playback behavior app-wide.
 *
 * Holds are refcounted and idempotent so [AudioInputDeviceSelector] can take one hold
 * per capture session and release it from the same token teardown every engine already
 * funnels through; [release] clears the platform route only when the last hold drops.
 * SCO link bring-up is asynchronous (~1 s), so [acquire] suspends until
 * [AudioManager.OnCommunicationDeviceChangedListener] confirms the route (bounded by
 * [ACTIVATION_TIMEOUT_MS]) and reports failure so the caller can fall back to default
 * routing. CRITICAL: every failure path inside [acquire] restores default routing for
 * anything it may have half-applied — an unbalanced acquire must never strand the
 * phone in headset routing after capture ends.
 *
 * NOTE: end-to-end classic-BT capture routing still requires on-device verification
 * (Samsung + Pixel) per ONDEVICE.md — setCommunicationDevice acceptance and SCO link
 * behavior are OEM- and version-dependent, and only a physical headset proves the
 * audio truly comes from the headset mic.
 */
class CommunicationDeviceSession(
    private val audioManager: AudioManager,
    /**
     * Executor for the activation listener; direct execution by default — the callback
     * only completes the attempt's deferred, so it is safe on the platform's dispatch
     * thread and keeps unit tests free of a Looper dependency.
     */
    private val listenerExecutor: Executor = Executor { it.run() },
) {
    /** Guards [refCount] and [activeDeviceId]. */
    private val lock = Any()

    /** Outstanding holds; the platform route is cleared when this returns to zero. */
    private var refCount = 0

    /** Id of the device the current holds engaged; null while disengaged. */
    private var activeDeviceId: Int? = null

    /**
     * Engages [device] as the platform communication device and suspends until the
     * platform confirms the route (or [ACTIVATION_TIMEOUT_MS] elapses). Returns true
     * and takes one refcounted hold on success; returns false WITHOUT a hold on
     * rejection or timeout, after restoring default routing so a failed attempt never
     * leaves the route half-applied. Re-acquiring the already-engaged device succeeds
     * immediately (idempotent), so refresh-style double engagement can never stack a
     * second platform request.
     */
    suspend fun acquire(device: AudioDeviceInfo): Boolean {
        synchronized(lock) {
            if (refCount > 0 && activeDeviceId == device.id) {
                refCount += 1
                return true
            }
        }
        val activated = CompletableDeferred<Unit>()
        val listener =
            AudioManager.OnCommunicationDeviceChangedListener { current ->
                if (current?.id == device.id) activated.complete(Unit)
            }
        // Listener before set: registering after could miss an immediately-dispatched
        // change event and turn a successful activation into a spurious timeout.
        audioManager.addOnCommunicationDeviceChangedListener(listenerExecutor, listener)
        try {
            val accepted = runCatching { audioManager.setCommunicationDevice(device) }.getOrDefault(false)
            if (!accepted) {
                Log.w(TAG, "setCommunicationDevice rejected for ${device.productName} (id=${device.id})")
                rollbackFailedAttempt()
                return false
            }
            if (!awaitActivation(device, activated)) {
                Log.w(TAG, "Communication device ${device.productName} not active after ${ACTIVATION_TIMEOUT_MS}ms")
                rollbackFailedAttempt()
                return false
            }
            synchronized(lock) {
                refCount += 1
                activeDeviceId = device.id
            }
            Log.i(TAG, "Communication device engaged: ${device.productName} (id=${device.id})")
            return true
        } catch (e: CancellationException) {
            // A cancelled engine start must not strand the half-applied route.
            rollbackFailedAttempt()
            throw e
        } finally {
            runCatching { audioManager.removeOnCommunicationDeviceChangedListener(listener) }
        }
    }

    /**
     * Drops one hold; restores default routing via [AudioManager.clearCommunicationDevice]
     * only when the last hold drops. Idempotent: releasing with no outstanding hold is a
     * no-op, so a doubled teardown can never clear a route a newer hold owns.
     */
    fun release() {
        synchronized(lock) {
            if (refCount == 0) return
            refCount -= 1
            if (refCount > 0) return
            activeDeviceId = null
        }
        runCatching { audioManager.clearCommunicationDevice() }
    }

    /**
     * True once the platform reports [device] as the communication device: immediately
     * when the set applied synchronously (or the platform had already auto-activated the
     * route — common where audio policy brings SCO up for explicitly selected inputs,
     * which this path must never regress with a pointless wait), else when the change
     * listener confirms within the timeout.
     */
    private suspend fun awaitActivation(
        device: AudioDeviceInfo,
        activated: CompletableDeferred<Unit>,
    ): Boolean {
        val alreadyActive =
            runCatching { audioManager.communicationDevice?.id == device.id }.getOrDefault(false)
        if (alreadyActive) return true
        return withTimeoutOrNull(ACTIVATION_TIMEOUT_MS) { activated.await() } != null
    }

    /**
     * Restores default routing after a failed or cancelled [acquire] attempt — unless
     * older holds remain, in which case the route stays theirs and their final [release]
     * clears it, so a failed switch can never yank routing out from under a live hold.
     */
    private fun rollbackFailedAttempt() {
        val clear = synchronized(lock) { refCount == 0 }
        if (clear) {
            runCatching { audioManager.clearCommunicationDevice() }
        }
    }

    companion object {
        private const val TAG = "CommunicationDeviceSession"

        /** Bound on the asynchronous SCO/communication-route bring-up (~1 s typical). */
        const val ACTIVATION_TIMEOUT_MS = 2_000L
    }
}
