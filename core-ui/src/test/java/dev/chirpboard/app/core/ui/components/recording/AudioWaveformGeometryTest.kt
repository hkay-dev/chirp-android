package dev.chirpboard.app.core.ui.components.recording

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Width-fill contract for the live waveform (on-device sweep fix): the visible history window
 * is derived from the actual canvas width so bars always span the card they are given; the
 * fixed bar-count default only sets a floor.
 */
class AudioWaveformGeometryTest {
    @Test
    fun slotCount_spansCanvasWiderThanTheBarCountFloor() {
        // 600px canvas at 10px per slot needs 60 slots; the 42-bar floor must not cap it.
        assertEquals(62, waveformVisibleSlotCount(canvasWidthPx = 600f, stepPx = 10f, barCountFloor = 42))
    }

    @Test
    fun slotCount_keepsTheFloorForNarrowCanvases() {
        // 100px canvas only needs 10 slots, but the floor keeps the historical window depth.
        assertEquals(44, waveformVisibleSlotCount(canvasWidthPx = 100f, stepPx = 10f, barCountFloor = 42))
    }

    @Test
    fun slotCount_roundsAPartialSlotUp() {
        // 605px / 10px = 60.5 slots: the half-covered edge slot must still be drawn.
        assertEquals(63, waveformVisibleSlotCount(canvasWidthPx = 605f, stepPx = 10f, barCountFloor = 42))
    }

    @Test
    fun slotCount_zeroStepFallsBackToTheFloor() {
        assertEquals(44, waveformVisibleSlotCount(canvasWidthPx = 600f, stepPx = 0f, barCountFloor = 42))
    }

    @Test
    fun slotCount_neverDropsBelowOneSlotPlusMargin() {
        assertEquals(3, waveformVisibleSlotCount(canvasWidthPx = 0f, stepPx = 10f, barCountFloor = 0))
    }
}
