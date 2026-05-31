## 1. Data Model

- [x] 1.1 Add enhancement execution snapshot entity, DAO, and repository models.
- [x] 1.2 Add per-subwork status/error metadata for processing mode, title, and summary.
- [x] 1.3 Add migration from minimal enhancement intent to execution snapshot.
- [x] 1.4 Backfill all `PENDING_ENHANCEMENT` and `ENHANCING` rows, including profileless rows.
- [x] 1.5 Add migration tests for profile-backed, profileless, pending, enhancing, and failed legacy rows.

## 2. Repository and Workers

- [x] 2.1 Add execution token claim methods for transcription and enhancement.
- [x] 2.2 Make transcription result commit conditional on the active transcription execution token.
- [x] 2.3 Make enhancement result commit conditional on the active enhancement execution token and source transcript revision.
- [x] 2.4 Preserve failed enhancement subwork in the snapshot after partial or full failure.
- [x] 2.5 Route failed enhancement retry to enhancement work.
- [x] 2.6 Treat stale worker completion as a logged no-op.

## 3. Queue and UI Contract

- [x] 3.1 Add `recoverPendingEnhancement(recordingId)` to `TranscriptionRecovery`.
- [x] 3.2 Update queue manager and reconciler to use phase-aware retry and recovery.
- [x] 3.3 Show pending enhancement recovery in Home.
- [x] 3.4 Show pending enhancement recovery in Processing Studio.
- [x] 3.5 Keep explicit full retranscription separate from enhancement retry.

## 4. Work Scheduling and Tests

- [x] 4.1 Introduce injectable WorkManager scheduler/gateway for transcription and enhancement work.
- [x] 4.2 Convert work request tests to pure builder and fake scheduler assertions.
- [x] 4.3 Remove static helper name mocks from transcription queue tests.
- [x] 4.4 Add stale transcription commit regression tests.
- [x] 4.5 Add stale enhancement commit regression tests.
- [x] 4.6 Add retry regression tests proving enhancement failure does not rerun transcription.
- [x] 4.7 Add subwork retention tests for partial and full enhancement failure.

## 5. Verification

- [x] 5.1 `openspec validate harden-transcription-enhancement-pipeline --strict`
- [x] 5.2 `./gradlew :data:compileDebugAndroidTestKotlin`
- [x] 5.3 `./gradlew :data:connectedDebugAndroidTest`
- [x] 5.4 `./gradlew :feature-transcription:testDebugUnitTest`
- [x] 5.5 `./gradlew :feature-recording:testDebugUnitTest`
- [x] 5.6 `./gradlew :feature-studio:testDebugUnitTest`
