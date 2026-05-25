> **Archive note:** Historical paths below reference the pre-split `:core` module. See [`PATH_REMAPPING.md`](PATH_REMAPPING.md) for current locations.
>

# CODEBASE_REVIEW_REPORT_V3.md

## Overview
This report documents high-priority technical debt identified during the third deep scan of the codebase. Focus is on architectural boundaries, contract coherence, and dependency health.

## High-Priority Issues

### 1. Boilerplate and Boilerplate Documentation
*   **Issue:** Repository classes and Work-request helpers are heavily padded with comment-heavy pass-through CRUD wrappers and redundant KDoc.
*   **Action:** Delete restating KDoc. Trim repository methods that add no policy or orchestration. Compress KDoc on one-line helpers where method names already explain intent.
*   **Files:** `data/src/main/java/dev/chirpboard/app/data/repository/ProfileRepository.kt`, `TagRepository.kt`, `RecordingRepository.kt`, `WordReplacementRepository.kt`, `feature-transcription/src/main/java/dev/chirpboard/app/feature/transcription/TranscriptionWorkRequest.kt`.

### 2. Authorization Guard Fragmentation
*   **Issue:** Recording entry points do not enforce `RECORD_AUDIO` consistently. Permission logic is split and duplicated across the recording boundary.
*   **Action:** Implement a shared guard at the recording boundary. Use this from `RecordingService`, `VoiceRecognitionActivity`, and `ChirpRecognitionService`.
*   **Files:** `feature-keyboard/.../ChirpKeyboardService.kt`, `feature-recording/.../RecordingService.kt`, `app/.../VoiceRecognitionActivity.kt`, `app/.../ChirpRecognitionService.kt`.

### 3. Contractual Incoherence
*   **Transcription UUID:** `TranscriptionWorkRequest.enqueue()` returns a UUID for a request that WorkManager may never schedule if `ExistingWorkPolicy.KEEP` is active. Change the contract to return the work name.
*   **Files:** `feature-transcription/.../TranscriptionWorkRequest.kt`.
*   **Filesystem Mutations:** `getModelDir()` helpers mutate storage and choose fallback paths while named as pure accessors. Rename to `ensureModelDir()` or split resolution from creation.
*   **Files:** `app/.../download/ModelDownloader.kt`, `feature-transcription/.../WhisperModelManager.kt`.
*   **LlmProcessor Failures:** `LlmProcessor` flattens both success and generation failure into `null`. Return a typed `Result` or `ProcessingResult` that separates status.
*   **Files:** `feature-llm/.../LlmProcessor.kt`, `feature-transcription/.../TranscriptionWorker.kt`.
*   **Model Readiness:** The app tracks model readiness in two incompatible ways (downloader-backed vs. `WhisperModelManager`-backed). Unified on one shared readiness service.
*   **Files:** `ModelDownloader.kt`, `WhisperModelManager.kt`, `TranscriptionSettingsViewModel.kt`, `KeyboardModule.kt`.

### 4. Cross-Module Architecture Debt
*   **Feature Coupling:** Feature modules depend directly on each other's concrete services (e.g., `feature-recording` depends on `feature-transcription` and `feature-llm`), violating module boundaries.
*   **Action:** Define narrow contracts in `core` or shared modules. Depend on contracts rather than concrete implementations.
*   **Files:** `feature-recording/build.gradle.kts`, `feature-recording/ui/HomeViewModel.kt`, `feature-recording/service/RecordingService.kt`, `feature-widget/.../WidgetReceiver.kt`, `feature-keyboard/.../KeyboardTranscriptionPipeline.kt`.
*   **Hub Module Overload:** `feature-recording` has become a change hub (8,339 lines), mixing unrelated recording, profile, tag, and LLM reach-through logic.
*   **Action:** Split by ownership seams. Move tag/profile management to data surfaces.
*   **Files:** `feature-recording/.../HomeViewModel.kt`, `feature-recording/.../RecordingService.kt`.

### 5. Dependency Health
*   **Parallel LLM Stacks:** A legacy LLM stack in `app/llm` persists alongside the new `feature-llm` stack.
*   **Action:** Collapse onto the `feature-llm` path. Adapt/inject `feature-llm` services into current consumers and remove the `app.llm` package.
*   **Files:** `app/build.gradle.kts`, `app/src/main/java/dev/chirpboard/app/VoiceRecognitionActivity.kt`, `app/llm/TextProcessor.kt`.
