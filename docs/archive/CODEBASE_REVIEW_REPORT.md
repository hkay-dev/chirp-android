# Chirpboard Architecture & Reliability Deep Scan Report
*Date: April 5, 2026*

> **Archive note:** File paths below reference the pre-split `:core` module. See [`PATH_REMAPPING.md`](PATH_REMAPPING.md) for current locations.

This document details the findings of a comprehensive, multi-agent codebase review. The scan identified critical defects, race conditions, memory leaks, and reliability issues across the `core`, `data`, `feature-recording`, `feature-transcription`, `feature-keyboard`, `feature-obsidian`, and `feature-llm` modules.

---

## 1. Syntax Errors & Dead Code

### **Syntax Error: `ScreenScaffold` Signature**
* **File:** `core/src/main/java/dev/chirpboard/app/core/ui/components/ScreenScaffold.kt` (Lines 29-30)
* **Defect:** Blatant syntax error. The `ScreenScaffold` composable is missing the closing parenthesis and block `) {` between the parameter list and the function body.
* **Required Fix:** Add `) {` at the end of the `ScreenScaffold` parameter list before the `Scaffold` call.

### **Dead Code: Unreachable Obsidian Sync Pipeline**
* **File:** `feature-obsidian/src/main/java/dev/chirpboard/app/feature/obsidian/ObsidianManager.kt` (Lines 35-69)
* **Defect:** The `export()` function is never invoked anywhere in the `app`, `feature-keyboard`, or `feature-widget` modules outside of test files. The entire Obsidian sync capability is functionally dead code in production.
* **Required Fix:** Integrate `ObsidianManager.export()` into `VoiceRecognitionPipeline.kt` and `KeyboardTranscriptionPipeline.kt`, or remove the module if the feature was abandoned.

### **Dead Code: Unused LLM Orchestrator**
* **File:** `feature-llm/src/main/java/dev/chirpboard/app/feature/llm/LlmProcessor.kt` (Lines 1-42)
* **Defect:** `LlmProcessor` is completely unused. Both the keyboard service and the app service directly invoke `TextProcessor` for LLM tasks.
* **Required Fix:** Safely delete `LlmProcessor.kt` and its associated tests to reduce compilation overhead and codebase size.

---

## 2. Race Conditions & Atomicity Violations

### **State Corruption in Recording Duration**
* **File:** `core/src/main/java/dev/chirpboard/app/core/recording/RecordingStateManager.kt` (Line 140)
* **Defect:** Modifies an `AtomicLong` (`accumulatedSegmentMs.addAndGet`) *inside* a `StateFlow.update` CAS (Compare-And-Swap) loop. If there is contention updating the flow, the loop will retry, executing `addAndGet` multiple times. This will exponentially inflate the recorded duration time.
* **Required Fix:** Move the math and side effects outside of the `update` loop, or calculate the `totalAccumulated` purely based on immutable state within the CAS loop and apply the final value to the `AtomicLong` after the `update` block succeeds.

### **Premature Lock Release Destroying New Recordings**
* **File:** `core/src/main/java/dev/chirpboard/app/core/recording/RecordingStateManager.kt` (Lines 243, 249, 222)
* **Defect:** Calls `recordingLock.set(false)` inside the `_state.update` CAS loop. If the CAS loop is evaluating or retries, the lock is prematurely released while the state hasn't been committed. Another thread can instantly call `tryStartRecording`, acquire the lock, and start a new session, only to have the original CAS loop eventually succeed and force the state back to `Idle`, silently destroying the new recording session.
* **Required Fix:** Never trigger side effects inside `StateFlow.update`. Call `recordingLock.set(false)` immediately *after* the `_state.update` block completes.

### **TOCTOU Race Condition in Profile Creation**
* **File:** `data/src/main/java/dev/chirpboard/app/data/repository/ProfileRepository.kt` (Lines 44-60)
* **Defect:** Fetches the maximum sort order `val maxOrder = profileDao.getMaxSortOrder() ?: 0` and then executes `profileDao.insert(profile)` without a transaction or lock. If two profiles are created concurrently, both will receive the same `sortOrder`, breaking UI list deterministic ordering.
* **Required Fix:** Wrap the entire operation in a `database.withTransaction { ... }` block to ensure the read-modify-write cycle is atomic.

### **Dual-Update Race Condition on LLM Toggle**
* **File:** `feature-keyboard/src/main/java/dev/chirpboard/app/feature/keyboard/service/ChirpKeyboardService.kt` (Lines 125-130, 144-150)
* **Defect:** `toggleLlm()` eagerly flips `_llmEnabled.value` and launches a coroutine to persist it. Concurrently, `observePreferences` collects changes from datastore and updates `_llmEnabled.value` again. Rapid toggling by the user will cause the UI to rubber-band.
* **Required Fix:** Remove the eager `_llmEnabled.value = newValue` assignment. Let the unidirectional data flow from `observePreferences` be the sole mutator of the local state.

