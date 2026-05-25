> **Archive note:** Historical paths below reference the pre-split `:core` module. See [`PATH_REMAPPING.md`](PATH_REMAPPING.md) for current locations.
>

# Codebase Review Report V2

*Date: April 5, 2026*

This report summarizes new critical issues identified in the most recent codebase scans.

## 1. Room DB & Persistence
* **TOCTOU Race Condition in Profile Creation:** `ProfileRepository` fetches `maxSortOrder` and inserts without a transaction.
    * *Fix:* Wrap the operation in `database.withTransaction { ... }`.
* **Atomicity Violation & N+1 Queries:** `RecordingRepository` uses multiple transactions and individual queries when deleting by ID.
    * *Fix:* Implement `deleteByIds(ids: List<UUID>)` and wrap the chunked deletion logic in a single transaction.

## 2. Coroutines & Concurrency
* **State Corruption in Recording Duration:** `RecordingStateManager` modifies `AtomicLong` inside a `StateFlow.update` CAS loop.
    * *Fix:* Move duration calculation outside the CAS loop.
* **Premature Lock Release:** `RecordingStateManager` calls `recordingLock.set(false)` inside the `StateFlow.update` CAS loop.
    * *Fix:* Move lock release to *after* the update block completes.
* **Dual-Update Race Condition:** `ChirpKeyboardService` eagerly toggles state while observing datastore concurrently.
    * *Fix:* Remove eager `_llmEnabled` assignment; rely on datastore observation.
* **Thread Safety in Map Writes:** `HomeViewModel` uses a standard `MutableMap` with concurrent background coroutines.
    * *Fix:* Replace with `ConcurrentHashMap` or guard with a `Mutex`.
* **Leaked Coroutine Scope:** `VoiceRecorder` holds a private scope without a lifecycle termination mechanism.
    * *Fix:* Implement `Closeable` and cancel the scope in `onDestroy()`.
* **Leaked Timeout Jobs:** `RecordingStateManager` leaves timeout jobs running after recording stops.
    * *Fix:* Store and explicitly cancel timeout jobs on completion or cancellation.

## 3. Compose Performance
* **Improper `remember` Usage:** `ChirpKeyboardService` uses `remember` on a rapidly changing list, triggering excessive `toImmutableList()` calls.
    * *Fix:* Convert to `ImmutableList` upstream.
* **Rapid UI State Emissions:** `KeyboardUI` collects high-frequency flow at the root, forcing full recomposition.
    * *Fix:* Pass flow down and collect state internally in the visualizer.

## 4. Android Framework & Service Lifecycle
* **Double-Tap Destroys Active Recording:** `RecordingService` calls `stopSelf()` when it receives a redundant start request, even if recording is already active.
    * *Fix:* Return silently if `tryStartRecording` returns `AlreadyRecording`.
* **Recording Error Overwrite on Stop:** `stopAndTranscribe()` cancels the IO job and stops audio simultaneously, causing an error emission.
    * *Fix:* Ignore `ERROR_INVALID_OPERATION` if triggered by a deliberate `stop()` call.
* **Native Resource Leaks (`MediaRecorder` / `MediaPlayer`):** Instances are instantiated in `apply` blocks, orphaning them if exceptions occur during configuration.
    * *Fix:* Instantiate and assign the instance *before* configuration.
* **InputMethodService Window Leak:** `ChirpKeyboardService` creates new `ComposeView`s without disposing of old ones.
    * *Fix:* Call `disposeComposition()` on `onFinishInputView()`.
* **Text Lost on Cancellation:** `KeyboardTranscriptionPipeline` fails to persist text when a `CancellationException` occurs.
    * *Fix:* Use `NonCancellable` context for persistence.

## 5. Audio & Performance Inefficiencies
* **Autoboxing GC Churn:** `AudioDecoder` and `ChunkedAudioProcessor` use `ArrayDeque<Float>`, forcing millions of objects.
    * *Fix:* Use a primitive `FloatArray` circular buffer.
* **GC Pressure from Date Formatters:** `Extensions.kt` instantiates formatters on every call.
    * *Fix:* Use `ThreadLocal` formatters or `java.time`.
* **Exponential Time Complexity:** `ChunkedAudioProcessor` analyzes the entire transcript buffer repeatedly.
    * *Fix:* Analyze only the last chunk.
* **ANR Risk:** `RecordingStopOrchestrator` blocks the main thread with `runBlocking(Dispatchers.IO)`.
    * *Fix:* Refactor to `suspend` function and launch in a dedicated scope.
