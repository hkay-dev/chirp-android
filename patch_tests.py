import os

def fix_view_model_test():
    path = "feature-recording/src/test/java/dev/chirpboard/app/feature/recording/ui/RecordViewModelTest.kt"
    if not os.path.exists(path): return
    with open(path, "r") as f:
        text = f.read()

    text = text.replace("every { amplitudeHistoryFlow } returns MutableStateFlow(emptyList())", "every { waveformBuffer } returns dev.chirpboard.app.core.recording.WaveformBuffer(1000)")
    text = text.replace("assertEquals(emptyList<Float>(), viewModel.amplitudeHistory.value)", "assertEquals(0, viewModel.waveformBuffer.count)")

    with open(path, "w") as f:
        f.write(text)

def fix_state_manager_test():
    path = "core/src/test/java/dev/chirpboard/app/core/recording/RecordingStateManagerTest.kt"
    if not os.path.exists(path): return
    with open(path, "r") as f:
        text = f.read()

    text = text.replace("assertEquals(listOf(0.5f), manager.amplitudeHistoryFlow.value)", "assertEquals(0.5f, manager.waveformBuffer.get(0)); assertEquals(1, manager.waveformBuffer.count)")
    text = text.replace("assertEquals(listOf(0.5f, 0.8f), manager.amplitudeHistoryFlow.value)", "assertEquals(0.5f, manager.waveformBuffer.get(0)); assertEquals(0.8f, manager.waveformBuffer.get(1)); assertEquals(2, manager.waveformBuffer.count)")
    text = text.replace("assertTrue(manager.amplitudeHistoryFlow.value.isEmpty())", "assertEquals(0, manager.waveformBuffer.count)")

    with open(path, "w") as f:
        f.write(text)

if __name__ == "__main__":
    fix_view_model_test()
    fix_state_manager_test()