### **Double-Tap Destroys Active Recording**
* **File:** `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/service/RecordingService.kt` (Lines 149-160)
* **Defect:** When `RecordingManager.startRecording` is called, it checks a `StateFlow` to see if recording is idle. If the user rapidly double-taps the record button, two intents are fired before the state updates. The first intent acquires the lock and begins recording. The second intent fails to acquire the lock (`RecordingStartResult.AlreadyRecording`) and executes `stopSelf()`. Calling `stopSelf()` immediately destroys the service and tears down the *active* recording started by the first intent.
* **Required Fix:** Do not call `stopSelf()` when `tryStartRecording` returns `AlreadyRecording`. Return and ignore the redundant request.

### **Recording Error Overwrite on Stop**
* **Files:** `ChirpKeyboardService.kt` (Lines 273-285), `VoiceRecorder.kt` (Lines 102-107)
* **Defect:** In `stopAndTranscribe()`, `recordingJob?.cancel()` is called right before `recorder.stop()`. Cancelling the job interrupts the IO thread, while `recorder.stop()` simultaneously releases the `AudioRecord`. This causes `read()` to return `AudioRecord.ERROR_INVALID_OPERATION`, which triggers `onRecordingError`. This abruptly overwrites the keyboard's state from `Transcribing` to `Error` precisely when the user successfully finishes a recording.
* **Required Fix:** Do not trigger `onRecordingError` for `ERROR_INVALID_OPERATION` if `isRecording` has deliberately been set to false by a `stop()` call.

### **Thread Safety / Silent Data Corruption in Map Writes**
* **File:** `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/ui/HomeViewModel.kt` (Lines 111-115)
* **Defect:** `profileCache` (a standard `mutableMapOf`) is written to simultaneously from multiple background coroutines using `.map { async { ... profileCache[id] = ... } }.awaitAll()`. Since standard HashMaps are not thread-safe, concurrent `put` operations from `Dispatchers.IO` threads will cause `ConcurrentModificationException`s or silent internal map corruption.
* **Required Fix:** Change `profileCache` to a `ConcurrentHashMap` or wrap the read/write logic in a Mutex.

---

## 3. Memory Leaks & Native Resource Leaks

### **Native Resource Leak: Unreleased `MediaRecorder`**
* **File:** `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/service/RecordingService.kt` (Lines 184-199)
* **Defect:** The service creates a `MediaRecorder` inside an `apply` block and chains configuration calls. If any method inside the `apply` block throws an exception, the `apply` block exits abruptly, and the instance is *never assigned* to the `mediaRecorder` property. When the catch block calls `stopSelf()` and triggers `onDestroy()`, the `mediaRecorder?.release()` call is a no-op. The native hardware resources are permanently leaked.
* **Required Fix:** Assign the `MediaRecorder` instance to the class property *before* configuring and starting it.

### **Native Resource Leak: Unreleased `MediaPlayer`**
* **File:** `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/audio/AudioPlayer.kt` (Lines 90-133)
* **Defect:** Similar to the `MediaRecorder` leak, `load()` instantiates a `MediaPlayer` inside an `apply` block. If `setDataSource()` throws an exception, the assignment is skipped, and the catch block has no reference to the orphaned `MediaPlayer` instance to call `release()`.
* **Required Fix:** Instantiate and assign the `MediaPlayer` first, then configure it. 

### **InputMethodService Window Leak (Orphaned Compositions)**
* **File:** `feature-keyboard/src/main/java/dev/chirpboard/app/feature/keyboard/service/ChirpKeyboardService.kt` (Lines 183-211)
* **Defect:** `onCreateInputView()` instantiates a new `ComposeView` every time the keyboard configuration changes or is shown. Old `ComposeView` instances are never explicitly disposed, permanently leaking their compositions and their heavily-active flow collectors (`collectAsStateWithLifecycle`).
* **Required Fix:** Override `onFinishInputView()` to call `disposeComposition()` on the active `ComposeView`, or assign an isolated `ViewTreeLifecycleOwner`.

### **Leaked Timeout Jobs**
* **File:** `core/src/main/java/dev/chirpboard/app/core/recording/RecordingStateManager.kt` (Lines 188-229)
* **Defect:** `beginStopRecording` returns a `Job?` that serves as a 5-second timeout. Neither the caller nor `onRecordingCompleted` ever cancels it. Even on successful recording stops, zombie coroutines linger in memory for 5 seconds.
* **Required Fix:** Store the timeout `Job` locally inside `RecordingStateManager` and explicitly `timeoutJob?.cancel()` at the beginning of `onRecordingCompleted()`, `onRecordingError()`, and `forceCancel()`.

