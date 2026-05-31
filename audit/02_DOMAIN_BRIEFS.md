# Domain Briefs

Use these as sub-agent briefs. Every domain agent must look for both reliability bugs and simplification opportunities. A valid fix can be deletion, consolidation, or making one source of truth explicit.

## Recording Lifecycle And Stop Handoff

Key files:

- `core-contracts/src/main/java/dev/chirpboard/app/core/recording/RecordingState.kt`
- `core-contracts/src/main/java/dev/chirpboard/app/core/recording/RecordingStateManager.kt`
- `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/RecordingManager.kt`
- `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/service/RecordingService.kt`
- `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/service/StopRequestGate.kt`
- `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/service/RecordingStopOrchestrator.kt`
- `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/service/RecordingServiceStopOutcomes.kt`
- `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/service/RecordingFinalizeStopOutcomeApplier.kt`
- `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/service/RecordingFinalizeWorker.kt`

Look for duplicate stops, state transitions after timeout, lock release ordering, failure paths that leave `RECORDING` rows, and anything that violates immediate capture stop handoff. Prefer one clearly owned stop path over stacked fallback stop handlers.

## Recovery, Journals, Orphans, And DB Consistency

Key files:

- `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/session/RecordingSessionJournal.kt`
- `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/session/SessionRecoveryAssessor.kt`
- `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/session/RecordingSessionRecovery.kt`
- `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/session/RecordingSessionReconciler.kt`
- `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/session/RecordingStartupCoordinator.kt`
- `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/session/RecordingFinalizeStartupReconciler.kt`
- `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/session/RecordingRecoveryStore.kt`
- `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/session/RecordingRecoveryDeferStore.kt`
- `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/session/RecordingRecoveryProtectedPathsStore.kt`
- `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/cleanup/OrphanedAudioCleaner.kt`
- `data/src/main/java/dev/chirpboard/app/data/repository/RecordingRepository.kt`
- `data/src/main/java/dev/chirpboard/app/data/dao/RecordingDao.kt`

Look for journal/file/DB disagreement, non-atomic updates, missing idempotency, stale deferred recovery, protected path TTL errors, and orphan cleanup deleting valid audio. Be conservative about adding recovery branches; first check whether one canonical reconciliation path can own the case.

## Long Recording And Segment Capture

Key files:

- `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/service/GaplessSegmentCapture.kt`
- `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/service/GaplessSegmentCaptureEngine.kt`
- `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/service/GaplessSegmentCaptureFactory.kt`
- `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/service/GaplessMp3SegmentCapture.kt`
- `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/service/GaplessWavSegmentCapture.kt`
- `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/service/RecordingSegmentRotator.kt`
- `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/service/RecordingSegmentConcatenator.kt`
- `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/service/RecordingSegmentFinalize.kt`
- `core-audio/src/main/java/dev/chirpboard/app/core/audio/recorder/VoiceRecorder.kt`
- `core-audio/src/main/java/dev/chirpboard/app/core/audio/RecordingStorageMonitor.kt`

Look for segment loss, bad format assumptions, long finalize timeout mistakes, pause/resume gaps, storage exhaustion, temp file cleanup races, and any case where internal multipart capture leaks into user-visible behavior. Keep the user-facing model one recording, one timer, one output file.

## Model Loading, Readiness, And Transcription Timing

Key files:

- `app/src/main/java/dev/chirpboard/app/download/ModelReadinessGate.kt`
- `app/src/main/java/dev/chirpboard/app/download/ModelDownloader.kt`
- `app/src/main/java/dev/chirpboard/app/RecognizerManager.kt`
- `app/src/main/java/dev/chirpboard/app/SherpaRecognizer.kt`
- `feature-transcription/src/main/java/dev/chirpboard/app/feature/transcription/WhisperModelManager.kt`
- `feature-transcription/src/main/java/dev/chirpboard/app/feature/transcription/TranscriptionWorker.kt`
- `feature-transcription/src/main/java/dev/chirpboard/app/feature/transcription/TranscriptionWorkerSupport.kt`
- `feature-transcription/src/main/java/dev/chirpboard/app/feature/transcription/audio/ChunkedAudioProcessor.kt`
- `feature-transcription/src/main/java/dev/chirpboard/app/feature/transcription/TranscriptionQueueManager.kt`
- `feature-transcription/src/main/java/dev/chirpboard/app/feature/transcription/TranscriptionQueueReconciler.kt`

