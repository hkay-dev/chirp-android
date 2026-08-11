package dev.chirpboard.app.feature.studio

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProcessingStudioShareTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `writeTranscriptShareFile writes into the provider-mapped subdirectory and prunes stale shares`() {
        val cacheDir = tempFolder.newFolder("cache")
        val context = mockk<Context> { every { this@mockk.cacheDir } returns cacheDir }
        val shareDir = File(cacheDir, "transcript-shares").apply { mkdirs() }
        val stale = File(shareDir, "transcript-old.txt").apply { writeText("old") }
        stale.setLastModified(System.currentTimeMillis() - 2L * 60L * 60L * 1000L)
        val recent = File(shareDir, "transcript-recent.txt").apply { writeText("recent") }

        val written = ProcessingStudioShare.writeTranscriptShareFile(context, "share body")

        assertEquals(shareDir, written.parentFile)
        assertEquals("share body", written.readText())
        assertTrue(!stale.exists())
        assertTrue(recent.exists())
    }

    @Test
    fun `buildTranscriptShareText includes summary and transcript sections`() {
        val text =
            ProcessingStudioShare.buildTranscriptShareText(
                title = "Meeting notes",
                summary = "Quick recap",
                transcriptText = "Hello world",
            )

        assertTrue(text.contains("# Meeting notes"))
        assertTrue(text.contains("## Summary"))
        assertTrue(text.contains("Quick recap"))
        assertTrue(text.contains("## Transcript"))
        assertTrue(text.contains("Hello world"))
    }

    @Test
    fun `buildStructuredOutcomeShareText includes group label`() {
        val text =
            ProcessingStudioShare.buildStructuredOutcomeShareText(
                title = "Meeting notes",
                groupLabel = "Tasks",
                itemText = "Follow up with Alex",
            )

        assertEquals(
            """
            # Meeting notes

            ## Tasks
            Follow up with Alex
            """.trimIndent(),
            text.trim(),
        )
    }

    @Test
    fun `audioMimeType uses canonical extension mime types`() {
        assertEquals("audio/mp4", ProcessingStudioShare.audioMimeType(File("recording.m4a")))
        assertEquals("audio/mpeg", ProcessingStudioShare.audioMimeType(File("recording.mp3")))
        assertEquals("audio/wav", ProcessingStudioShare.audioMimeType(File("recording.wav")))
    }

    @Test
    fun `audioMimeType refuses raw keyboard PCM instead of calling it MP4`() {
        val failure = runCatching { ProcessingStudioShare.audioMimeType(File("recording.f32pcm")) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }
}
