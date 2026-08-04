package dev.chirpboard.app.gguf

data class GgufNativeDecodeTelemetry(
    val loadMs: Float,
    val melMs: Float,
    val encodeMs: Float,
    val decodeMs: Float,
    val aborted: Boolean,
    val statusCode: Int,
)

/** Narrow JNI owner for the transcribe.cpp backend. */
class GgufNativeRecognizer {
    init {
        System.loadLibrary("c++_shared")
        System.loadLibrary("chirp_gguf")
    }

    fun supportsVulkan(): Boolean = nativeSupportsVulkan()

    fun usesKleidiAi(): Boolean = nativeUsesKleidiAi()

    fun load(modelPath: String, threads: Int, useVulkan: Boolean = false): Boolean =
        nativeLoad(modelPath, threads, if (useVulkan) BACKEND_VULKAN else BACKEND_CPU)

    fun loadedBackend(): String = nativeLoadedBackend()

    fun usedCpuFallback(): Boolean = nativeUsedCpuFallback()

    fun isLoaded(): Boolean = nativeIsLoaded()

    /** Starts one cancellable native operation and returns its stale-cancel-safe identifier. */
    fun beginDecode(): Long = nativeBeginDecode()

    /** Requests cancellation only when [operationId] still owns the active native run. */
    fun cancelDecode(operationId: Long): Boolean = nativeCancelDecode(operationId)

    fun decodeTelemetry(operationId: Long): GgufNativeDecodeTelemetry? =
        nativeDecodeTelemetry(operationId)?.toDecodeTelemetry()

    fun transcribe(samples: FloatArray): String? = nativeTranscribe(samples)

    fun transcribePcmFloatFile(path: String, sampleCount: Long): String? =
        nativeTranscribePcmFloatFile(path, sampleCount)

    fun transcribeBatch(samples: Array<FloatArray>): Array<String>? = nativeTranscribeBatch(samples)

    fun lastError(): String = nativeLastError()

    fun release() = nativeRelease()

    private external fun nativeLoad(modelPath: String, threads: Int, backendCode: Int): Boolean

    private external fun nativeSupportsVulkan(): Boolean

    private external fun nativeUsesKleidiAi(): Boolean

    private external fun nativeLoadedBackend(): String

    private external fun nativeUsedCpuFallback(): Boolean

    private external fun nativeIsLoaded(): Boolean

    private external fun nativeBeginDecode(): Long

    private external fun nativeCancelDecode(operationId: Long): Boolean

    private external fun nativeDecodeTelemetry(operationId: Long): FloatArray?

    private external fun nativeTranscribe(samples: FloatArray): String?

    private external fun nativeTranscribePcmFloatFile(path: String, sampleCount: Long): String?

    private external fun nativeTranscribeBatch(samples: Array<FloatArray>): Array<String>?

    private external fun nativeLastError(): String

    private external fun nativeRelease()

    private companion object {
        const val BACKEND_CPU = 0
        const val BACKEND_VULKAN = 1
    }
}

internal fun FloatArray.toDecodeTelemetry(): GgufNativeDecodeTelemetry? {
    if (size != 6 || any { !it.isFinite() || it < 0f }) return null
    return GgufNativeDecodeTelemetry(
        loadMs = this[0],
        melMs = this[1],
        encodeMs = this[2],
        decodeMs = this[3],
        aborted = this[4] >= 0.5f,
        statusCode = this[5].toInt(),
    )
}
