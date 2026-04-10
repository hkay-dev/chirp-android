# Parakeeboard: Technical Hardening Specification (v1.0)

This document outlines the critical performance bottlenecks, memory leaks, and threading vulnerabilities identified in the Parakeeboard codebase. Implementing these fixes is mandatory for achieving "Pro" tier stability and fluid 120Hz UI performance.

---

## 1. Audio Pipeline: Eliminating GC Churn & Autoboxing
**Current Defect:** 
In `VoiceRecorder.kt` (feature-keyboard) and `AudioDecoder.kt` (feature-transcription), audio samples are frequently handled as `MutableList<Float>` or `ArrayDeque<Float>`. 
*   **The Math:** At 16,000Hz, a 10-minute recording generates **9,600,000 samples**. Using `List<Float>` forces the JVM to wrap every 4-byte primitive into a 16-byte `java.lang.Float` object.
*   **Impact:** This creates ~150MB of garbage per 10 minutes, leading to frequent GC pauses (stuttering) and potential `OutOfMemoryError` on low-end devices.

**Hardening Requirement:**
*   **Action:** Replace all `List<Float>` and `MutableList<Float>` in audio capture loops with **primitive `FloatArray`**.
*   **Pattern:** Implement a **Fixed-Size Circular Ring Buffer** for real-time sample capture.

```kotlin
// BEFORE (Buggy/Inefficient)
private val samples = mutableListOf<Float>() 
// ... in loop ...
samples.add(buffer[i]) // AUTOBOXING TRIGGER

// AFTER (Hardened)
private var samples = FloatArray(INITIAL_CAPACITY)
private var sampleCount = 0
// ... in loop ...
if (sampleCount >= samples.size) {
    samples = samples.copyOf(samples.size * 2) // Surgical resize
}
samples[sampleCount++] = buffer[i] // ZERO ALLOCATION
```

---

## 2. State Management: Solving the "Recomposition Storm"
**Current Defect:**
`RecordingStateManager.kt` updates the amplitude history every **16ms** (60fps) to drive the waveform UI.
*   **The Code:** `_amplitudeHistory.update { (it + normalized).takeLast(1000) }`.
*   **Impact:** This allocates **two new lists** every 16ms and copies up to 1000 references. This is the primary cause of "UI Heat" where the phone warms up during recording despite low CPU usage.

**Hardening Requirement:**
*   **Action:** Decouple "Global State" from "High-Frequency UI Data".
*   **Pattern:** Use a `FloatArray` backed by a `MutableStateFlow` or a dedicated `WaveformState` object that uses a `CircularBuffer`.

```kotlin
// HARDENED STATE PATTERN
class WaveformBuffer(val capacity: Int = 1000) {
    private val buffer = FloatArray(capacity)
    private var index = 0
    
    // Use a single StateFlow update to trigger recomposition, 
    // but keep the data primitive.
    private val _dataVersion = MutableStateFlow(0L)
    val dataVersion = _dataVersion.asStateFlow()

    fun add(amplitude: Float) {
        buffer[index] = amplitude
        index = (index + 1) % capacity
        _dataVersion.value++
    }
}
```

---

## 3. UI Layer: Stable Waveform Rendering
**Current Defect:**
`AudioWaveform.kt` takes a `List<Float>`. Because `List` is an interface, Compose treats it as **unstable**. 
*   **Impact:** The entire Waveform component and its parents recompose 60 times a second, even if the content hasn't changed enough to warrant a redraw.

**Hardening Requirement:**
*   **Action:** Use **`ImmutableList`** from `kotlinx.collections.immutable` or pass a primitive-friendly wrapper.
*   **Pattern:** Draw the waveform directly using `drawWithCache` to avoid object allocation in the `Canvas` block.

---

## 4. Threading: Preventing Silent Data Loss
**Current Defect:**
Generic `catch (e: Exception)` blocks in `RecordingService` and `TranscriptionPipeline` swallow `CancellationException`.
*   **The Scenario:** If the app is killed or the keyboard closes, the coroutine is cancelled. If the `catch` block swallows this, the `NonCancellable` cleanup code never runs.
*   **Impact:** Current recording data is orphaned in `/tmp` and never persisted to the database, leading to "Lost Recording" bugs.

**Hardening Requirement:**
*   **Action:** Audit all `catch` blocks in the recording path.
*   **Mandate:** Always rethrow `CancellationException`.

```kotlin
// HARDENED CATCH PATTERN
try {
    saveAudioFile()
} catch (e: Exception) {
    if (e is CancellationException) throw e // MANDATORY
    Log.e(TAG, "Save failed", e)
} finally {
    withContext(NonCancellable) { // ENSURE CLEANUP
        releaseHardwareResources()
    }
}
```

---

## 5. Lifecycle Safety: Memory Leak Prevention
**Current Defect:**
`VoiceRecorder` creates a `CoroutineScope(SupervisorJob() + Dispatchers.Default)`. 
*   **The Leak:** If the `RecordingService` fails to call `close()` on the recorder (e.g. during a crash or abrupt service stop), this scope and its associated flows stay active in memory.

**Hardening Requirement:**
*   **Action:** Bind the `VoiceRecorder` scope directly to the `Service` lifecycle or `ViewModel` scope.
*   **Validation:** Use `LeakCanary` in debug builds specifically targeted at the `RecordingService`.

---

## Next Steps for Execution
1.  **Phase 1 (Critical):** Fix the `RecordingStateManager` list-copying logic and `VoiceRecorder` autoboxing.
2.  **Phase 2 (UX):** Update `AudioWaveform` to use stable types and GPU-accelerated `graphicsLayer`.
3.  **Phase 3 (Safety):** Run a global sweep for `CancellationException` handling in all `feature-*` modules.
