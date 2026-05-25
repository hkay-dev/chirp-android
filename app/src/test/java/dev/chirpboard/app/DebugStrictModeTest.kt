package dev.chirpboard.app

import org.junit.Test

class DebugStrictModeTest {
    @Test
    fun `enableIfDebug is a no-op in release mode`() {
        DebugStrictMode.enableIfDebug(isDebug = false)
    }
}
