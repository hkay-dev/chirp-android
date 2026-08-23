package dev.chirpboard.app.core.recording

class WaveformBuffer(val capacity: Int = 1000) {
    private val buffer = FloatArray(capacity)
    private var writeIndex = 0
    private var sampleCount = 0

    val count: Int
        get() = synchronized(this) { sampleCount }

    fun add(amplitude: Float) {
        synchronized(this) {
            buffer[writeIndex] = amplitude
            writeIndex = (writeIndex + 1) % capacity
            if (sampleCount < capacity) sampleCount++
        }
    }

    fun clear() {
        synchronized(this) {
            buffer.fill(0f)
            writeIndex = 0
            sampleCount = 0
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

    /**
     * Copies the buffered samples oldest-to-newest into [dest] under a single lock
     * acquisition and returns how many were copied (at most `dest.size`; when [dest] is
     * smaller than the buffered sample count the newest `dest.size` samples are copied). The UI draw
     * loop uses this instead of per-sample [get] calls, which would take the monitor
     * lock once per bar per frame while the capture thread is writing.
     */
    fun snapshotInto(dest: FloatArray): Int = synchronized(this) {
        val n = minOf(sampleCount, dest.size)
        if (n == 0) return@synchronized 0
        val oldest = if (sampleCount < capacity) 0 else writeIndex
        // A dest smaller than the buffer keeps the NEWEST n samples: dropping the newest
        // instead would freeze a live waveform on the first samples of the recording.
        val start = (oldest + (minOf(sampleCount, capacity) - n)) % capacity
        val tail = minOf(n, capacity - start)
        System.arraycopy(buffer, start, dest, 0, tail)
        if (tail < n) {
            System.arraycopy(buffer, 0, dest, tail, n - tail)
        }
        n
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
