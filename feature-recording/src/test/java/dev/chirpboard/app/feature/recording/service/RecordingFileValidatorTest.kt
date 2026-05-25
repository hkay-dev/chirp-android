package dev.chirpboard.app.feature.recording.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
