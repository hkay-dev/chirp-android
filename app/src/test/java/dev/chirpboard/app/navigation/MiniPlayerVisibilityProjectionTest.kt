package dev.chirpboard.app.navigation

import dev.chirpboard.app.core.playback.RecordingPlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.UUID

/**
 * The navigation root must not recompose on the 10 Hz playback position tick (CMP-12). The
 * mini-player *visibility* projection drops the high-frequency position/duration fields so a
 * distinctUntilChanged feed only emits when a visibility-relevant field actually changes.
 */
class MiniPlayerVisibilityProjectionTest {
    private val recordingId = UUID.randomUUID()

    @Test
    fun `position and duration ticks project to the same value`() {
        val tick1 =
            RecordingPlaybackState(
                recordingId = recordingId,
                positionMs = 1_000L,
                durationMs = 30_000L,
                isPlaying = true,
            )
        val tick2 = tick1.copy(positionMs = 1_500L)

        // The only difference between two consecutive ticks is the position; the projection
        // must collapse them so distinctUntilChanged suppresses the emission.
        assertEquals(tick1.toVisibilityProjection(), tick2.toVisibilityProjection())
    }

    @Test
    fun `projection preserves the fields the visibility decision reads`() {
        val state =
            RecordingPlaybackState(
                recordingId = recordingId,
                positionMs = 5_000L,
                durationMs = 12_000L,
                isLoading = true,
                errorMessage = "boom",
            )

        val projected = state.toVisibilityProjection()

        assertEquals(recordingId, projected.recordingId)
        assertEquals(true, projected.isLoading)
        assertEquals("boom", projected.errorMessage)
        // High-frequency fields are zeroed so they cannot drive an emission.
        assertEquals(0L, projected.positionMs)
        assertEquals(0L, projected.durationMs)
    }

    @Test
    fun `becoming active changes the projection so the bar can appear`() {
        val idle = RecordingPlaybackState()
        val active = RecordingPlaybackState(recordingId = recordingId, isPlaying = true)

        assertNotEquals(idle.toVisibilityProjection(), active.toVisibilityProjection())
    }

    @Test
    fun `clearing the recording changes the projection so the bar can hide`() {
        val active = RecordingPlaybackState(recordingId = recordingId, positionMs = 9_000L)
        val cleared = active.copy(recordingId = null)

        assertNotEquals(active.toVisibilityProjection(), cleared.toVisibilityProjection())
    }
}
