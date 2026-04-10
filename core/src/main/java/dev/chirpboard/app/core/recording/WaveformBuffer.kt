package dev.chirpboard.app.core.recording

import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Stable
class WaveformBuffer(val capacity: Int = 1000) {
    private val buffer = FloatArray(capacity)
    private var writeIndex = 0
    private var sampleCount = 0

    private val _dataVersion = MutableStateFlow(0L)
    val dataVersion: StateFlow<Long> = _dataVersion.asStateFlow()

    val count: Int
        get() = synchronized(this) { sampleCount }

    fun add(amplitude: Float) {
        synchronized(this) {
            buffer[writeIndex] = amplitude
            writeIndex = (writeIndex + 1) % capacity
            if (sampleCount < capacity) sampleCount++
            _dataVersion.value++
        }
    }

    fun clear() {
        synchronized(this) {
            buffer.fill(0f)
            writeIndex = 0
            sampleCount = 0
            _dataVersion.value++
        }
    }

    fun get(i: Int): Float = synchronized(this) {
        if (i < 0 || i >= sampleCount) {
            0f
        } else {
            val start = if (sampleCount < capacity) 0 else writeIndex
            buffer[(start + i) % capacity]
        }
    }

    fun lastOrNull(): Float? = synchronized(this) {
        if (sampleCount == 0) {
            null
        } else {
            val lastIndex = if (writeIndex == 0) capacity - 1 else writeIndex - 1
            buffer[lastIndex]
        }
    }
}
