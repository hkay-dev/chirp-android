package dev.chirpboard.app.feature.recording.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chirpboard.app.core.playback.RecordingPlaybackState
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

@RunWith(AndroidJUnit4::class)
class RecordingListItemProgressTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun recordingListItem_inTranscribingStatus_showsCompactProgress() {
        val item =
            RecordingDisplayItem(
                recording =
                    Recording(
                        title = "Meeting notes",
                        audioPath = "/tmp/test.m4a",
                        status = RecordingStatus.TRANSCRIBING,
                        source = RecordingSource.APP,
                        createdAt = Date(),
                        durationMs = 30_000L,
                    ),
            )

        composeRule.setContent {
            MaterialTheme {
                RecordingListItem(
                    item = item,
                    playbackState = RecordingPlaybackState(),
                    recordingState = RecordingState.Idle,
                    onClick = {},
                    onPlayClick = {},
                    onLongClick = {},
                )
            }
        }

        composeRule.onNodeWithText("Transcribing").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Your transcript will appear here when processing finishes.",
        ).assertIsDisplayed()
    }
}
