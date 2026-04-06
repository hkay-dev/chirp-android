package dev.chirpboard.app.download

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class ModelDownloaderTest {
    private lateinit var context: Context
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var downloader: ModelDownloader
    private lateinit var testDir: File
    private lateinit var testModelsDir: File

    private val testModelFiles =
        listOf(
            dev.chirpboard.app.download.model.ModelFile(
                name = "test_model.onnx",
                expectedSize = 12L,
                expectedSha256 = "6b86b273ff34fce19d6b804eff5a3f5747ada4eaa22f1d49c01e52ddb7875b4b", // "hello world\n"
            ),
        )

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        sharedPrefs = mockk(relaxed = true)

        every { context.getSharedPreferences(ModelDownloader.VERIFICATION_PREFS_NAME, Context.MODE_PRIVATE) } returns sharedPrefs

        testDir = File(System.getProperty("java.io.tmpdir"), "test_models_root_${System.currentTimeMillis()}")
        testDir.mkdirs()
        testModelsDir = File(testDir, "models/parakeet-test")
        testModelsDir.mkdirs()

        downloader =
            ModelDownloader(
                context = context,
                modelFiles = testModelFiles,
                modelDirProvider = { testModelsDir },
                legacyModelDirProvider = { testModelsDir },
            )
        ModelDownloader.clearProcessVerificationCacheForTest()
    }

    @Test
    fun `isModelDownloaded returns false when files missing`() {
        assertFalse(downloader.isModelDownloaded())
    }

    @Test
    fun `isModelDownloaded returns true when files present and valid`() {
        // Create valid file
        val file = File(testModelsDir, "test_model.onnx")
        file.writeText("hello world\n") // 12 bytes

        assertTrue(downloader.isModelDownloaded())
    }

    @Test
    fun `isModelDownloaded returns false when file has wrong size`() {
        val file = File(testModelsDir, "test_model.onnx")
        file.writeText("hello") // 5 bytes, expects 12

        assertFalse(downloader.isModelDownloaded())
    }

    @Test
    fun `hasSufficientStorage logic`() {
        // 60MB available, needs 5MB + 50MB buffer = 55MB. Should be true.
        assertTrue(hasSufficientStorage(60L * 1024 * 1024, 5L * 1024 * 1024))

        // 50MB available, needs 5MB + 50MB buffer = 55MB. Should be false.
        assertFalse(hasSufficientStorage(50L * 1024 * 1024, 5L * 1024 * 1024))
    }

    @Test
    fun `computeSha256 returns correct hash`() {
        val file = File(testDir, "hash_test.txt")
        file.writeText("hello world\n")

        val hash = computeSha256(file)
        assertEquals("6b86b273ff34fce19d6b804eff5a3f5747ada4eaa22f1d49c01e52ddb7875b4b", hash)
    }

    @Test
    fun `validateFileIntegrity returns null when match`() {
        val file = File(testDir, "hash_test2.txt")
        file.writeText("hello world\n")

        val error = validateFileIntegrity(file, 12L, "6b86b273ff34fce19d6b804eff5a3f5747ada4eaa22f1d49c01e52ddb7875b4b")
        assertNull(error)
    }

    @Test
    fun `validateFileIntegrity returns error message when mismatch`() {
        val file = File(testDir, "hash_test3.txt")
        file.writeText("wrong content")

        val error = validateFileIntegrity(file, 12L, "6b86b273ff34fce19d6b804eff5a3f5747ada4eaa22f1d49c01e52ddb7875b4b")
        assertNotNull(error)
        assertTrue(error!!.contains("Hash mismatch"))
    }
}
