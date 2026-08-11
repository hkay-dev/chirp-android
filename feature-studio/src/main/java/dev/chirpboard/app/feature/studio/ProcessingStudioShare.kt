package dev.chirpboard.app.feature.studio

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.chirpboard.app.core.audio.RecordingOutputFormat
import java.io.File

internal object ProcessingStudioShare {
    /**
     * EXTRA_TEXT travels in a Binder transaction capped near 1 MB shared with everything else in
     * the intent; long dictations blow past it and crash with TransactionTooLargeException.
     * Above this size the transcript ships as a text file through the FileProvider instead.
     */
    const val MAX_SHARE_EXTRA_TEXT_CHARS = 100_000

    private const val SHARE_DIR = "transcript-shares"
    private const val SHARE_FILE_MAX_AGE_MS = 60L * 60L * 1000L

    /**
     * Writes the share text into the provider-mapped transcript-shares cache subdirectory.
     * Disk IO: call off the main thread. Files from earlier shares are pruned once they are old
     * enough that no receiver can still be reading them.
     */
    fun writeTranscriptShareFile(
        context: Context,
        text: String,
    ): File {
        val dir = File(context.cacheDir, SHARE_DIR)
        dir.mkdirs()
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { existing ->
            if (now - existing.lastModified() > SHARE_FILE_MAX_AGE_MS) existing.delete()
        }
        val file = File(dir, "transcript-$now.txt")
        file.writeText(text)
        return file
    }

    fun buildTranscriptShareText(
        title: String,
        summary: String,
        transcriptText: String,
    ): String =
        buildString {
            appendLine("# $title")
            appendLine()
            if (summary.isNotEmpty()) {
                appendLine("## Summary")
                appendLine(summary)
                appendLine()
            }
            appendLine("## Transcript")
            appendLine(transcriptText)
        }

    fun buildStructuredOutcomeShareText(
        title: String,
        groupLabel: String,
        itemText: String,
    ): String =
        buildString {
            appendLine("# $title")
            appendLine()
            appendLine("## $groupLabel")
            appendLine(itemText)
        }

    fun audioShareIntent(
        context: Context,
        audioFile: File,
        title: String,
    ): Intent {
        val uri = fileUri(context, audioFile)
        return Intent(Intent.ACTION_SEND).apply {
            type = audioMimeType(audioFile)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun transcriptShareIntent(
        context: Context,
        title: String,
        text: String,
    ): Intent =
        if (text.length <= MAX_SHARE_EXTRA_TEXT_CHARS) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            val uri = fileUri(context, writeTranscriptShareFile(context, text))
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

    fun structuredOutcomeShareIntent(
        context: Context,
        title: String,
        groupLabel: String,
        text: String,
    ): Intent =
        transcriptShareIntent(
            context = context,
            title = "$title - $groupLabel",
            text = text,
        )

    fun audioAndTranscriptShareIntent(
        context: Context,
        audioFile: File,
        title: String,
        text: String,
    ): Intent {
        val audioUri = fileUri(context, audioFile)
        return if (text.length <= MAX_SHARE_EXTRA_TEXT_CHARS) {
            Intent(Intent.ACTION_SEND).apply {
                type = audioMimeType(audioFile)
                putExtra(Intent.EXTRA_STREAM, audioUri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            val textUri = fileUri(context, writeTranscriptShareFile(context, text))
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(audioUri, textUri))
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun chooserIntent(
        shareIntent: Intent,
        title: String,
    ): Intent =
        Intent.createChooser(shareIntent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun fileUri(
        context: Context,
        file: File,
    ): Uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)

    internal fun audioMimeType(file: File): String {
        require(isPlaybackAndShareReadyAudioPath(file.path)) { "Raw keyboard audio is not shareable" }
        return RecordingOutputFormat.fromFile(file).mimeType
    }
}
