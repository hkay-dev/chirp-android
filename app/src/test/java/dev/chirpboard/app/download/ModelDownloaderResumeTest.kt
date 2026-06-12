package dev.chirpboard.app.download

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Exercises the HTTP Range resume path (ERR-2) against a real local server: an interrupted
 * transfer keeps its partial temp file + ETag sidecar, and the next attempt resumes with
 * `Range`/`If-Range` (or restarts cleanly when the entity changed), always finishing with
 * the unchanged SHA-256 verification before promotion.
 */
class ModelDownloaderResumeTest {
    private companion object {
        const val CONTENT = "hello world\n" // 12 bytes
        const val CONTENT_SHA256 = "a948904f2f0f479b8f8197694b30184b0d2ed1c1cd2a1ec0fb85d299a192a447"
        const val ETAG = "\"entity-v1\""
        const val FILE_NAME = "test_model.onnx"
    }

    private lateinit var context: Context
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var server: MockWebServer
    private lateinit var testDir: File
    private lateinit var modelsDir: File

    private val modelFile =
        ModelDownloader.ModelFile(
            name = FILE_NAME,
            expectedSize = CONTENT.length.toLong(),
            expectedSha256 = CONTENT_SHA256,
        )

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        sharedPrefs = mockk(relaxed = true)
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { context.getSharedPreferences(ModelDownloader.VERIFICATION_PREFS_NAME, Context.MODE_PRIVATE) } returns sharedPrefs

        testDir = File(System.getProperty("java.io.tmpdir"), "resume_test_${System.nanoTime()}")
        modelsDir = File(testDir, "models").apply { mkdirs() }

