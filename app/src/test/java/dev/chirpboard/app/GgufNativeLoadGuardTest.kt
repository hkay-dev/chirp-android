package dev.chirpboard.app

import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufNativeLoadGuardTest {
    @Test
    fun `an unloadable native library is captured instead of thrown`() {
        // System.loadLibrary throws UnsatisfiedLinkError, an Error; every catch on the
        // transcription path catches Exception, so an unguarded construction crashes the
        // worker instead of reporting the model as unavailable.
        val result = loadNativeGuarded<Any> { throw UnsatisfiedLinkError("dlopen failed: libchirp_gguf.so") }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is UnsatisfiedLinkError)
    }

    @Test
    fun `cancellation is rethrown rather than captured`() {
        val thrown =
            runCatching {
                loadNativeGuarded<Any> { throw CancellationException("caller went away") }
            }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
    }

    @Test
    fun `a successful construction is returned`() {
        assertEquals("engine", loadNativeGuarded { "engine" }.getOrNull())
    }
}
