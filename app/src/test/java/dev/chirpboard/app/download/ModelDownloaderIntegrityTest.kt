package dev.chirpboard.app.download

import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloaderIntegrityTest {

    @Test
    fun `hasSufficientStorage returns false when available space is below requirement`() {
        assertFalse(hasSufficientStorage(availableBytes = 100, requiredBytes = 101))
        assertTrue(hasSufficientStorage(availableBytes = 101, requiredBytes = 101))
    }

    @Test
    fun `validateFileIntegrity returns false for checksum mismatch`() {
        val tempFile = Files.createTempFile("integrity", ".txt").toFile()
        tempFile.writeText("hello")

        val isValid = validateFileIntegrity(
            file = tempFile,
            expectedSize = tempFile.length(),
            expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000"
        )

        assertFalse(isValid)
        tempFile.delete()
    }

    @Test
    fun `writeInputStreamToTempFile deletes temp on interruption`() {
        val tempDir = Files.createTempDirectory("modeldl")
        val tempFile = tempDir.resolve("file.download").toFile()

        val input = object : InputStream() {
            private var count = 0
            override fun read(): Int {
                if (count >= 20) throw IOException("interrupted")
                count += 1
                return 'a'.code
            }
        }

        var threw = false
        try {
            runBlocking {
                writeInputStreamToTempFile(input, tempFile) { }
            }
        } catch (e: IOException) {
            threw = true
        }

        assertTrue(threw)
        assertFalse(tempFile.exists())
        tempDir.deleteIfExists()
    }

    @Test
    fun `promoteTempFileAtomically moves temp contents to destination`() {
        val tempDir = Files.createTempDirectory("modeldl")
        val destinationFile = tempDir.resolve("final.bin").toFile()
        destinationFile.writeText("old")

        val tempFile = tempDir.resolve("final.bin.download").toFile()
        tempFile.writeText("new")

        val promoted = promoteTempFileAtomically(tempFile, destinationFile)

        assertTrue(promoted)
        assertFalse(tempFile.exists())
        assertEquals("new", destinationFile.readText())

        destinationFile.delete()
        tempDir.deleteIfExists()
    }
}
