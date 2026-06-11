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
        private val stopHandler = AtomicReference<(() -> Boolean)?>(null)

        fun registerStopHandler(handler: () -> Boolean) {
            stopHandler.set(handler)
        }

        fun clearStopHandler() {
            stopHandler.set(null)
        }

        /** Returns true when a keyboard handler accepted the stop request. */
        fun requestStop(): Boolean {
            val handler = stopHandler.get() ?: return false
            return handler.invoke()
        }
    }
