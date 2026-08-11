package dev.chirpboard.app.core.ui.playback

import dev.chirpboard.app.core.playback.RecordingPlaybackState
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Visibility contract for the global mini-player bar, including the on-device sweep fix:
 * recording capture and playback UIs must never co-exist, so the bar hides for the whole
 * Record route (where it previously rendered under the record screen's action row).
 */
class ShouldShowGlobalMiniPlayerTest {
    private val recordingId = UUID.randomUUID()
    private val activeState = RecordingPlaybackState(recordingId = recordingId, hasStartedPlayback = true)
    private val recordRoute = "record?autoStart={autoStart}&profileId={profileId}"
    private val studioRoute = "processing_studio/{recordingId}"

    @Test
    fun hidden_whenPlaybackIsIdle() {
        assertFalse(
            shouldShowGlobalMiniPlayer(
                playbackState = RecordingPlaybackState(),
                currentRoute = "home",
                studioRecordingId = null,
            ),
        )
    }

    @Test
    fun shown_onHomeWhileActive() {
        assertTrue(
            shouldShowGlobalMiniPlayer(
                playbackState = activeState,
                currentRoute = "home",
                studioRecordingId = null,
            ),
        )
    }

    @Test
    fun shown_whileLoadingAfterAPlayRequest() {
        assertTrue(
            shouldShowGlobalMiniPlayer(
                playbackState = RecordingPlaybackState(isLoading = true, hasStartedPlayback = true),
                currentRoute = "home",
                studioRecordingId = null,
            ),
        )
    }

    @Test
    fun hidden_forASessionThatWasOnlyPrepared() {
        // Opening a Studio prepares playback without playing; navigating away must not
        // leave a "now playing" bar for audio the user never started.
        assertFalse(
            shouldShowGlobalMiniPlayer(
                playbackState = RecordingPlaybackState(recordingId = recordingId),
                currentRoute = "home",
                studioRecordingId = null,
            ),
        )
    }

    @Test
    fun shown_forErrorStateUntilDismissed() {
        assertTrue(
            shouldShowGlobalMiniPlayer(
                playbackState =
                    RecordingPlaybackState(
                        recordingId = recordingId,
                        errorMessage = "boom",
                        hasStartedPlayback = true,
                    ),
                currentRoute = "home",
                studioRecordingId = null,
            ),
        )
    }

    @Test
    fun hidden_forErrorFromAStudioPrepareTheUserNeverPlayed() {
        // Opening a Studio for a recording whose file is gone must not pin an error bar to
        // every other route; the Studio screen that triggered the prepare shows the message.
        assertFalse(
            shouldShowGlobalMiniPlayer(
                playbackState = RecordingPlaybackState(recordingId = recordingId, errorMessage = "boom"),
                currentRoute = "home",
                studioRecordingId = null,
            ),
        )
    }

    @Test
    fun hidden_onRecordRouteWhileActive() {
        assertFalse(
            shouldShowGlobalMiniPlayer(
                playbackState = activeState,
                currentRoute = recordRoute,
                studioRecordingId = null,
            ),
        )
    }

    @Test
    fun hidden_onRecordRouteEvenForErrorState() {
        // The record screen surfaces capture state itself; a stale playback error bar must
        // not wedge under its action row either. It re-surfaces on navigating back.
        assertFalse(
            shouldShowGlobalMiniPlayer(
                playbackState =
                    RecordingPlaybackState(
                        recordingId = recordingId,
                        errorMessage = "boom",
                        hasStartedPlayback = true,
                    ),
                currentRoute = recordRoute,
                studioRecordingId = null,
            ),
        )
    }

    @Test
    fun recordCheck_matchesRouteBaseExactly_notPrefixes() {
        // Routes that merely start with "record" (hypothetical "recordings") must not hide the bar.
        assertTrue(
            shouldShowGlobalMiniPlayer(
                playbackState = activeState,
                currentRoute = "recordings",
                studioRecordingId = null,
            ),
        )
        assertTrue(
            shouldShowGlobalMiniPlayer(
                playbackState = activeState,
                currentRoute = "word-replacements",
                studioRecordingId = null,
            ),
        )
    }

    @Test
    fun hidden_onStudioForTheSameRecording() {
        assertFalse(
            shouldShowGlobalMiniPlayer(
                playbackState = activeState,
                currentRoute = studioRoute,
                studioRecordingId = recordingId.toString(),
            ),
        )
    }

    @Test
    fun shown_onStudioForADifferentRecording() {
        assertTrue(
            shouldShowGlobalMiniPlayer(
                playbackState = activeState,
                currentRoute = studioRoute,
                studioRecordingId = UUID.randomUUID().toString(),
            ),
        )
    }
}
