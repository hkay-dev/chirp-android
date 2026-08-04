package dev.chirpboard.app.cloud

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class VertexTextGenerationClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `generation key survives client recreation and changes with the request`() = runTest {
        repeat(4) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"text":"generated","model":"gemini-default"}"""),
            )
        }

        assertEquals("generated", client().generate("raw", "clean it", null, RECORDING_ID).getOrThrow())
        assertEquals("generated", client().generate("raw", "clean it", null, RECORDING_ID).getOrThrow())
        assertEquals("generated", client().generate("raw", "rewrite it", null, RECORDING_ID).getOrThrow())
        assertEquals("generated", client().generate("raw", "clean it", null, OTHER_RECORDING_ID).getOrThrow())

        val firstRequest = server.takeRequest()
        val firstKey = firstRequest.getHeader("Idempotency-Key")
        val secondKey = server.takeRequest().getHeader("Idempotency-Key")
        val changedKey = server.takeRequest().getHeader("Idempotency-Key")
        val otherRecordingKey = server.takeRequest().getHeader("Idempotency-Key")
        assertEquals(RECORDING_ID, firstRequest.getHeader("Recording-Id"))
        assertEquals(firstKey, secondKey)
        assertNotEquals(firstKey, changedKey)
        assertNotEquals(firstKey, otherRecordingKey)
        assertEquals(64, firstKey?.length)
        assertTrue(firstKey?.matches(Regex("[0-9a-f]{64}")) == true)
    }

    @Test
    fun `generation in progress is returned as a call failure`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"state":"GENERATING"}"""),
        )

        val result = client().generate("raw", "clean it", null, RECORDING_ID)

        assertTrue(result.isFailure)
        assertEquals("Vertex generation is still running", result.exceptionOrNull()?.message)
        assertTrue(server.takeRequest().getHeader("Idempotency-Key")?.isNotBlank() == true)
    }

    @Test
    fun `missing recording ID fails before a request is sent`() = runTest {
        val result = client().generate("raw", "clean it", null, null)

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is IOException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `temporary HTTP failures stay retryable IO failures`() =
        runTest {
            val retryableStatuses = listOf(202, 408, 429, 500, 503)
            retryableStatuses.forEach { status ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(status)
                        .setHeader("Retry-After", "1")
                        .setBody("{}"),
                )
            }

            retryableStatuses.forEach { status ->
                val failure = client().generate("raw-$status", "clean it", null, RECORDING_ID).exceptionOrNull()

                assertTrue("HTTP $status should be retryable", failure is IOException)
            }
        }

    @Test
    fun `permanent HTTP failures are not exposed as retryable IO failures`() =
        runTest {
            val permanentStatuses = listOf(400, 401, 403, 404, 422)
            permanentStatuses.forEach { status ->
                server.enqueue(MockResponse().setResponseCode(status).setBody("{}"))
            }

            permanentStatuses.forEach { status ->
                val failure = client().generate("raw-$status", "clean it", null, RECORDING_ID).exceptionOrNull()

                assertTrue("HTTP $status should fail", failure != null)
                assertFalse("HTTP $status should be terminal", failure is IOException)
            }
        }

    @Test
    fun `temporary Firebase token refresh failure stays retryable`() =
        runTest {
            val outage = CloudAuthTemporarilyUnavailableException("refresh unavailable")
            val authTokenProvider =
                object : CloudAuthTokenProvider {
                    override suspend fun getIdToken(): String? = throw outage
                }
            val client = client(authTokenProvider)

            val availabilityFailure = runCatching { client.isConfigured() }.exceptionOrNull()
            val generationFailure = client.generate("raw", "clean it", null, RECORDING_ID).exceptionOrNull()

            assertTrue(availabilityFailure === outage)
            assertTrue(generationFailure === outage)
        }

    private fun client(
        authTokenProvider: CloudAuthTokenProvider =
            object : CloudAuthTokenProvider {
                override suspend fun getIdToken(): String = "firebase-test-token"
            },
    ) =
        VertexTextGenerationClient(
            httpClient = OkHttpClient(),
            authTokenProvider = authTokenProvider,
            serviceConfiguration =
                CloudServiceConfiguration(
                    baseUrl = server.url("/").toString(),
                    allowInsecureLoopback = true,
                ),
        )

    private companion object {
        const val RECORDING_ID = "a9026856-c916-4ef0-a630-6f652c83c200"
        const val OTHER_RECORDING_ID = "aac829ee-9b44-43a8-b2b0-aae103c3f6f6"
    }
}