### **Leaked Coroutine Scope in Voice Recorder**
* **File:** `feature-keyboard/src/main/java/dev/chirpboard/app/feature/keyboard/recorder/VoiceRecorder.kt` (Line 48)
* **Defect:** Uses a private `CoroutineScope` to run the `stateIn` buffer for amplitude reporting. `VoiceRecorder` lacks a `release()` method, meaning the scope and flow collections will permanently leak if the component is re-instantiated.
* **Required Fix:** Implement `Closeable` on `VoiceRecorder` to cancel its `scope`, and call it from `ChirpKeyboardService.onDestroy()`.

---

## 4. Inefficiencies & Performance Issues

### **Massive Autoboxing GC Churn**
* **Files:** `AudioDecoder.kt` (Lines 476-492), `ChunkedAudioProcessor.kt` (Lines 54-59)
* **Defect:** Audio samples are buffered using `ArrayDeque<Float>`. Calling `buffer.addLast(sample)` forces autoboxing of every single `float` primitive into a `java.lang.Float` object. At 16,000 Hz, a 10-minute recording allocates ~9.6 million objects per buffer pass, causing catastrophic memory pressure and GC pauses.
* **Required Fix:** Replace `ArrayDeque<Float>` with a primitive `FloatArray` structured as a circular ring buffer.

### **Severe GC Pressure from Date Formatters**
* **File:** `core/src/main/java/dev/chirpboard/app/core/util/Extensions.kt` (Lines 39, 40, 48, 75, 81, 82)
* **Defect:** Extension functions like `formatRelative()` and `formatDateTime()` instantiate a new `SimpleDateFormat` and `Calendar` on every call. Used extensively within Compose `LazyColumn`s, they will flood the garbage collector and cause scrolling jank.
* **Required Fix:** Extract `SimpleDateFormat` instances to `ThreadLocal` top-level variables, or migrate entirely to `java.time.format.DateTimeFormatter`.

### **Exponential Time Complexity (O(N²)): String Allocation**
* **File:** `feature-transcription/src/main/java/dev/chirpboard/app/feature/transcription/audio/ChunkedAudioProcessor.kt` (Lines 131-134)
* **Defect:** In `joinTranscripts()`, the loop executes `result.toString().split("\\s+".toRegex()).takeLast(3)` on the entirely accumulated transcript buffer. For a 30-minute recording, this creates massive memory spikes and UI stutter.
* **Required Fix:** Only analyze the last chunk (`parts[i - 1]`) instead of the entire `result` buffer, or maintain a small sliding window.

### **ANR Risk & Main Thread Blocking**
* **File:** `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/service/RecordingStopOrchestrator.kt` (Lines 31-64)
* **Defect:** Wraps database writes and transcription enqueuing inside a `runBlocking(Dispatchers.IO)` block. Because this is invoked by `RecordingService.stopRecording()` on the main thread, the main thread is completely blocked waiting for disk I/O.
* **Required Fix:** Remove `runBlocking`. Refactor `persistAndQueueRecording` into a `suspend` function and launch it within a dedicated `CoroutineScope`.

### **Improper `remember` Usage in Compose**
* **File:** `feature-keyboard/src/main/java/dev/chirpboard/app/feature/keyboard/service/ChirpKeyboardService.kt` (Lines 192-193)
* **Defect:** `val amplitudes = remember(rawAmplitudes) { rawAmplitudes.toImmutableList() }`. Because `rawAmplitudes` is a fresh `List<Float>` emitted ~60 times a second, its reference constantly changes. `remember` invalidates continuously, causing `toImmutableList()` to allocate a new immutable list object 60 times a second.
* **Required Fix:** Convert the amplitudes to an `ImmutableList` upstream inside `VoiceRecorder` before emitting it.

### **Rapid UI State Emissions**
* **File:** `feature-keyboard/src/main/java/dev/chirpboard/app/feature/keyboard/service/ChirpKeyboardService.kt` (Lines 191-204)
* **Defect:** `val rawAmplitudes by recorder.amplitudes.collectAsStateWithLifecycle()` collects a high-frequency flow directly at the root of the `setContent` block. This forces the *entire* `KeyboardUI` hierarchy to fully recompose 60 times a second.
* **Required Fix:** Pass the raw `StateFlow<List<Float>>` down to the `WaveformVisualizer` composable and let it collect the state internally.

