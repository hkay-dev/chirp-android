package dev.chirpboard.app.cloud

import java.io.IOException

/**
 * Supplies the short-lived user token accepted by the private dictation service.
 * Firebase Authentication will replace the unconfigured binding at the auth checkpoint.
 */
interface CloudAuthTokenProvider {
    /**
     * Returns null only when no user is signed in. Implementations translate a token-refresh or
     * Firebase service outage into [CloudAuthTemporarilyUnavailableException], so queued work
     * retries rather than treating a network failure as a permanent sign-out.
     */
    suspend fun getIdToken(): String?
}

class CloudAuthTemporarilyUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class UnconfiguredCloudAuthTokenProvider : CloudAuthTokenProvider {
    override suspend fun getIdToken(): String? = null
}
