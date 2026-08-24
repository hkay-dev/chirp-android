package dev.chirpboard.app.core.audio.recorder

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureEmergencyReserveTest {
    @Test
    fun `preparation replaces stale state with one bounded complete reserve`() {
        val directory = Files.createTempDirectory("capture-reserve").toFile()
        try {
            val partial = File(directory, "reserve.partial").apply { writeText("stale") }
            val reserve = File(directory, "reserve.bin").apply { writeText("short") }
            val store = store(directory)

            assertTrue(store.prepare())

            assertFalse(partial.exists())
            assertTrue(reserve.isFile)
            assertTrue(reserve.length() == TEST_RESERVE_BYTES)
            assertTrue(store.reclaim())
            assertFalse(reserve.exists())
            assertFalse(store.reclaim())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `reclaim never claims an in-flight partial reserve`() {
        val directory = Files.createTempDirectory("capture-reserve-race").toFile()
        try {
            lateinit var store: EmergencyReserveStore
            store =
                store(directory) { partial, bytes ->
                    assertFalse(store.reclaim())
                    RandomAccessFile(partial, "rw").use { it.setLength(bytes) }
                }

            assertFalse(store.prepare())

            assertFalse(store.reserveFileForTest().exists())
            assertFalse(store.partialFileForTest().exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `valid reserve startup still removes a stale partial`() {
        val directory = Files.createTempDirectory("capture-reserve-valid").toFile()
        try {
            val reserve = File(directory, "reserve.bin")
            RandomAccessFile(reserve, "rw").use { it.setLength(TEST_RESERVE_BYTES) }
            val partial = File(directory, "reserve.partial").apply { writeText("stale") }
            val store = store(directory) { _, _ -> error("valid reserve must not allocate") }

            assertTrue(store.prepare())

            assertTrue(reserve.isFile)
            assertFalse(partial.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a reclaimed reserve can be rebuilt for the next recording`() {
        val directory = Files.createTempDirectory("capture-reserve-rearm").toFile()
        try {
            val store = store(directory)
            assertTrue(store.prepare())
            assertTrue(store.reclaim())
            assertFalse(store.reserveFileForTest().exists())

            assertTrue(store.prepare())

            assertTrue(store.reserveFileForTest().isFile)
            assertTrue(store.reserveFileForTest().length() == TEST_RESERVE_BYTES)
            assertFalse(store.partialFileForTest().exists())
            // The rebuilt reserve is claimable again, so the safety net is not one-shot.
            assertTrue(store.reclaim())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `storage exhaustion matching is narrow`() {
        assertTrue(IOException("No space left on device").isStorageExhaustion())
        assertTrue(IOException("write failed", IOException("Quota exceeded")).isStorageExhaustion())
        assertFalse(IOException("permission denied").isStorageExhaustion())
    }

    private fun store(
        directory: File,
        allocateFile: (File, Long) -> Unit = { file, bytes ->
            RandomAccessFile(file, "rw").use { it.setLength(bytes) }
        },
    ): EmergencyReserveStore {
        val partial = File(directory, "reserve.partial")
        return EmergencyReserveStore(
            directory = directory,
            reserveBytes = TEST_RESERVE_BYTES,
            reserveFileName = "reserve.bin",
            partialFileName = partial.name,
            allocationSupported = { true },
            allocate = { _, bytes -> allocateFile(partial, bytes) },
        )
    }

    private companion object {
        const val TEST_RESERVE_BYTES = 16L * 1024L
    }
}
