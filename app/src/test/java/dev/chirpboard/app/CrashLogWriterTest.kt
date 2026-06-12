package dev.chirpboard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ERR-21: the local crash breadcrumb writer must record the stack trace, rotate old files,
 * and always delegate to the previously installed handler (system crash semantics unchanged).
 */
class CrashLogWriterTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newWriter(
        dir: File,
        clock: () -> Long = System::currentTimeMillis,
    ) = CrashLogWriter(dir, clock)

    @Test
    fun `writeCrashLog records timestamp, thread and stack trace`() {
        val dir = tempFolder.newFolder("crashlogs")
        val writer = newWriter(dir, clock = { 1_700_000_000_000L })

        writer.writeCrashLog(Thread.currentThread(), RuntimeException("boom marker"))

        val logs = dir.listFiles().orEmpty()
        assertEquals(1, logs.size)
        val content = logs.single().readText()
        assertTrue(content.contains("thread: ${Thread.currentThread().name}"))
        assertTrue(content.contains("boom marker"))
        assertTrue(content.contains("RuntimeException"))
        assertTrue(content.contains("epochMs=1700000000000"))
    }

    @Test
    fun `creates the log directory when missing`() {
        val dir = File(tempFolder.root, "nested/crashlogs")
        val writer = newWriter(dir)

        writer.writeCrashLog(Thread.currentThread(), IllegalStateException("first crash"))

        assertTrue(dir.isDirectory)
        assertEquals(1, dir.listFiles().orEmpty().size)
    }

    @Test
    fun `prunes to the newest MAX_LOG_FILES records`() {
        val dir = tempFolder.newFolder("crashlogs")
        var fakeNow = 1_700_000_000_000L
        val writer = newWriter(dir, clock = { fakeNow })

        repeat(CrashLogWriter.MAX_LOG_FILES + 3) { index ->
            fakeNow += 60_000L
            writer.writeCrashLog(Thread.currentThread(), RuntimeException("crash $index"))
        }

        val logs = dir.listFiles().orEmpty().sortedByDescending { it.name }
        assertEquals(CrashLogWriter.MAX_LOG_FILES, logs.size)
        // The newest record survives; the oldest were pruned.
        assertTrue(logs.first().readText().contains("crash ${CrashLogWriter.MAX_LOG_FILES + 2}"))
    }

    @Test
    fun `install chains to the previous handler and still writes the log`() {
        val dir = tempFolder.newFolder("crashlogs")
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        try {
            var delegatedThrowable: Throwable? = null
            Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
                delegatedThrowable = throwable
            }

            newWriter(dir).install()
            val crash = RuntimeException("delegated crash")
            Thread.getDefaultUncaughtExceptionHandler()!!
                .uncaughtException(Thread.currentThread(), crash)

            assertNotNull(delegatedThrowable)
            assertEquals(crash, delegatedThrowable)
            assertEquals(1, dir.listFiles().orEmpty().size)
            assertTrue(dir.listFiles().orEmpty().single().readText().contains("delegated crash"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(originalHandler)
        }
    }
}
