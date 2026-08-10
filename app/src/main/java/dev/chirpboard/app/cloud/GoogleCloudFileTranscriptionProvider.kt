package dev.chirpboard.app.cloud

import androidx.annotation.Keep
import com.google.gson.Gson
import dev.chirpboard.app.core.transcription.CloudFileTranscriptionProvider
import dev.chirpboard.app.core.transcription.CloudFileTranscriptionRequest
import dev.chirpboard.app.core.transcription.CloudTranscriptionConfigurationStatus
import dev.chirpboard.app.core.transcription.GOOGLE_CLOUD_CHIRP_3_MAX_AUDIO_BYTES
import dev.chirpboard.app.core.transcription.GOOGLE_CLOUD_CHIRP_3_MAX_DURATION_MS
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import dev.chirpboard.app.di.CloudTranscriptionHttpClient
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32C
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.BufferedSink

@Singleton
class GoogleCloudFileTranscriptionProvider
    @Inject
    internal constructor(
        @CloudTranscriptionHttpClient private val httpClient: OkHttpClient,
        private val authTokenProvider: CloudAuthTokenProvider,
        private val checkpointStore: CloudDictationCheckpointStore,
        private val serviceConfiguration: CloudServiceConfiguration,
    ) : CloudFileTranscriptionProvider {
        private val gson = Gson()
        private val baseUrl = serviceConfiguration.baseUrl.trim().trimEnd('/')

        override suspend fun configurationStatus(): CloudTranscriptionConfigurationStatus {
            val parsedBase = baseUrl.toHttpUrlOrNull()
            if (parsedBase == null ||
                (!parsedBase.isHttps &&
                    !(serviceConfiguration.allowInsecureLoopback && parsedBase.host in setOf("127.0.0.1", "localhost")))
            ) {
                return CloudTranscriptionConfigurationStatus.ENDPOINT_MISSING
            }
            return try {
                if (authTokenProvider.getIdToken().isNullOrBlank()) {
                    CloudTranscriptionConfigurationStatus.AUTHENTICATION_MISSING
                } else {
                    CloudTranscriptionConfigurationStatus.READY
                }
            } catch (_: CloudAuthTemporarilyUnavailableException) {
                CloudTranscriptionConfigurationStatus.TEMPORARILY_UNAVAILABLE
            }
        }

        override suspend fun transcribeFile(request: CloudFileTranscriptionRequest): TranscriptionOutcome =
            withContext(Dispatchers.IO) {
                try {
                    transcribe(request)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: CloudRequestException) {
                    TranscriptionOutcome.EngineError(error.publicMessage, error.retryable)
                } catch (_: IOException) {
                    TranscriptionOutcome.EngineError("Cloud transcription connection failed", retryable = true)
                } catch (_: Exception) {
                    TranscriptionOutcome.EngineError("Cloud transcription failed", retryable = false)
                }
            }

        private suspend fun transcribe(request: CloudFileTranscriptionRequest): TranscriptionOutcome {
            endpoint("/v1/dictations")
            val audioFile = File(request.audioPath)
            if (!audioFile.isFile || audioFile.length() <= 0L) {
                throw CloudRequestException("Cloud transcription audio is missing", retryable = false)
            }
            if (request.durationMs > GOOGLE_CLOUD_CHIRP_3_MAX_DURATION_MS) {
                throw CloudRequestException(
                    "Google Cloud Chirp 3 supports recordings up to one hour; use local transcription for this recording",
                    retryable = false,
                )
            }
            if (audioFile.length() > GOOGLE_CLOUD_CHIRP_3_MAX_AUDIO_BYTES) {
                throw CloudRequestException(
                    "This recording is too large for Google Cloud Chirp 3; use local transcription for this recording",
                    retryable = false,
                )
            }
            val contentType = normalizedContentType(request.mimeType)
            val byteLength = audioFile.length()
            val crc32c = calculateCrc32c(audioFile)
            val storedCheckpoint = checkpointStore.get(request.recordingId)
            var envelope = createDictation(request, contentType, byteLength, crc32c)
            var checkpoint =
                storedCheckpoint?.takeIf { saved ->
                    saved.jobId == envelope.job.id &&
                        saved.byteLength == byteLength &&
                        saved.crc32c == crc32c
                }
            if (storedCheckpoint != null && checkpoint == null) {
                checkpointStore.clear(request.recordingId)
            }
            var recoveryUsed = false

            while (true) {
                val job = envelope.job
                if (job.state == STATE_FAILED && job.error?.retryable == true && !recoveryUsed) {
                    recoveryUsed = true
                    envelope = retryDictation(job.id)
                    checkpoint = checkpoint?.takeIf { it.jobId == envelope.job.id }
                    continue
                }
                terminalOutcome(job)?.let { outcome ->
                    checkpointStore.clear(request.recordingId)
                    return outcome
                }

                if (job.state == STATE_AWAITING_UPLOAD) {
                    uploadAwaitingAudio(
                        recordingId = request.recordingId,
                        job = job,
                        audioFile = audioFile,
                        contentType = contentType,
                        crc32c = crc32c,
                        byteLength = byteLength,
                        savedCheckpoint = checkpoint,
                        freshUpload = envelope.upload,
                    )
                    checkpoint = null
                    val committed = commitDictation(job.id)
                    val committedJob = committed.job
                    if (committedJob.state == STATE_FAILED && committedJob.error?.retryable == true && !recoveryUsed) {
                        recoveryUsed = true
                        envelope = retryDictation(committedJob.id)
                        continue
                    }
                    terminalOutcome(committedJob)?.let { outcome ->
                        checkpointStore.clear(request.recordingId)
                        return outcome
                    }
                    if (committedJob.state !in ACTIVE_STATES) {
                        throw CloudRequestException("Cloud transcription returned an unknown state", retryable = false)
                    }
                    val outcome = pollUntilTerminal(committedJob.id)
                    checkpointStore.clear(request.recordingId)
                    return outcome
                }

                if (job.state !in ACTIVE_STATES) {
                    throw CloudRequestException("Cloud transcription returned an unknown state", retryable = false)
                }
                val outcome = pollUntilTerminal(job.id)
                checkpointStore.clear(request.recordingId)
                return outcome
            }
        }

        private suspend fun uploadAwaitingAudio(
            recordingId: UUID,
            job: DictationJob,
            audioFile: File,
            contentType: String,
            crc32c: String,
            byteLength: Long,
            savedCheckpoint: CloudDictationCheckpoint?,
            freshUpload: DictationUpload?,
        ) {
            val clearedCheckpoint =
                CloudDictationCheckpoint(
                    jobId = job.id,
                    uploadSessionUrl = null,
                    crc32c = crc32c,
                    byteLength = byteLength,
                )
            val freshSessionUrl = freshUpload?.sessionUrl?.takeIf { it.isNotBlank() }
            if (freshUpload == null) {
                checkpointStore.put(recordingId, clearedCheckpoint)
                return
            }

            val savedSessionUrl = savedCheckpoint?.uploadSessionUrl?.takeIf { it.isNotBlank() }
            if (savedSessionUrl != null) {
                try {
                    uploadAudio(
                        audioFile = audioFile,
                        sessionUrl = savedSessionUrl,
                        contentType = contentType,
                        crc32c = crc32c,
                        requestedChunkSize = freshUpload.chunkSizeBytes,
                    )
                    checkpointStore.put(recordingId, clearedCheckpoint)
                    return
                } catch (error: CloudRequestException) {
                    if (!error.requiresFreshUploadSession) throw error
                    checkpointStore.put(recordingId, clearedCheckpoint)
                    if (freshSessionUrl == null || freshSessionUrl == savedSessionUrl) throw error
                }
            }

            if (freshSessionUrl == null) {
                throw CloudRequestException(
                    publicMessage = "Cloud upload session is missing",
                    retryable = true,
                    requiresFreshUploadSession = true,
                )
            }
            val freshCheckpoint = clearedCheckpoint.copy(uploadSessionUrl = freshSessionUrl)
            checkpointStore.put(recordingId, freshCheckpoint)
            try {
                uploadAudio(
                    audioFile = audioFile,
                    sessionUrl = freshSessionUrl,
                    contentType = contentType,
                    crc32c = crc32c,
                    requestedChunkSize = freshUpload.chunkSizeBytes,
                )
            } catch (error: CloudRequestException) {
                if (error.requiresFreshUploadSession) {
                    checkpointStore.put(recordingId, clearedCheckpoint)
                }
                throw error
            }
            checkpointStore.put(recordingId, clearedCheckpoint)
        }

        private suspend fun createDictation(
            request: CloudFileTranscriptionRequest,
            contentType: String,
            byteLength: Long,
            crc32c: String,
        ): CreateDictationResponse {
            val body =
                CreateDictationRequest(
                    contentType = contentType,
                    byteLength = byteLength,
                    durationMs = request.durationMs,
                    crc32c = crc32c,
                    languageCode = request.languageCode,
                    cleanup = false,
                )
            val httpRequest =
                authorizedRequest(endpoint("/v1/dictations"))
                    .header("Idempotency-Key", request.recordingId.toString())
                    .post(gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            return executeJson(httpRequest, CreateDictationResponse::class.java)
        }

        private suspend fun commitDictation(jobId: String): DictationResponse {
            requireValidJobId(jobId)
            val request =
                authorizedRequest(endpoint("/v1/dictations/$jobId/commit"))
                    .post(EMPTY_REQUEST_BODY)
                    .build()
            return httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 422) {
                    response.parseJson(DictationResponse::class.java)
                } else {
                    throw response.toCloudRequestException()
                }
            }
        }

        private suspend fun retryDictation(jobId: String): CreateDictationResponse {
            requireValidJobId(jobId)
            val request =
                authorizedRequest(endpoint("/v1/dictations/$jobId/retry"))
                    .post(EMPTY_REQUEST_BODY)
                    .build()
            return executeJson(request, CreateDictationResponse::class.java)
        }

        private suspend fun pollUntilTerminal(jobId: String): TranscriptionOutcome {
            requireValidJobId(jobId)
            val deadline = System.nanoTime() + POLL_WINDOW_NANOS
            while (System.nanoTime() < deadline) {
                val request = authorizedRequest(endpoint("/v1/dictations/$jobId")).get().build()
                val response = executeJson(request, DictationResponse::class.java)
                terminalOutcome(response.job)?.let { return it }
                if (response.job.state !in ACTIVE_STATES) {
                    throw CloudRequestException("Cloud transcription returned an unknown state", retryable = false)
                }
                delay(serviceConfiguration.pollIntervalMs)
            }
            throw CloudRequestException("Cloud transcription is still processing", retryable = true)
        }

        private fun terminalOutcome(job: DictationJob): TranscriptionOutcome? =
            when (job.state) {
                STATE_READY -> {
                    val text = job.rawTranscript ?: job.transcript.orEmpty()
                    if (text.isBlank()) TranscriptionOutcome.NoSpeech else TranscriptionOutcome.Success(text)
                }
                STATE_FAILED -> {
                    TranscriptionOutcome.EngineError(
                        reason = job.error?.message ?: "Cloud speech recognition failed",
                        retryable = job.error?.retryable == true,
                    )
                }
                else -> null
            }

        private suspend fun authorizedRequest(url: String): Request.Builder {
            val token = authTokenProvider.getIdToken()
                ?.takeIf { it.isNotBlank() }
                ?: throw CloudRequestException("Cloud authentication is not configured", retryable = false)
            return Request.Builder().url(url).header("Authorization", "Bearer $token")
        }

        private fun <T> executeJson(
            request: Request,
            responseType: Class<T>,
        ): T =
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw response.toCloudRequestException()
                }
                response.parseJson(responseType)
            }

        private fun <T> Response.parseJson(responseType: Class<T>): T {
            val body = body?.string()
                ?: throw CloudRequestException("Cloud service returned an empty response", retryable = true)
            return runCatching { gson.fromJson(body, responseType) }
                .getOrElse {
                    throw CloudRequestException("Cloud service returned an invalid response", retryable = true)
                }
        }

        private fun uploadAudio(
            audioFile: File,
            sessionUrl: String,
            contentType: String,
            crc32c: String,
            requestedChunkSize: Long?,
        ) {
            val uploadUrl = sessionUrl.toHttpUrlOrNull()
                ?.takeIf { url ->
                    val googleStorage =
                        url.isHttps &&
                            (url.host == "storage.googleapis.com" || url.host.endsWith(".storage.googleapis.com"))
                    val testLoopback =
                        serviceConfiguration.allowInsecureLoopback &&
                            !url.isHttps &&
                            url.host in setOf("127.0.0.1", "localhost")
                    googleStorage || testLoopback
                }
                ?: throw CloudRequestException(
                    publicMessage = "Cloud upload session is invalid",
                    retryable = true,
                    requiresFreshUploadSession = true,
                )
            val totalBytes = audioFile.length()
            val chunkSize = normalizedChunkSize(requestedChunkSize)
            var offset = queryUploadedBytes(uploadUrl.toString(), totalBytes)
            var stalledResponses = 0

            while (offset < totalBytes) {
                val bytesRemaining = totalBytes - offset
                val contentLength = minOf(chunkSize, bytesRemaining)
                val endInclusive = offset + contentLength - 1L
                val finalChunk = endInclusive == totalBytes - 1L
                val singleChunk = offset == 0L && finalChunk
                val body = FileSegmentRequestBody(audioFile, offset, contentLength, contentType)
                val request =
                    Request
                        .Builder()
                        .url(uploadUrl)
                        .put(body)
                        .header("Content-Length", contentLength.toString())
                        .apply {
                            if (!singleChunk) {
                                header("Content-Range", "bytes $offset-$endInclusive/$totalBytes")
                            }
                            if (finalChunk) {
                                header("X-Goog-Hash", "crc32c=$crc32c")
                            }
                        }.build()

                val nextOffset =
                    try {
                        httpClient.newCall(request).execute().use { response ->
                            when (response.code) {
                                200, 201 -> totalBytes
                                308 -> parseCommittedBytes(response.header("Range"))
                                    ?: queryUploadedBytes(uploadUrl.toString(), totalBytes)
                                else -> throw response.toCloudRequestException(upload = true)
                            }
                        }
                    } catch (_: IOException) {
                        queryUploadedBytes(uploadUrl.toString(), totalBytes)
                    }

                if (nextOffset <= offset) {
                    stalledResponses += 1
                    if (stalledResponses >= MAX_STALLED_UPLOAD_RESPONSES) {
                        throw CloudRequestException("Cloud upload did not make progress", retryable = true)
                    }
                } else {
                    stalledResponses = 0
                    offset = nextOffset.coerceAtMost(totalBytes)
                }
            }
        }

        private fun queryUploadedBytes(
            sessionUrl: String,
            totalBytes: Long,
        ): Long {
            val request =
                Request
                    .Builder()
                    .url(sessionUrl)
                    .put(EMPTY_UPLOAD_BODY)
                    .header("Content-Length", "0")
                    .header("Content-Range", "bytes */$totalBytes")
                    .build()
            return httpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200, 201 -> totalBytes
                    308 -> parseCommittedBytes(response.header("Range")) ?: 0L
                    else -> throw response.toCloudRequestException(upload = true)
                }
            }
        }

        private fun endpoint(path: String): String {
            val parsedBase = baseUrl.toHttpUrlOrNull()
                ?: throw CloudRequestException("Cloud transcription endpoint is not configured", retryable = false)
            if (!parsedBase.isHttps &&
                !(serviceConfiguration.allowInsecureLoopback && parsedBase.host in setOf("127.0.0.1", "localhost"))
            ) {
                throw CloudRequestException("Cloud transcription endpoint must use HTTPS", retryable = false)
            }
            return "$baseUrl$path"
        }

        private fun normalizedContentType(mimeType: String): String =
            when (mimeType.lowercase()) {
                "audio/mp4", "audio/m4a", "audio/x-m4a" -> "audio/mp4"
                "audio/wav", "audio/wave", "audio/x-wav" -> "audio/wav"
                "audio/mpeg", "audio/mp3" -> "audio/mpeg"
                else -> throw CloudRequestException("Cloud transcription does not support this audio format", retryable = false)
            }

        private fun calculateCrc32c(file: File): String {
            val checksum = CRC32C()
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(CHECKSUM_BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    checksum.update(buffer, 0, count)
                }
            }
            val bytes = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(checksum.value.toInt()).array()
            return Base64.getEncoder().encodeToString(bytes)
        }

        private fun normalizedChunkSize(requested: Long?): Long =
            requested
                ?.takeIf { it > 0L }
                ?.coerceIn(MIN_UPLOAD_CHUNK_BYTES, MAX_UPLOAD_CHUNK_BYTES)
                ?: DEFAULT_UPLOAD_CHUNK_BYTES

        private fun parseCommittedBytes(range: String?): Long? {
            val endInclusive = range?.substringAfterLast('-')?.toLongOrNull() ?: return null
            return endInclusive + 1L
        }

        private fun requireValidJobId(jobId: String) {
            if (!JOB_ID.matches(jobId)) {
                throw CloudRequestException("Cloud service returned an invalid job ID", retryable = false)
            }
        }

        private fun Response.toCloudRequestException(
            upload: Boolean = false,
        ): CloudRequestException {
            val requiresFreshUploadSession = upload && code in FRESH_UPLOAD_SESSION_STATUS_CODES
            // Known server error codes override the status-based defaults: a 429 for the
            // daily quota is pointless to retry before UTC midnight, while a 409 for an
            // unfinished upload resolves itself on the next attempt.
            when (if (upload) null else parseServerErrorCode()) {
                "daily_dictation_limit" ->
                    return CloudRequestException(
                        "Daily cloud transcription limit reached; use local transcription or try again tomorrow",
                        retryable = false,
                    )
                "upload_incomplete" ->
                    return CloudRequestException(
                        "Cloud upload has not finished; it will resume automatically",
                        retryable = true,
                    )
                "deletion_in_progress" ->
                    return CloudRequestException(
                        "This recording's cloud transcription is being deleted",
                        retryable = false,
                    )
            }
            // 401 is retryable: tokens are fetched fresh per request, so the next attempt
            // gets a new one. 403 stays permanent (the account itself was refused).
            val retryable =
                requiresFreshUploadSession ||
                    code == 401 ||
                    code == 408 ||
                    code == 429 ||
                    code >= 500
            val publicMessage =
                when (code) {
                    401 -> "Cloud sign-in needs to be refreshed"
                    403 -> "Cloud authentication was rejected"
                    408, 429 -> "Cloud transcription is temporarily busy"
                    in 500..599 -> "Cloud transcription service is temporarily unavailable"
                    else -> if (upload) "Cloud upload failed" else "Cloud transcription request was rejected"
                }
            return CloudRequestException(publicMessage, retryable, requiresFreshUploadSession)
        }

        private fun Response.parseServerErrorCode(): String? =
            runCatching {
                val raw = peekBody(MAX_ERROR_BODY_BYTES).string()
                gson.fromJson(raw, ServerErrorEnvelope::class.java)?.error?.code
            }.getOrNull()

        private companion object {
            val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
            val EMPTY_REQUEST_BODY = ByteArray(0).toRequestBody(JSON_MEDIA_TYPE)
            val EMPTY_UPLOAD_BODY = ByteArray(0).toRequestBody(null)
            val JOB_ID = Regex("[a-fA-F0-9]{32}")
            val ACTIVE_STATES = setOf(STATE_AWAITING_UPLOAD, STATE_SUBMITTING, STATE_TRANSCRIBING, STATE_CLEANING)
            const val STATE_AWAITING_UPLOAD = "AWAITING_UPLOAD"
            const val STATE_SUBMITTING = "SUBMITTING"
            const val STATE_TRANSCRIBING = "TRANSCRIBING"
            const val STATE_CLEANING = "CLEANING"
            const val STATE_READY = "READY"
            const val STATE_FAILED = "FAILED"
            val POLL_WINDOW_NANOS = TimeUnit.MINUTES.toNanos(55)
            const val CHECKSUM_BUFFER_BYTES = 64 * 1024
            const val DEFAULT_UPLOAD_CHUNK_BYTES = 8L * 1024L * 1024L
            const val MIN_UPLOAD_CHUNK_BYTES = 256L * 1024L
            const val MAX_UPLOAD_CHUNK_BYTES = 16L * 1024L * 1024L
            const val MAX_STALLED_UPLOAD_RESPONSES = 3
            const val MAX_ERROR_BODY_BYTES = 16L * 1024L
            val FRESH_UPLOAD_SESSION_STATUS_CODES = setOf(400, 404, 409, 410, 412)
        }
    }