        server = MockWebServer()
        server.start()
        ModelDownloader.clearProcessVerificationCacheForTest()
    }

    @After
    fun tearDown() {
        server.shutdown()
        testDir.deleteRecursively()
        unmockkStatic(Log::class)
    }

    private fun newDownloader(): ModelDownloader =
        ModelDownloader(
            context = context,
            modelFiles = listOf(modelFile),
            modelDirProvider = { modelsDir },
            legacyModelDirProvider = { modelsDir },
            baseUrl = server.url("/models").toString(),
            availableBytesProvider = { Long.MAX_VALUE },
        )

    /** Serves [CONTENT], honouring Range+If-Range; optionally truncates the first response. */
    private class ResumeDispatcher(
        private val truncateFirstResponse: Boolean,
        private val honourRange: Boolean = true,
    ) : Dispatcher() {
        val requests = mutableListOf<RecordedRequest>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            val content = CONTENT.toByteArray()
            val range = request.getHeader("Range")
            val ifRange = request.getHeader("If-Range")
            if (honourRange && range != null && ifRange == ETAG) {
                val offset = range.removePrefix("bytes=").removeSuffix("-").toInt()
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("ETag", ETAG)
                    .setBody(Buffer().write(content, offset, content.size - offset))
            }
            val response =
                MockResponse()
                    .setHeader("ETag", ETAG)
                    .setBody(Buffer().write(content))
            if (truncateFirstResponse && requests.size == 1) {
                response.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            }
            return response
        }
    }

    @Test
    fun `interrupted download keeps partial temp and etag then resumes via Range`() =
        runBlocking {
            val dispatcher = ResumeDispatcher(truncateFirstResponse = true)
            server.dispatcher = dispatcher
            val downloader = newDownloader()

            val firstAttempt = downloader.downloadModelFlow().toList()
            val firstError = firstAttempt.last()
            assertTrue("expected error, got $firstError", firstError is ModelDownloader.DownloadState.Error)
            assertTrue((firstError as ModelDownloader.DownloadState.Error).retryable)

            val tempFile = File(modelsDir, "$FILE_NAME.download")
            val etagFile = File(modelsDir, "$FILE_NAME.download.etag")
            assertTrue("partial temp must be kept for resume", tempFile.exists())
            val partialLength = tempFile.length()
            assertTrue(partialLength in 1 until modelFile.expectedSize)
            assertEquals(ETAG, etagFile.readText())

            val secondAttempt = downloader.downloadModelFlow().toList()
            assertEquals(ModelDownloader.DownloadState.Complete, secondAttempt.last())

            val resumeRequest = dispatcher.requests[1]
            assertEquals("bytes=$partialLength-", resumeRequest.getHeader("Range"))
            assertEquals(ETAG, resumeRequest.getHeader("If-Range"))

            assertEquals(CONTENT, File(modelsDir, FILE_NAME).readText())
            assertFalse(tempFile.exists())
            assertFalse(etagFile.exists())
        }

    @Test
    fun `changed entity restarts from byte zero instead of splicing`() =
        runBlocking {
            // A stale partial against a DIFFERENT etag: the server answers 200 (full body),
            // and the downloader must discard the partial instead of appending.
            val tempFile = File(modelsDir, "$FILE_NAME.download")
            tempFile.writeText("STALE!")
            File(modelsDir, "$FILE_NAME.download.etag").writeText("\"old-entity\"")

            server.dispatcher = ResumeDispatcher(truncateFirstResponse = false, honourRange = false)
            val downloader = newDownloader()

            val states = downloader.downloadModelFlow().toList()
            assertEquals(ModelDownloader.DownloadState.Complete, states.last())
            assertEquals(CONTENT, File(modelsDir, FILE_NAME).readText())
        }

    @Test
    fun `fully downloaded temp is promoted without any network`() =
        runBlocking {
            // Process died between the final write and the promote: no request must be made.
            File(modelsDir, "$FILE_NAME.download").writeText(CONTENT)

            val dispatcher = ResumeDispatcher(truncateFirstResponse = false)
            server.dispatcher = dispatcher
            val downloader = newDownloader()

            val states = downloader.downloadModelFlow().toList()
            assertEquals(ModelDownloader.DownloadState.Complete, states.last())
            assertEquals(CONTENT, File(modelsDir, FILE_NAME).readText())
            assertTrue("no network request expected", dispatcher.requests.isEmpty())
        }

    @Test
    fun `resolveResumePlan requires a partial with a strong etag`() {
        val temp = File(testDir, "plan.download")
        val etag = File(testDir, "plan.download.etag")

        assertEquals(ResumePlan.Fresh, resolveResumePlan(temp, etag, expectedSize = 12L))

        temp.writeText("hello ")
        assertEquals("partial without etag must restart", ResumePlan.Fresh, resolveResumePlan(temp, etag, 12L))

        etag.writeText(ETAG)
        assertEquals(ResumePlan.Resume(offset = 6L, etag = ETAG), resolveResumePlan(temp, etag, 12L))

        etag.writeText("W/\"weak\"")
        assertEquals("weak validators cannot be used with If-Range", ResumePlan.Fresh, resolveResumePlan(temp, etag, 12L))

        etag.writeText(ETAG)
        temp.writeText(CONTENT)
        assertEquals(ResumePlan.PromoteCompleted, resolveResumePlan(temp, etag, 12L))

        temp.writeText(CONTENT + "overflow")
        assertEquals("oversized partial must restart", ResumePlan.Fresh, resolveResumePlan(temp, etag, 12L))
    }

    @Test
    fun `readEtagSidecar rejects weak and blank validators`() {
        val etag = File(testDir, "validators.etag")
        assertNull(readEtagSidecar(etag))
        etag.writeText("  ")
        assertNull(readEtagSidecar(etag))
        etag.writeText("W/\"weak\"")
        assertNull(readEtagSidecar(etag))
        etag.writeText(" $ETAG \n")
        assertEquals(ETAG, readEtagSidecar(etag))
    }

    @Test
    fun `classifyDownloadError maps connectivity and disk-full to friendly retry semantics`() {
        val noNetwork = classifyDownloadError(java.net.UnknownHostException("huggingface.co"))
        assertTrue(noNetwork.retryable)
        assertEquals("No internet connection. Check your network and try again.", noNetwork.message)

        val timeout = classifyDownloadError(java.net.SocketTimeoutException("timeout"))
        assertTrue(timeout.retryable)

        val diskFull = classifyDownloadError(java.io.IOException("write failed: ENOSPC (No space left on device)"))
        assertFalse(diskFull.retryable)
        assertEquals("Not enough storage to finish the download. Free up space and try again.", diskFull.message)

        val genericIo = classifyDownloadError(java.io.IOException("unexpected end of stream"))
        assertTrue(genericIo.retryable)

        val unknown = classifyDownloadError(IllegalStateException("boom"))
        assertFalse(unknown.retryable)
    }
}
