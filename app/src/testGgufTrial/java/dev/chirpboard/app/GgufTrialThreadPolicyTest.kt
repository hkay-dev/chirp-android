package dev.chirpboard.app

import org.junit.Assert.assertEquals
import org.junit.Test

class GgufTrialThreadPolicyTest {
    @Test
    fun `uses only cores within seventy percent of the fastest core`() {
        val frequencies = listOf(1_800L, 1_800L, 2_900L, 2_900L, 3_500L, 3_500L, 4_400L, 4_400L)

        assertEquals(
            4,
            optimizedGgufThreadCount(frequencies.size) { frequencies[it] },
        )
    }

    @Test
    fun `falls back to a four thread cap when sysfs is unavailable`() {
        assertEquals(4, optimizedGgufThreadCount(8) { null })
    }
}