Look for false readiness, corrupt models accepted, readiness cache staleness, concurrent recognizer construction, workers racing active recordings, failed model outcomes treated as empty text, and unbounded model load timing. Prefer singleflight, explicit state, and bounded invalidation over parallel fallback model-loading paths.

## Keyboard IME And Input

Key files:

- `feature-keyboard/src/main/java/dev/chirpboard/app/feature/keyboard/service/ChirpKeyboardService.kt`
- `feature-keyboard/src/main/java/dev/chirpboard/app/feature/keyboard/service/KeyboardInputConnectionActions.kt`
- `feature-keyboard/src/main/java/dev/chirpboard/app/feature/keyboard/session/KeyboardSessionCoordinator.kt`
- `feature-keyboard/src/main/java/dev/chirpboard/app/feature/keyboard/session/KeyboardUiState.kt`
- `feature-keyboard/src/main/java/dev/chirpboard/app/feature/keyboard/quickcapture/QuickCaptureSessionImpl.kt`
- `feature-keyboard/src/main/java/dev/chirpboard/app/feature/keyboard/session/KeyboardInlineCapturePersistence.kt`
- `core-contracts/src/main/java/dev/chirpboard/app/core/recording/KeyboardRecordingStopBridge.kt`
- `core-contracts/src/main/java/dev/chirpboard/app/core/recording/KeyboardPendingStopStore.kt`
- `core-contracts/src/main/java/dev/chirpboard/app/core/quickcapture/QuickCaptureContracts.kt`
- `core-contracts/src/main/java/dev/chirpboard/app/core/transcription/InlineTranscriptionContracts.kt`

Look for invalid input connections, text insertion after focus changes, lost pending stops when IME unbinds, quick-capture state desync, keyboard/app/widget stop routing errors, and dictated text loss. Prefer refusing to insert stale text with a recoverable UI state over guessing the user's target field.

## Over-Engineering And Module Boundaries

Key files:

- `settings.gradle.kts`
- `build.gradle.kts`
- Module `build.gradle.kts` files.
- `core-contracts/**`
- `feature-recording/**/di/**`
- `feature-transcription/**/di/**`
- `feature-keyboard/**/di/**`
- `app/src/main/java/dev/chirpboard/app/di/**`
- Active OpenSpec changes for module cleanup.

Look for facades, duplicate abstractions, unnecessary dependency inversion, contracts with one implementation and no stability benefit, stale wrappers, fallback APIs without callers, or module edges that make lifecycle state harder to reason about.

## Test Fidelity

Key files:

- `docs/reliability-test-matrix.md`
- `scripts/run-reliability-matrix.sh`
- All `*Test.kt`
- All `androidTest/**`
- All `detekt-baseline.xml`

Searches:

- `rg -n "assertTrue\\(true\\)|TODO|FIXME|ignore|@Ignore|isReturnDefaultValues|relaxed = true|coEvery|returns Unit" --glob '*Test*.kt'`
- `rg -n "fallbackToDestructiveMigration|fallbackToDestructiveMigrationOnDowngrade"`

Look for tests that mirror constants, overuse relaxed mocks, miss ordering assertions, skip process-death or long-session behavior, or claim matrix coverage without validating the risky path.

## Android 16 Platform And Modern Android Pitfalls

Key files:

- `audit/08_ANDROID_BEST_PRACTICES.md`
- `app/build.gradle.kts`
- All module `build.gradle.kts` files.
- `app/src/main/AndroidManifest.xml`
- Feature manifests.
- `MainActivity.kt`
- `ChirpKeyboardService.kt`
- `RecordingService.kt`
- `RecordingFinalizeWorker.kt`
- Compose screens and ViewModels.

Look for old-SDK compatibility work that is no longer needed, Gradle SDK drift, Android 16 behavior changes, foreground-service timeout handling, predictive back gaps, edge-to-edge/insets issues, orientation/resizability assumptions, ScheduledExecutor fixed-rate assumptions, unsafe intent exposure, lifecycle-unaware Flow collection, ViewModels holding Android lifecycle objects, WorkManager misuse, and IME lifecycle mistakes. Android 16-only targeting means legacy compatibility code needs proof, not inertia.
