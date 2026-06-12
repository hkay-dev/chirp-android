package dev.chirpboard.app.feature.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * TST-014: the state observer must push every recording-state transition into ALL placed
 * widgets with the live duration — including the very first emission, which carries the
 * REAL current state (IME-16/PLT-04: a hardcoded Idle frame used to show "Tap to record"
 * while the mic was live).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WidgetStateObserverTest {
    private val context = mockk<Context>(relaxed = true)
    private val appWidgetManager = mockk<AppWidgetManager>()
    private val stateFlow = MutableStateFlow<RecordingState>(RecordingState.Idle)
    private val stateManager = mockk<RecordingStateManager>()

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)

    /** (widgetId, state, durationMs) for every render pushed to the provider. */
    private val renders = mutableListOf<Triple<Int, RecordingState, Long>>()

    @Before
    fun setUp() {
        every { stateManager.state } returns stateFlow
        every { stateManager.getCurrentDurationMs() } returns 42_000L

        mockkConstructor(ComponentName::class)
        mockkStatic(AppWidgetManager::class)
        every { AppWidgetManager.getInstance(context) } returns appWidgetManager
        every { appWidgetManager.getAppWidgetIds(any()) } returns intArrayOf(7, 9)

        mockkObject(RecordingWidgetProvider.Companion)
        every {
            RecordingWidgetProvider.updateAppWidgetWithState(any(), any(), any(), any(), any())
        } answers {
            renders +=
                Triple(
                    thirdArg<Int>(),
                    arg<RecordingState>(3),
                    arg<Long>(4),
                )
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun startedObserver(): WidgetStateObserver =
        WidgetStateObserver(context, stateManager).apply {
            scope = testScope.backgroundScope
            startObserving()
        }

    @Test
    fun startObserving_rendersTheCurrentLiveStateToEveryWidget() {
        // The W2 live-state fix: the flow already holds a live Recording when the observer
        // subscribes (e.g. process restart while capturing) — the first render must carry
        // that real state, never a hardcoded Idle frame.
        val live = RecordingState.Recording(origin = RecordingOrigin.WIDGET)
        stateFlow.value = live

        startedObserver()
        testScope.runCurrent()

        assertEquals(
            listOf(
                Triple(7, live as RecordingState, 42_000L),
                Triple(9, live as RecordingState, 42_000L),
            ),
            renders.toList(),
        )
    }

    @Test
    fun stateTransitions_pushOneRenderPerWidgetPerState() {
        startedObserver()
        testScope.runCurrent()

        val recording = RecordingState.Recording(origin = RecordingOrigin.APP)
        stateFlow.value = recording
        testScope.runCurrent()

        val stopping =
            RecordingState.Stopping(origin = RecordingOrigin.APP, recordingId = UUID.randomUUID())
        stateFlow.value = stopping
        testScope.runCurrent()

        val statesPerWidget = renders.filter { it.first == 7 }.map { it.second }
        assertEquals(listOf(RecordingState.Idle, recording, stopping), statesPerWidget)
        // Both placed widgets received every transition.
        assertEquals(renders.size, statesPerWidget.size * 2)
    }

    @Test
    fun noWidgetsPlaced_skipsRenderWork() {
        every { appWidgetManager.getAppWidgetIds(any()) } returns intArrayOf()

        startedObserver()
        testScope.runCurrent()
        stateFlow.value = RecordingState.Recording(origin = RecordingOrigin.APP)
        testScope.runCurrent()

        assertEquals(emptyList<Triple<Int, RecordingState, Long>>(), renders.toList())
        verify(exactly = 0) {
            RecordingWidgetProvider.updateAppWidgetWithState(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun missingAppWidgetManager_isASafeNoOp() {
        every { AppWidgetManager.getInstance(context) } returns null

        startedObserver()
        testScope.runCurrent()
        stateFlow.value = RecordingState.Recording(origin = RecordingOrigin.APP)
        testScope.runCurrent()

        assertEquals(emptyList<Triple<Int, RecordingState, Long>>(), renders.toList())
    }
}
