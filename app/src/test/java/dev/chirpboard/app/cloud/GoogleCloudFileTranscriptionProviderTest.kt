package dev.chirpboard.app.cloud

import dev.chirpboard.app.core.transcription.CloudFileTranscriptionRequest
import dev.chirpboard.app.core.transcription.CloudTranscriptionConfigurationStatus
import dev.chirpboard.app.core.transcription.GOOGLE_CLOUD_CHIRP_3_MAX_DURATION_MS
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import java.io.File
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32C
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GoogleCloudFileTranscriptionProviderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var checkpointStore: CloudDictationCheckpointStore
    private lateinit var provider: GoogleCloudFileTranscriptionProvider
    private lateinit var audioFile: File

    @Before
    fun setup() {
        server = MockWebServer().also { it.start() }
        checkpointStore = mockk(relaxed = true)
        coEvery { checkpointStore.get(any()) } returns null
        provider =
            GoogleCloudFileTranscriptionProvider(
                httpClient = OkHttpClient(),
                authTokenProvider = object : CloudAuthTokenProvider {
                    override suspend fun getIdToken(): String = "firebase-test-token"
                },
                checkpointStore = checkpointStore,
                serviceConfiguration =
                    CloudServiceConfiguration(
                        baseUrl = server.url("/").toString(),
                        allowInsecureLoopback = true,
                        pollIntervalMs = 0L,
                    ),
            )
        audioFile = temporaryFolder.newFile("dictation.wav").apply { writeBytes(ByteArray(128) { it.toByte() }) }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `awaiting upload without a session commits the verified object`() = runTest {
        server.enqueue(jsonResponse(jobEnvelope(STATE_AWAITING_UPLOAD, upload = "null"), 200))
        server.enqueue(jsonResponse(jobEnvelope(STATE_SUBMITTING), 202))
        server.enqueue(jsonResponse(jobEnvelope(STATE_READY, rawTranscript = "ready text"), 200))

        val outcome = provider.transcribeFile(request())

        assertEquals(TranscriptionOutcome.Success("ready text"), outcome)
        val create = server.takeRequest()
        assertEquals("/v1/dictations", create.path)
        assertEquals(RECORDING_ID.toString(), create.getHeader("Idempotency-Key"))
        assertTrue(create.body.readUtf8().contains("\"cleanup\":false"))
        assertEquals("/v1/dictations/$JOB_ID/commit", server.takeRequest().path)
        assertEquals("/v1/dictations/$JOB_ID", server.takeRequest().path)
    }

    @Test
    fun `recording over one hour is rejected before creating a cloud job`() =
        runTest {
            val outcome = provider.transcribeFile(request(GOOGLE_CLOUD_CHIRP_3_MAX_DURATION_MS + 1L))

            assertEquals(
                TranscriptionOutcome.EngineError(
                    reason = "Google Cloud Chirp 3 supports recordings up to one hour; use local transcription for this recording",
                    retryable = false,
                ),
                outcome,
            )
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `temporary token refresh failure is not reported as sign out`() =
        runTest {
            val transientProvider =
                GoogleCloudFileTranscriptionProvider(
                    httpClient = OkHttpClient(),
                    authTokenProvider =
                        object : CloudAuthTokenProvider {
                            override suspend fun getIdToken(): String? {
                                throw CloudAuthTemporarilyUnavailableException("refresh unavailable")
                            }
                        },
                    checkpointStore = checkpointStore,
                    serviceConfiguration =
                        CloudServiceConfiguration(
                            baseUrl = server.url("/").toString(),
                            allowInsecureLoopback = true,
                        ),
                )

            assertEquals(
                CloudTranscriptionConfigurationStatus.TEMPORARILY_UNAVAILABLE,
                transientProvider.configurationStatus(),
            )
        }

    @Test
    fun `daily dictation limit is a non-retryable failure with its own message`() = runTest {
        server.enqueue(
            jsonResponse(
                """{"error":{"code":"daily_dictation_limit","message":"Daily limit reached."}}""",
                429,
            ),
        )

        val outcome = provider.transcribeFile(request())

        // Retrying with backoff cannot succeed before the quota resets, so the worker
        // must not burn its attempts against it.
        assertEquals(
            TranscriptionOutcome.EngineError(
                reason = "Daily cloud transcription limit reached; use local transcription or try again tomorrow",
                retryable = false,
            ),
            outcome,
        )
    }

    @Test
    fun `expired sign-in token is retryable because tokens are fetched per request`() = runTest {
        server.enqueue(
            jsonResponse(
                """{"error":{"code":"unauthenticated","message":"The sign-in token is invalid."}}""",
                401,
            ),
        )

        val outcome = provider.transcribeFile(request())

        assertEquals(
            TranscriptionOutcome.EngineError(
                reason = "Cloud sign-in needs to be refreshed",
                retryable = true,
            ),
            outcome,
        )
    }

    @Test
    fun `commit before the upload finished is retryable`() = runTest {
        server.enqueue(jsonResponse(jobEnvelope(STATE_AWAITING_UPLOAD, upload = "null"), 200))
        server.enqueue(
            jsonResponse(
                """{"error":{"code":"upload_incomplete","message":"The audio upload has not finished."}}""",
                409,
            ),
        )

        val outcome = provider.transcribeFile(request())

        assertEquals(
            TranscriptionOutcome.EngineError(
                reason = "Cloud upload has not finished; it will resume automatically",
                retryable = true,
            ),
            outcome,
        )
    }

    @Test
    fun `non-retryable failure clears the stored checkpoint`() = runTest {
        coEvery { checkpointStore.get(RECORDING_ID) } returns
            matchingCheckpoint(server.url("/saved-upload-session").toString())
        server.enqueue(
            jsonResponse("""{"error":{"code":"forbidden","message":"This account is not allowed."}}""", 403),
        )

        val outcome = provider.transcribeFile(request())

        assertTrue(outcome is TranscriptionOutcome.EngineError && !outcome.retryable)
        // The checkpoint holds a resumable-upload URL; it must not outlive a recording
        // that will never be retried.
        coVerify { checkpointStore.clear(RECORDING_ID) }
    }

    @Test
    fun `commit integrity rejection retries and reuploads in the same run`() = runTest {
        val retryUpload = uploadDescriptor("/retry-upload-session")
        server.enqueue(jsonResponse(jobEnvelope(STATE_AWAITING_UPLOAD, upload = "null"), 200))
        server.enqueue(jsonResponse(jobEnvelope(STATE_FAILED, retryable = true), 422))
        server.enqueue(jsonResponse(jobEnvelope(STATE_AWAITING_UPLOAD, upload = retryUpload), 200))
        server.enqueue(MockResponse().setResponseCode(308))
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(jsonResponse(jobEnvelope(STATE_SUBMITTING), 202))
        server.enqueue(jsonResponse(jobEnvelope(STATE_READY, rawTranscript = "recovered text"), 200))

        val outcome = provider.transcribeFile(request())

        assertEquals(TranscriptionOutcome.Success("recovered text"), outcome)
        assertEquals("/v1/dictations", server.takeRequest().path)
        assertEquals("/v1/dictations/$JOB_ID/commit", server.takeRequest().path)
        assertEquals("/v1/dictations/$JOB_ID/retry", server.takeRequest().path)
        assertEquals("/retry-upload-session", server.takeRequest().path)
        assertEquals("/retry-upload-session", server.takeRequest().path)
        assertEquals("/v1/dictations/$JOB_ID/commit", server.takeRequest().path)
        assertEquals("/v1/dictations/$JOB_ID", server.takeRequest().path)
    }

    @Test
    fun `commit recovery calls retry at most once`() = runTest {
        server.enqueue(jsonResponse(jobEnvelope(STATE_AWAITING_UPLOAD, upload = "null"), 200))
        server.enqueue(jsonResponse(jobEnvelope(STATE_FAILED, retryable = true), 422))
        server.enqueue(jsonResponse(jobEnvelope(STATE_AWAITING_UPLOAD, upload = "null"), 200))
        server.enqueue(jsonResponse(jobEnvelope(STATE_FAILED, retryable = true), 422))

        val outcome = provider.transcribeFile(request())

        assertEquals(TranscriptionOutcome.EngineError(reason = "failed", retryable = true), outcome)
        assertEquals("/v1/dictations", server.takeRequest().path)
        assertEquals("/v1/dictations/$JOB_ID/commit", server.takeRequest().path)
        assertEquals("/v1/dictations/$JOB_ID/retry", server.takeRequest().path)
        assertEquals("/v1/dictations/$JOB_ID/commit", server.takeRequest().path)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `retryable failed job calls retry before polling`() = runTest {
        server.enqueue(jsonResponse(jobEnvelope(STATE_FAILED, retryable = true), 200))
        server.enqueue(jsonResponse(jobEnvelope(STATE_SUBMITTING), 202))
        server.enqueue(jsonResponse(jobEnvelope(STATE_READY, rawTranscript = "recovered text"), 200))

        val outcome = provider.transcribeFile(request())

        assertEquals(TranscriptionOutcome.Success("recovered text"), outcome)
        server.takeRequest()
        assertEquals("/v1/dictations/$JOB_ID/retry", server.takeRequest().path)
        assertEquals("/v1/dictations/$JOB_ID", server.takeRequest().path)
    }

    @Test
    fun `retryable failed job uploads with the fresh retry session before committing`() = runTest {
        val upload = uploadDescriptor("/upload-session")
        server.enqueue(jsonResponse(jobEnvelope(STATE_FAILED, retryable = true), 200))
        server.enqueue(jsonResponse(jobEnvelope(STATE_AWAITING_UPLOAD, upload = upload), 200))
        server.enqueue(MockResponse().setResponseCode(308))
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(jsonResponse(jobEnvelope(STATE_SUBMITTING), 202))
        server.enqueue(jsonResponse(jobEnvelope(STATE_READY, rawTranscript = "recovered text"), 200))

        val outcome = provider.transcribeFile(request())

        assertEquals(TranscriptionOutcome.Success("recovered text"), outcome)
        server.takeRequest()
        assertEquals("/v1/dictations/$JOB_ID/retry", server.takeRequest().path)
        val uploadQuery = server.takeRequest()
        assertEquals("/upload-session", uploadQuery.path)
        assertEquals("bytes */${audioFile.length()}", uploadQuery.getHeader("Content-Range"))
        val uploadRequest = server.takeRequest()
        assertEquals("/upload-session", uploadRequest.path)
        assertEquals(audioFile.length(), uploadRequest.bodySize)
        assertEquals("/v1/dictations/$JOB_ID/commit", server.takeRequest().path)
        assertEquals("/v1/dictations/$JOB_ID", server.takeRequest().path)
    }

    @Test
    fun `matching checkpoint resumes its partial upload before the fresh session`() = runTest {
        val savedSession = server.url("/saved-upload-session").toString()
        coEvery { checkpointStore.get(RECORDING_ID) } returns matchingCheckpoint(savedSession)
        server.enqueue(jsonResponse(jobEnvelope(STATE_AWAITING_UPLOAD, upload = uploadDescriptor("/fresh-upload-session")), 200))
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-31"))
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(jsonResponse(jobEnvelope(STATE_SUBMITTING), 202))
        server.enqueue(jsonResponse(jobEnvelope(STATE_READY, rawTranscript = "resumed text"), 200))

        val outcome = provider.transcribeFile(request())

        assertEquals(TranscriptionOutcome.Success("resumed text"), outcome)
        assertEquals("/v1/dictations", server.takeRequest().path)
        val query = server.takeRequest()
        assertEquals("/saved-upload-session", query.path)
        assertEquals("bytes */${audioFile.length()}", query.getHeader("Content-Range"))
        val resumedUpload = server.takeRequest()
        assertEquals("/saved-upload-session", resumedUpload.path)
        assertEquals("bytes 32-${audioFile.length() - 1}/${audioFile.length()}", resumedUpload.getHeader("Content-Range"))
        assertEquals(audioFile.length() - 32L, resumedUpload.bodySize)
        assertEquals("/v1/dictations/$JOB_ID/commit", server.takeRequest().path)
        assertEquals("/v1/dictations/$JOB_ID", server.takeRequest().path)
    }

    @Test
    fun `saved checksum rejection clears the session and falls back to the fresh session`() = runTest {
        assertSavedSessionFallsBackToFresh(400)
    }

    @Test
    fun `saved precondition rejection clears the session and falls back to the fresh session`() = runTest {
        assertSavedSessionFallsBackToFresh(412)
    }

    private suspend fun assertSavedSessionFallsBackToFresh(rejectionCode: Int) {
        val savedSession = server.url("/saved-upload-session").toString()
        val freshSession = server.url("/fresh-upload-session").toString()
        coEvery { checkpointStore.get(RECORDING_ID) } returns matchingCheckpoint(savedSession)
        server.enqueue(jsonResponse(jobEnvelope(STATE_AWAITING_UPLOAD, upload = uploadDescriptor("/fresh-upload-session")), 200))
        server.enqueue(MockResponse().setResponseCode(rejectionCode))
        server.enqueue(MockResponse().setResponseCode(308))
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(jsonResponse(jobEnvelope(STATE_SUBMITTING), 202))
        server.enqueue(jsonResponse(jobEnvelope(STATE_READY, rawTranscript = "fresh text"), 200))

        val outcome = provider.transcribeFile(request())

        assertEquals(TranscriptionOutcome.Success("fresh text"), outcome)
        assertEquals("/v1/dictations", server.takeRequest().path)
        assertEquals("/saved-upload-session", server.takeRequest().path)
        assertEquals("/fresh-upload-session", server.takeRequest().path)
        assertEquals("/fresh-upload-session", server.takeRequest().path)
        assertEquals("/v1/dictations/$JOB_ID/commit", server.takeRequest().path)
        assertEquals("/v1/dictations/$JOB_ID", server.takeRequest().path)
        coVerifyOrder {
            checkpointStore.put(RECORDING_ID, match { it.uploadSessionUrl == null })
            checkpointStore.put(RECORDING_ID, match { it.uploadSessionUrl == freshSession })
            checkpointStore.put(RECORDING_ID, match { it.uploadSessionUrl == null })
        }
    }

    private fun matchingCheckpoint(sessionUrl: String) =
        CloudDictationCheckpoint(
            jobId = JOB_ID,
            uploadSessionUrl = sessionUrl,
            crc32c = crc32c(audioFile),
            byteLength = audioFile.length(),
        )

    private fun uploadDescriptor(path: String): String =
        """{"sessionUrl":"${server.url(path)}","method":"PUT","chunkSizeBytes":262144}"""

    private fun crc32c(file: File): String {
        val checksum = CRC32C()
        val fileBytes = file.readBytes()
        checksum.update(fileBytes, 0, fileBytes.size)
        val bytes = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(checksum.value.toInt()).array()
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun request(durationMs: Long = 1_000L) =
        CloudFileTranscriptionRequest(
            recordingId = RECORDING_ID,
            executionToken = "worker-token",
            audioPath = audioFile.absolutePath,
            mimeType = "audio/wav",
            durationMs = durationMs,
        )

    private fun jsonResponse(
        body: String,
        code: Int,
    ) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun jobEnvelope(
        state: String,
        upload: String = "null",
        rawTranscript: String? = null,
        retryable: Boolean = false,
    ): String {
        val raw = rawTranscript?.let { "\"$it\"" } ?: "null"
        val error =
            if (state == STATE_FAILED) {
                "{\"code\":\"speech_failed\",\"message\":\"failed\",\"retryable\":$retryable}"
            } else {
                "null"
            }
        return """{"job":{"id":"$JOB_ID","state":"$state","transcript":$raw,"rawTranscript":$raw,"polishedTranscript":null,"error":$error},"upload":$upload}"""
    }

    private companion object {
        val RECORDING_ID: UUID = UUID.fromString("a9026856-c916-4ef0-a630-6f652c83c200")
        const val JOB_ID = "0123456789abcdef0123456789abcdef"
        const val STATE_AWAITING_UPLOAD = "AWAITING_UPLOAD"
        const val STATE_SUBMITTING = "SUBMITTING"
        const val STATE_READY = "READY"
        const val STATE_FAILED = "FAILED"
    }
}
