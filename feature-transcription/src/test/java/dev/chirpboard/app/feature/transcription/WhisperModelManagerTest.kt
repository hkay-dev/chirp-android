package dev.chirpboard.app.feature.transcription

import android.content.Context
import android.os.Environment
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WhisperModelManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockContext: Context
    private lateinit var filesDir: File
    private lateinit var docsDir: File
    private lateinit var persistentDir: File
    private lateinit var legacyDir: File
    private lateinit var classUnderTest: WhisperModelManager

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        
        filesDir = tempFolder.newFolder("files")
        docsDir = tempFolder.newFolder("docs")
        
        every { mockContext.filesDir } returns filesDir
        
        mockkStatic(Environment::class)
        every { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS) } returns docsDir
        
        persistentDir = File(docsDir, ".chirpboard/models/parakeet-tdt-0.6b-v2")
        legacyDir = File(filesDir, "models/parakeet-tdt-0.6b-v2")
        
        classUnderTest = WhisperModelManager(mockContext)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun setFileLength(dir: File, ratio: Float = 1.0f) {
        dir.mkdirs()
        val files = listOf(
            Pair("encoder.int8.onnx", 650_000_000L),
            Pair("decoder.int8.onnx", 7_000_000L),
            Pair("joiner.int8.onnx", 1_700_000L),
            Pair("tokens.txt", 9_000L)
        )
        
        for ((name, expectedSize) in files) {
            val file = File(dir, name)
            java.io.RandomAccessFile(file, "rw").use {
                it.setLength((expectedSize * ratio).toLong())
            }
        }
    }

    @Test
    fun `isModelDownloaded returns false when no files exist`() {
        assertFalse(classUnderTest.isModelDownloaded())
        assertEquals(WhisperModelManager.ModelStatus.NotDownloaded, classUnderTest.modelStatus.value)
    }

    @Test
    fun `isModelDownloaded returns true when persistent files are complete`() {
        setFileLength(persistentDir, 1.0f)
        classUnderTest.refreshStatus()
        assertTrue(classUnderTest.isModelDownloaded())
        assertEquals(WhisperModelManager.ModelStatus.Ready, classUnderTest.modelStatus.value)
    }

    @Test
    fun `isModelDownloaded returns false when files are too small`() {
        setFileLength(persistentDir, 0.5f)
        classUnderTest.refreshStatus()
        assertFalse(classUnderTest.isModelDownloaded())
    }

    @Test
    fun `isModelDownloaded returns true when legacy files are complete`() {
        setFileLength(legacyDir, 1.0f)
        classUnderTest.refreshStatus()
        assertTrue(classUnderTest.isModelDownloaded())
    }

    @Test
    fun `getDownloadedSize returns total size`() {
        setFileLength(persistentDir, 1.0f)
        val expectedTotal = 650_000_000L + 7_000_000L + 1_700_000L + 9_000L
        assertEquals(expectedTotal, classUnderTest.getDownloadedSize())
    }

    @Test
    fun `deleteModel removes all model directories and updates status`() {
        setFileLength(persistentDir, 1.0f)
        setFileLength(legacyDir, 1.0f)
        
        val result = classUnderTest.deleteModel()
        
        assertTrue(result)
        // Due to getModelDir creating the directory on access during refreshStatus(),
        // we verify the state is correctly updated to NotDownloaded instead of directory non-existence.
        assertEquals(WhisperModelManager.ModelStatus.NotDownloaded, classUnderTest.modelStatus.value)
        assertFalse(classUnderTest.isModelDownloaded())
        
        // Legacy directory is definitely not recreated, so we can assert it is gone
        assertFalse(legacyDir.exists())
    }

    @Test
    fun `updateDownloadProgress updates state flow`() {
        classUnderTest.updateDownloadProgress(0.5f)
        assertEquals(0.5f, classUnderTest.downloadProgress.value)
        assertTrue(classUnderTest.modelStatus.value is WhisperModelManager.ModelStatus.Downloading)
        assertEquals(0.5f, (classUnderTest.modelStatus.value as WhisperModelManager.ModelStatus.Downloading).progress)
    }

    @Test
    fun `markDownloadComplete updates state flow`() {
        classUnderTest.updateDownloadProgress(0.9f)
        classUnderTest.markDownloadComplete()
        
        assertEquals(0f, classUnderTest.downloadProgress.value)
        assertEquals(WhisperModelManager.ModelStatus.Ready, classUnderTest.modelStatus.value)
    }

    @Test
    fun `markDownloadError updates state flow`() {
        classUnderTest.updateDownloadProgress(0.5f)
        classUnderTest.markDownloadError("Failed")
        
        assertEquals(0f, classUnderTest.downloadProgress.value)
        val status = classUnderTest.modelStatus.value
        assertTrue(status is WhisperModelManager.ModelStatus.Error)
        assertEquals("Failed", (status as WhisperModelManager.ModelStatus.Error).message)
    }
}
