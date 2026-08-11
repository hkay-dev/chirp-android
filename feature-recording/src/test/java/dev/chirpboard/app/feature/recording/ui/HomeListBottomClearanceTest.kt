package dev.chirpboard.app.feature.recording.ui

import androidx.compose.ui.unit.dp
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bottom-clearance stacking contract for the home list (on-device sweep fix, r17): the
 * reserved scroll range must cover the Record FAB, the quick-start surface stacked above it,
 * and the viewport the global mini-player bar steals when visible.
 */
class HomeListBottomClearanceTest {
    @Test
    fun baseClearance_coversTheFabAlone() {
        assertEquals(112.dp, homeListBottomClearance(quickStartVisible = false, miniPlayerVisible = false))
    }

    @Test
    fun quickStartSurface_addsItsStackHeight() {
        assertEquals(220.dp, homeListBottomClearance(quickStartVisible = true, miniPlayerVisible = false))
    }

    @Test
    fun miniPlayer_addsItsViewportAllowance() {
        assertEquals(200.dp, homeListBottomClearance(quickStartVisible = false, miniPlayerVisible = true))
    }

    @Test
    fun fullFloatingStack_isTheSumOfAllParts() {
        assertEquals(308.dp, homeListBottomClearance(quickStartVisible = true, miniPlayerVisible = true))
    }

    @Test
    fun impliesGlobalMiniPlayer_falseWhenIdle() {
        assertFalse(RecordingPlaybackRowState().impliesGlobalMiniPlayer())
    }

    @Test
    fun impliesGlobalMiniPlayer_trueForActiveLoadingAndError() {
        // Mirrors shouldShowGlobalMiniPlayer's predicate: (active OR loading OR error)
        // AND (playback actually started OR error).
        assertTrue(
            RecordingPlaybackRowState(
                recordingId = UUID.randomUUID(),
                hasStartedPlayback = true,
            ).impliesGlobalMiniPlayer(),
        )
        assertTrue(RecordingPlaybackRowState(isLoading = true, hasStartedPlayback = true).impliesGlobalMiniPlayer())
        assertTrue(RecordingPlaybackRowState(errorMessage = "boom").impliesGlobalMiniPlayer())
    }

    @Test
    fun impliesGlobalMiniPlayer_falseForAPreparedButNeverPlayedSession() {
        assertFalse(RecordingPlaybackRowState(recordingId = UUID.randomUUID()).impliesGlobalMiniPlayer())
    }
}
