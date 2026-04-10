import os
import re

def write_waveform_buffer():
    path = "core/src/main/java/dev/chirpboard/app/core/recording/WaveformBuffer.kt"
    content = """package dev.chirpboard.app.core.recording

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
"""
    with open(path, "w") as f:
        f.write(content)

def patch_recording_state_manager():
    path = "core/src/main/java/dev/chirpboard/app/core/recording/RecordingStateManager.kt"
    with open(path, "r") as f:
        text = f.read()

    text = text.replace(
        "private val _amplitudeHistory = MutableStateFlow<List<Float>>(emptyList())\n    val amplitudeHistoryFlow: StateFlow<List<Float>> = _amplitudeHistory.asStateFlow()",
        "val waveformBuffer = WaveformBuffer(AMPLITUDE_HISTORY_SIZE)"
    )

    old_update = """        _amplitudeHistory.update { history ->
            (history + normalized).takeLast(AMPLITUDE_HISTORY_SIZE)
        }"""
    new_update = "        waveformBuffer.add(normalized)"
    text = text.replace(old_update, new_update)

    old_clear = """        _amplitudeHistory.value = emptyList()"""
    new_clear = "        waveformBuffer.clear()"
    text = text.replace(old_clear, new_clear)

    with open(path, "w") as f:
        f.write(text)

def patch_record_view_model():
    path = "feature-recording/src/main/java/dev/chirpboard/app/feature/recording/ui/RecordViewModel.kt"
    with open(path, "r") as f:
        text = f.read()
    
    text = text.replace(
        "val amplitudeHistory: StateFlow<List<Float>> = recordingStateManager.amplitudeHistoryFlow",
        "val waveformBuffer = recordingStateManager.waveformBuffer"
    )

    with open(path, "w") as f:
        f.write(text)

def patch_audio_waveform():
    path = "feature-recording/src/main/java/dev/chirpboard/app/feature/recording/ui/components/AudioWaveform.kt"
    with open(path, "r") as f:
        text = f.read()

    text = text.replace(
        "amplitudes: List<Float>",
        "waveformBuffer: dev.chirpboard.app.core.recording.WaveformBuffer"
    )

    # In activeAlpha
    text = text.replace("amplitudes.isNotEmpty()", "waveformBuffer.count > 0")

    # In newestAmp
    text = text.replace("amplitudes.lastOrNull()", "waveformBuffer.lastOrNull()")

    # In bar loop
    text = text.replace("val n = amplitudes.size", "val n = waveformBuffer.count")
    text = text.replace("amplitudes[i]", "waveformBuffer.get(i)")

    with open(path, "w") as f:
        f.write(text)

def patch_record_screen():
    path = "feature-recording/src/main/java/dev/chirpboard/app/feature/recording/ui/RecordScreen.kt"
    with open(path, "r") as f:
        text = f.read()
    
    text = text.replace("val amplitudeHistory by viewModel.amplitudeHistory.collectAsStateWithLifecycle()",
                        "val waveformVersion by viewModel.waveformBuffer.dataVersion.collectAsStateWithLifecycle()")
    
    text = text.replace("amplitudes = amplitudeHistory", "waveformBuffer = viewModel.waveformBuffer")
    
    with open(path, "w") as f:
        f.write(text)

if __name__ == '__main__':
    write_waveform_buffer()
    patch_recording_state_manager()
    patch_record_view_model()
    patch_audio_waveform()
    patch_record_screen()
