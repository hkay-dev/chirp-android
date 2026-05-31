package dev.chirpboard.app.download

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
            ModelDownloader.ModelFile(
                name = "test_model.onnx",
                expectedSize = 12L,
                expectedSha256 = "a948904f2f0f479b8f8197694b30184b0d2ed1c1cd2a1ec0fb85d299a192a447", // "hello world\n"
            ),
        )

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        sharedPrefs = mockk(relaxed = true)
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0

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

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
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
    fun `isModelDownloaded returns false when valid files are split across directories`() {
        val splitModelFiles =
            listOf(
                ModelDownloader.ModelFile(
                    name = "first.onnx",
                    expectedSize = 12L,
                    expectedSha256 = "a948904f2f0f479b8f8197694b30184b0d2ed1c1cd2a1ec0fb85d299a192a447",
                ),
                ModelDownloader.ModelFile(
                    name = "second.onnx",
                    expectedSize = 8L,
                    expectedSha256 = "71573b922a87abc3fd1a957f2cfa09d9e16998567dd878a85e12166112751806",
                ),
            )
        val persistentDir = File(testDir, "persistent").apply { mkdirs() }
        val legacyDir = File(testDir, "legacy").apply { mkdirs() }
        File(persistentDir, "first.onnx").writeText("hello world\n")
        File(legacyDir, "second.onnx").writeText("goodbye\n")
        val splitDownloader =
            ModelDownloader(
                context = context,
                modelFiles = splitModelFiles,
                modelDirProvider = { persistentDir },
                legacyModelDirProvider = { legacyDir },
            )
        ModelDownloader.clearProcessVerificationCacheForTest()

        assertFalse(splitDownloader.isModelDownloaded())
    }

    @Test
    fun `isModelDownloaded returns false when file has wrong size`() {
        val file = File(testModelsDir, "test_model.onnx")
        file.writeText("hello") // 5 bytes, expects 12

        assertFalse(downloader.isModelDownloaded())
    }

    @Test
    fun `computeSha256 returns correct hash`() {
        val file = File(testDir, "hash_test.txt")
        file.writeText("hello world\n")

        val hash = computeSha256(file)
        assertEquals("a948904f2f0f479b8f8197694b30184b0d2ed1c1cd2a1ec0fb85d299a192a447", hash)
    }

    @Test
    fun `validateFileIntegrity returns true when match`() {
        val file = File(testDir, "hash_test2.txt")
        file.writeText("hello world\n")

        val valid = validateFileIntegrity(file, 12L, "a948904f2f0f479b8f8197694b30184b0d2ed1c1cd2a1ec0fb85d299a192a447")
        assertTrue(valid)
    }
}