private class FileSegmentRequestBody(
    private val file: File,
    private val offset: Long,
    private val byteCount: Long,
    contentType: String,
) : RequestBody() {
    private val mediaType = contentType.toMediaType()

    override fun contentType() = mediaType

    override fun contentLength(): Long = byteCount

    override fun writeTo(sink: BufferedSink) {
        RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            val buffer = ByteArray(64 * 1024)
            var remaining = byteCount
            while (remaining > 0L) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) throw IOException("Audio file ended during upload")
                sink.write(buffer, 0, count)
                remaining -= count
            }
        }
    }
}

private class CloudRequestException(
    val publicMessage: String,
    val retryable: Boolean,
    val requiresFreshUploadSession: Boolean = false,
) : Exception(publicMessage)

@Keep
private data class CreateDictationRequest(
    val contentType: String,
    val byteLength: Long,
    val durationMs: Long,
    val crc32c: String,
    val languageCode: String,
    val cleanup: Boolean,
)

@Keep
private data class CreateDictationResponse(
    val job: DictationJob,
    val upload: DictationUpload?,
)

@Keep
private data class DictationResponse(
    val job: DictationJob,
)

@Keep
private data class DictationJob(
    val id: String,
    val state: String,
    val transcript: String?,
    val rawTranscript: String?,
    val polishedTranscript: String?,
    val error: DictationError?,
)

@Keep
private data class DictationUpload(
    val sessionUrl: String,
    val method: String,
    val chunkSizeBytes: Long,
)

@Keep
private data class DictationError(
    val code: String,
    val message: String,
    val retryable: Boolean,
)

@Keep
private data class ServerErrorEnvelope(
    val error: ServerErrorDetail?,
)

@Keep
private data class ServerErrorDetail(
    val code: String?,
    val message: String?,
)
