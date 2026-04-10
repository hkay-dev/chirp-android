package dev.chirpboard.app.core.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WaveformBuffer(val capacity: Int = 1000) {
    val buffer = FloatArray(capacity)
    var index = 0
        private set
    var count = 0
        private set
    
    private val _dataVersion = MutableStateFlow(0L)
    val dataVersion: StateFlow<Long> = _dataVersion.asStateFlow()

    fun add(amplitude: Float) {
        synchronized(this) {
            buffer[index] = amplitude
            index = (index + 1) % capacity
            if (count < capacity) count++
            _dataVersion.value++
        }
    }

    fun clear() {
        synchronized(this) {
            for (i in 0 until capacity) {
                buffer[i] = 0f
            }
            index = 0
            count = 0
            _dataVersion.value++
        }
    }
    
    fun get(i: Int): Float {
        if (i < 0 || i >= count) return 0f
        val start = if (count < capacity) 0 else index
        return buffer[(start + i) % capacity]
    }
    
    fun lastOrNull(): Float? {
        if (count == 0) return null
        val idx = if (index == 0) capacity - 1 else index - 1
        return buffer[idx]
    }
}
