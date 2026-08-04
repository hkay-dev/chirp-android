package dev.chirpboard.app.cloud

data class CloudServiceConfiguration(
    val baseUrl: String,
    val allowInsecureLoopback: Boolean = false,
    val pollIntervalMs: Long = 5_000L,
)
