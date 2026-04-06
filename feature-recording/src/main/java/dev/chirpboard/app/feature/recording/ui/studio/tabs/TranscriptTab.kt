package dev.chirpboard.app.feature.recording.ui.studio.tabs

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.feature.recording.ui.studio.TranscriptWord
import kotlinx.collections.immutable.ImmutableList

@Composable
fun TranscriptTab(
    words: ImmutableList<TranscriptWord>,
    onWordClicked: (Long) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    // Chunk words so we don't put 10,000 words into a single Text layout which might be slow.
    // 100 words per chunk gives a nice balance of not too many Text nodes,
    // but not one massive layout pass.
    val chunks = remember(words) { words.chunked(100) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        items(chunks) { chunk ->
            val annotatedString =
                buildAnnotatedString {
                    chunk.forEachIndexed { index, transcriptWord ->
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = "word_${transcriptWord.startTimestampMs}_$index",
                                linkInteractionListener = { onWordClicked(transcriptWord.startTimestampMs) },
                            ),
                        ) {
                            append(transcriptWord.word)
                        }
                        append(" ")
                    }
                }

            Text(
                text = annotatedString,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}
