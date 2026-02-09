package dev.chirpboard.app.feature.recording.service

import java.util.concurrent.atomic.AtomicBoolean

internal class StopRequestGate {
    private val inProgress = AtomicBoolean(false)

    fun tryBegin(): Boolean = inProgress.compareAndSet(false, true)

    fun isInProgress(): Boolean = inProgress.get()

    fun reset() {
        inProgress.set(false)
    }
}
