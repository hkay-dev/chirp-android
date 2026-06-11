package dev.chirpboard.app.core.recording

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Allows non-keyboard surfaces (for example the home-screen widget) to stop an active
 * keyboard quick-capture session without routing through [RecordingService].
 */
@Singleton
class KeyboardRecordingStopBridge
    @Inject
    constructor() {
        class Registration internal constructor(
            internal val handler: () -> Boolean,
        )

        private val stopHandler = AtomicReference<Registration?>(null)

        fun registerStopHandler(handler: () -> Boolean): Registration {
            val registration = Registration(handler)
            stopHandler.set(registration)
            return registration
        }

        fun clearStopHandler(registration: Registration) {
            stopHandler.compareAndSet(registration, null)
        }

        /** Returns true when a keyboard handler accepted the stop request. */
        fun requestStop(): Boolean {
            val registration = stopHandler.get() ?: return false
            return registration.handler.invoke()
        }
    }
