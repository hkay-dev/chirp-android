package dev.chirpboard.app.feature.studio

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.chirpboard.app.core.audio.RecordingOutputFormat
import java.io.File

internal object ProcessingStudioShare {
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
        title: String,
        text: String,
    ): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun structuredOutcomeShareIntent(
        title: String,
        groupLabel: String,
        text: String,
    ): Intent =
        transcriptShareIntent(
            title = "$title - $groupLabel",
            text = text,
        )

    fun audioAndTranscriptShareIntent(
        context: Context,
        audioFile: File,
        title: String,
        text: String,
    ): Intent {
        val uri = fileUri(context, audioFile)
        return Intent(Intent.ACTION_SEND).apply {
            type = audioMimeType(audioFile)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

    internal fun audioMimeType(file: File): String = RecordingOutputFormat.fromFile(file).mimeType
}
