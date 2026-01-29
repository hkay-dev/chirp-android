package dev.parakeeboard.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class VoiceRecorder {
    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT
    }

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private val samples = mutableListOf<Float>()

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isRecording.get()) return false

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            return false
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord?.release()
            audioRecord = null
            return false
        }

        samples.clear()
        audioRecord?.startRecording()
        isRecording.set(true)
        return true
    }

    suspend fun collectSamples() = withContext(Dispatchers.IO) {
        val record = audioRecord ?: return@withContext
        val buffer = FloatArray(1024)

        while (isActive && isRecording.get()) {
            val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            if (read > 0) {
                synchronized(samples) {
                    for (i in 0 until read) {
                        samples.add(buffer[i])
                    }
                }
            }
        }
    }

    fun stop(): FloatArray {
        isRecording.set(false)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        return synchronized(samples) {
            samples.toFloatArray()
        }
    }

    fun isRecording(): Boolean = isRecording.get()
}
