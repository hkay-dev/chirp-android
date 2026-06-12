package dev.chirpboard.app.feature.recording.session.validation

import dev.chirpboard.app.core.audio.WavFileWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class RecordingFileValidatorTest {
    private val validator = RecordingFileValidator()

    @Test
    fun validateForStop_rejectsMissingFile() {
        val result = validator.validateForStop(File("/tmp/does-not-exist-${System.nanoTime()}.m4a"))
        assertEquals(RecordingValidationLevel.INVALID, result.level)
        assertFalse(result.isPlayable)
    }

    @Test
    fun validateForStop_rejectsIncompleteMoov() {
        val file = createFtypOnlyFile()
        val result = validator.validateForStop(file)
        assertFalse(result.isPlayable)
        assertTrue(result.isRecoverableStub)
        file.delete()
    }

    @Test
    fun validateForStop_acceptsPlayableStub() {
        val file = createPlayableStubFile()
        assertTrue(validator.validateForStop(file).isPlayable)
        file.delete()
    }

    @Test
    fun containsMoovAtom_findsMarkerStraddlingBufferBoundary() {
        // Place "moov" so two of its bytes fall in the first 8KB read buffer and two in
        // the next, which the old per-chunk substring scan missed entirely.
        val file = createFtypOnlyFile()
        val padToBoundary = 8192 - file.length().toInt() - 2
        file.appendBytes(ByteArray(padToBoundary))
        file.appendBytes("moov".encodeToByteArray())
        assertTrue(validator.containsMoovAtom(file))
        assertTrue(validator.validateForStop(file).isPlayable)
        file.delete()
    }

    @Test
    fun containsMoovAtom_reportsFalseWhenMarkerAbsent() {
        val file = createFtypOnlyFile()
        file.appendBytes(ByteArray(20_000))
        assertFalse(validator.containsMoovAtom(file))
        file.delete()
    }

    @Test
    fun validateForRecovery_acceptsFtypOnly() {
        val file = createFtypOnlyFile()
        assertTrue(validator.validateForRecovery(file).isRecoverableStub)
        file.delete()
    }

    @Test
    fun checkpointPathFor_appendsCheckpointSuffix() {
        assertEquals(
            "/data/recording.m4a.checkpoint.m4a",
            RecordingFileValidator.checkpointPathFor("/data/recording.m4a"),
        )
    }

    @Test
    fun validateForStop_acceptsFinalizedWav() {
        val file = finalizedWavFile()
        assertTrue(validator.validateForStop(file).isPlayable)
        file.delete()
    }

    @Test
    fun validateForStop_rejectsWavWithCrashedHeader() {
        val file = crashedWavFile()
        assertEquals(RecordingValidationLevel.INVALID, validator.validateForStop(file).level)
        file.delete()
    }

    @Test
    fun validateForRecovery_treatsCrashedWavAsRecoverableStub() {
        val file = crashedWavFile()
        assertTrue(validator.validateForRecovery(file).isRecoverableStub)
        file.delete()
    }

    private fun finalizedWavFile(): File {
        val file = File.createTempFile("finalized", ".wav")
        WavFileWriter(file, sampleRate = 16_000).use { writer ->
            writer.appendPcm16(ByteArray(2048) { 1 }, 2048)
        }
        return file
    }

    private fun crashedWavFile(): File {
        val file = finalizedWavFile()
        RandomAccessFile(file, "rw").use { raf ->
            raf.write(ByteArray(WavFileWriter.WAV_HEADER_BYTES))
        }
        return file
    }

    private fun createFtypOnlyFile(): File {
        val file = File.createTempFile("valid", ".m4a")
        file.writeBytes(
            byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()) +
                ByteArray(512),
        )
        return file
    }

    private fun createPlayableStubFile(): File {
        val file = createFtypOnlyFile()
        file.appendBytes("moov".encodeToByteArray())
        return file
    }
}
