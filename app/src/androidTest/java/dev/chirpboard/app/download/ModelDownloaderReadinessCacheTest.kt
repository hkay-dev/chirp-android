package dev.chirpboard.app.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chirpboard.app.core.modelreadiness.ModelReadinessUnavailableReason
import dev.chirpboard.app.core.modelreadiness.ModelReadinessVerificationSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ModelDownloaderReadinessCacheTest {

    private lateinit var appContext: Context
    private lateinit var primaryDir: File
    private lateinit var legacyDir: File

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        primaryDir = File(appContext.cacheDir, "model_cache_test_primary_${UUID.randomUUID()}").apply { mkdirs() }
        legacyDir = File(appContext.cacheDir, "model_cache_test_legacy_${UUID.randomUUID()}").apply { mkdirs() }
        clearVerificationCaches()
    }

    @After
    fun tearDown() {
        clearVerificationCaches()
        primaryDir.deleteRecursively()
        legacyDir.deleteRecursively()
    }

    @Test
    fun evaluateModelReadiness_usesProcessCacheAfterInitialChecksum() {
        val modelSpec = createTestModelSpec(primaryDir, "encoder.bin", byteArrayOf(1, 2, 3, 4))
        val downloader = newDownloader(modelSpec)

        val first = downloader.evaluateModelReadiness()
        val second = downloader.evaluateModelReadiness()

        assertTrue(first.isReady)
        assertEquals(
            ModelReadinessVerificationSource.CHECKSUM_VERIFICATION,
            first.verificationSource
        )
        assertTrue(second.isReady)
        assertEquals(
            ModelReadinessVerificationSource.PROCESS_CACHE,
            second.verificationSource
        )
    }

    @Test
    fun evaluateModelReadiness_usesPersistedCacheAcrossDownloaderInstances() {
        val modelSpec = createTestModelSpec(primaryDir, "encoder.bin", byteArrayOf(5, 6, 7, 8))

        val firstDownloader = newDownloader(modelSpec)
        val first = firstDownloader.evaluateModelReadiness()
        assertTrue(first.isReady)
        assertEquals(
            ModelReadinessVerificationSource.CHECKSUM_VERIFICATION,
            first.verificationSource
        )

        ModelDownloader.clearProcessVerificationCacheForTest()

        val secondDownloader = newDownloader(modelSpec)
        val second = secondDownloader.evaluateModelReadiness()

        assertTrue(second.isReady)
        assertEquals(
            ModelReadinessVerificationSource.PERSISTED_CACHE,
            second.verificationSource
        )
    }

    @Test
    fun evaluateModelReadiness_invalidatesPersistedCacheWhenFileChanges() {
        val modelFile = File(primaryDir, "encoder.bin")
        modelFile.writeBytes(byteArrayOf(9, 9, 9, 9))
        val modelSpec = ModelDownloader.ModelFile(
            name = "encoder.bin",
            expectedSize = modelFile.length(),
            expectedSha256 = computeSha256(modelFile)
        )

        val firstDownloader = newDownloader(modelSpec)
        val first = firstDownloader.evaluateModelReadiness()
        assertTrue(first.isReady)

        ModelDownloader.clearProcessVerificationCacheForTest()

        modelFile.writeBytes(byteArrayOf(7, 7, 7, 7))
        val bumpedMtime = modelFile.lastModified() + 5_000L
        assertTrue(modelFile.setLastModified(bumpedMtime))

        val secondDownloader = newDownloader(modelSpec)
        val second = secondDownloader.evaluateModelReadiness()

        assertFalse(second.isReady)
        assertEquals(
            ModelReadinessUnavailableReason.INTEGRITY_MISMATCH,
            second.unavailableReason
        )
    }

    private fun createTestModelSpec(
        directory: File,
        name: String,
        bytes: ByteArray
    ): ModelDownloader.ModelFile {
        val file = File(directory, name)
        file.writeBytes(bytes)
        return ModelDownloader.ModelFile(
            name = name,
            expectedSize = file.length(),
            expectedSha256 = computeSha256(file)
        )
    }

    private fun newDownloader(modelSpec: ModelDownloader.ModelFile): ModelDownloader {
        return ModelDownloader(
            context = appContext,
            modelFiles = listOf(modelSpec),
            modelDirProvider = { primaryDir },
            legacyModelDirProvider = { legacyDir }
        )
    }

    private fun clearVerificationCaches() {
        ModelDownloader.clearProcessVerificationCacheForTest()
        appContext.getSharedPreferences(ModelDownloader.VERIFICATION_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