### **Atomicity Violation & N+1 Queries**
* **File:** `data/src/main/java/dev/chirpboard/app/data/repository/RecordingRepository.kt` (Lines 172-179)
* **Defect:** Batches IDs into chunks of 100, but wraps *each chunk* in a separate database transaction. It then invokes `recordingDao.deleteById(id)` in a loop, resulting in 100 distinct SQLite queries per chunk.
* **Required Fix:** Wrap the entire `chunked` loop inside a single `database.withTransaction`, and add a `deleteByIds(ids: List<UUID>)` method.

---

## 5. Reliability & Logic Bugs

### **Text Lost on Keyboard Destruction/Rotation**
* **File:** `feature-keyboard/src/main/java/dev/chirpboard/app/feature/keyboard/service/KeyboardTranscriptionPipeline.kt` (Lines 142-156)
* **Defect:** If Android destroys the keyboard service, `ChirpKeyboardService.onDestroy` calls `scope.cancel()`. `KeyboardTranscriptionPipeline` incorrectly catches this via a generic `catch (e: Exception)` block, then invokes `withContext(Dispatchers.Main) { persistBufferedKeyboardCapture(...) }`. Because the parent scope is already cancelled, `withContext` immediately throws a `CancellationException`. The active recorded text is permanently lost.
* **Required Fix:** Catch `CancellationException` explicitly and rethrow it. Wrap persistence logic in `withContext(NonCancellable + Dispatchers.Main)`.

### **Obsidian Sync Failing Silently**
* **File:** `feature-obsidian/src/main/java/dev/chirpboard/app/feature/obsidian/ObsidianManager.kt` (Lines 145-151)
* **Defect:** In the SAF fallback mechanism, if `openOutputStream` returns `null`, the Kotlin `?.use` operator skips execution. The code deletes the temporary file and immediately returns `finalFile.uri` as a success result. The user gets a success callback pointing to a 0-byte file while their data is wiped out.
* **Required Fix:** Remove the safe call `?.use`. Explicitly check if the stream is `null` and throw an `IOException("Failed to open output stream")`.

### **Missing State Persistence (Dead Processing)**
* **File:** `feature-transcription/src/main/java/dev/chirpboard/app/feature/transcription/TranscriptionWorker.kt` (Lines 261-266)
* **Defect:** In `runEnhancementOnly()`, dynamically generated text is passed to the LLM to generate the title and summary, but the newly processed text is *never saved back to the database*.
* **Required Fix:** Add a call to `recordingRepository.updateProcessedText(recordingId, processedText)` right before triggering `applyEnhancement()`.

### **Cascading & Substring Replacement in WordReplacer**
* **Files:** `WordReplacementRepository.kt` (Lines 74-88), `WordReplacer.kt` (Lines 25-28)
* **Defect:** Uses literal `String.replace()` without word boundaries. A rule to replace "he" with "she" turns "hello" into "shello". Furthermore, sequential application means Rule A's output can be mutated by Rule B.
* **Required Fix:** Compile the replacements into a single-pass Regex utilizing word boundaries (`\b`), ensuring special characters are escaped: `Regex("\\b${Regex.escape(rule.original)}\\b")`.

### **Cancellation Triggers Immediate Re-Execution**
* **File:** `feature-transcription/src/main/java/dev/chirpboard/app/feature/transcription/TranscriptionQueueManager.kt` (Lines 338-348)
* **Defect:** When `cancel()` is invoked, the `WorkManager` job is cancelled, but the database status is immediately set back to `RecordingStatus.PENDING_TRANSCRIPTION`. The automated reconciler immediately detects this pending status without a worker and re-enqueues it.
* **Required Fix:** Introduce a formal `CANCELLED` status or retain it in a user-paused state.

### **Contradicting Code: Recording State Desynchronization**
* **File:** `feature-keyboard/src/main/java/dev/chirpboard/app/feature/keyboard/service/KeyboardServiceStartup.kt` (Lines 68-81)
* **Defect:** `observeRecordingState` checks if the local `keyboardState.isKeyboardManagedState()` is active before applying updates from the global `RecordingStateManager`. If the keyboard is transcribing and the global state switches to `Idle` (e.g., recording completed), the flow ignores the global `Idle` emission, leaving them fundamentally desynchronized.
* **Required Fix:** Do not eagerly return out of the `.collect` block when global state transitions to `Idle` or `Error`.

### **Contradicting Code: Resume Offset Math**
* **File:** `core/src/main/java/dev/chirpboard/app/core/recording/RecordingStateManager.kt` (Lines 169-171)
* **Defect:** The comment claims `"We offset the start time backward by accumulatedMs so the timer math works"`. However, the code ignores this entirely and implements `startTimeMs = System.currentTimeMillis()`.
* **Required Fix:** Remove the incorrect comment to prevent future developers from introducing mathematical bugs.