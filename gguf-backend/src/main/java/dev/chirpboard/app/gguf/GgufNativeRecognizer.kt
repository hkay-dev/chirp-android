package dev.chirpboard.app.gguf

/** Narrow JNI owner for the trial-only transcribe.cpp backend. */
class GgufNativeRecognizer {
    init {
        System.loadLibrary("c++_shared")
        System.loadLibrary("chirp_gguf")
    }

    fun load(modelPath: String, threads: Int): Boolean = nativeLoad(modelPath, threads)

    fun isLoaded(): Boolean = nativeIsLoaded()

    fun transcribe(samples: FloatArray): String? = nativeTranscribe(samples)

    fun lastError(): String = nativeLastError()

    fun release() = nativeRelease()

    private external fun nativeLoad(modelPath: String, threads: Int): Boolean

    private external fun nativeIsLoaded(): Boolean

    private external fun nativeTranscribe(samples: FloatArray): String?

    private external fun nativeLastError(): String

    private external fun nativeRelease()
}
