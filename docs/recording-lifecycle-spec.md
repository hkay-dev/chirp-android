# Recording Lifecycle Specification

Operational summary for recording, recovery, and stop handoff. **Canonical requirements** live in OpenSpec:

- Baseline: `openspec/specs/recording/spec.md`, `recording-stability/spec.md`, `widget/spec.md`, `keyboard-recording/spec.md`, `tags/spec.md`
- Applied: `openspec/changes/archive/2026-05-25-recording-lifecycle-gap-closure/`
- Applied: `openspec/changes/archive/2026-05-25-stop-persistence-integrity/`
- Applied: `openspec/changes/archive/2026-05-25-recovery-data-integrity/`
- Applied: `openspec/changes/archive/2026-05-25-recording-edge-case-races/`
- Applied: `openspec/changes/archive/2026-05-25-transcription-pipeline-hardening/`
- Applied: `openspec/changes/archive/2026-05-25-nav-search-playback-polish/`
- Applied: `openspec/changes/archive/2026-05-25-decouple-capture-finalize-queue/`
- Applied: `openspec/changes/live-capture-home-presence/`
- **Active audit fixes:** `openspec/changes/AUDIT_INDEX.md` (remaining proposed changes)

When editing behavior, update the OpenSpec change or baseline first, then mirror here.

## State machine

Global coordination lives in `RecordingStateManager` (`core-contracts`):

```
Idle → Starting → Recording ⇄ Paused
  → [capture stop handoff] → Idle   (lock released; DB row stays RECORDING until finalize worker)
  ↘ Error → Idle
```

`Stopping` remains for keyboard-origin inline stop UX only. APP/WIDGET service stops do **not** hold the lock through stitch.

Only one **capture** session may hold the lock at a time (`APP`, `WIDGET`, `KEYBOARD`).

## Processing phases on Home

| DB status / capture | Visible on Home | Banner |
|---------------------|-----------------|--------|
| `RECORDING` (live APP capture) | Yes | Recording (pulsing red; tap returns to RecordScreen) |
| `RECORDING` (background finalize) | Yes | Stitching your recording together |
| `PENDING_TRANSCRIPTION` / `PENDING_ENHANCEMENT` | Yes | Waiting in line |
| `TRANSCRIBING` / `ENHANCING` | Yes | Transcribing / Enhancing |

### Browse home while recording

1. User taps back/close on RecordScreen during active or paused capture.
2. Dialog offers **Save**, **Discard**, or **Browse home**.
3. Browse home pops to Home; mic capture continues.
4. Home shows the live capture row; tap or record FAB returns to RecordScreen (`autoStart=false`).

## Capture backends

| Origin | Capture | Session journal | In-progress DB row |
|--------|---------|-----------------|---------------------|
| APP | `RecordingService` | Yes | Yes |
| WIDGET | `RecordingService` | Yes | Yes |
| KEYBOARD | `QuickCaptureSessionImpl` | No | No (inline samples) |

## Start

1. UI calls `RecordingManager.startRecording()` or widget/keyboard equivalent.
2. `RecordingStateManager.tryStartRecording()` acquires the lock.
3. Service path: reconcile stale journals → create in-progress row → `sessionJournal.createSession(ACTIVE)`.
4. Record screen auto-starts only when **actionable** recoverable sessions are empty (pending minus user-deferred).

## Stop

1. Service stops the mic (`stopAndFinalize` on capture engine), marks session journal `STOPPING`, enqueues `RecordingFinalizeWorker` on the serial WorkManager pipeline (`recording_finalize_pipeline`), and calls `onCaptureStopHandoff(recordingId)` to release the global lock immediately.
2. **Done handoff:** RecordScreen navigates immediately using `activeRecordingId` so Processing Studio and Home show Stitching while the finalize worker runs.
3. Background finalize (`RecordingFinalizeWorker`): concat segments → validate → `finalizeInProgressRecording` → transcription enqueue via `RecordingStopOrchestrator`.
4. Success: journal `markFinalized()`; row becomes `PENDING_TRANSCRIPTION` (Waiting in line on Home).
5. Failure: journal abandon + delete in-progress row; reliability event only (no global Error if user already started a new capture).
6. Startup: `RecordingFinalizeStartupReconciler` re-enqueues finalize work for `STOPPING` journals with in-progress DB rows after process death.

### Origin-aware stop routing

Surfaces that stop “whatever is recording” must use `RecordingActiveStopCommands.stopActiveRecording()`:

- **APP / WIDGET** → `RecordingServiceCommands.stopRecording()`
- **KEYBOARD** → `KeyboardRecordingStopBridge.requestStop()` when IME handler is registered
- **KEYBOARD (IME unbound)** → `KeyboardPendingStopStore.enqueue()` + user toast; drain on `ChirpKeyboardService` bind (no `forceCancel()`)

## Recovery

### Scan rules

A session is recoverable when:

1. Journal state is `ACTIVE` or `STOPPING`
2. Resolved audio ≥ 512 bytes
3. Linked recording is still `RECORDING` in DB, **or** no finalized row exists yet
4. Session is **not** the live active recording (`recordingId` ≠ `RecordingState.activeRecordingId`)

Reconciler removes journals whose linked recording already left `RECORDING` **or whose linked recording row no longer exists**.

### User actions

| Action | Journal | DB row | Re-prompt |
|--------|---------|--------|-----------|
| Recover | `markFinalized` | Finalized | No |
| Discard | `markFinalized` | Deleted | No |
| Keep files | Journal removed; paths protected 7 days | In-progress row deleted | No |
| Defer (dismiss) | Unchanged | Unchanged | No until pending clears; persisted in DataStore |

Deferred session IDs persist in `recording_recovery` preferences and survive process death.

### Maintenance

- On app start: prune `ABANDONED` journals older than 30 days, refresh recovery store, run orphan audio cleaner.
- Abandoned journal pruning allows orphan cleaner to reclaim audio referenced only by stale entries.

## In-recording tags

Tags attach to the in-progress `recordingId` as soon as `RecordingStateManager.onRecordingIdAssigned()` runs (including during `Starting`). Profile default tags apply once per session. Toggle/create writes immediately via `TagRepository`.

## UI conventions

- Record screen **Close (X)** and system **Back** both offer save or discard while recording.
- Recovery dialog uses shared `RecordingRecoveryStore.deferSession()` (not per-screen `remember` state).

## Test coverage

See `docs/reliability-test-matrix.md` for automated commands. Key suites:

- `RecordingSessionRecoveryTest` — recover idempotency and missing-row guard
- `RecordingSessionRecoveryLiveSessionTest` — live session exclusion
- `RecordingSessionJournalTest` — journal lifecycle + prune
- `KeyboardRecordingStopBridgeTest` — widget/keyboard stop bridge
- `KeyboardPendingStopStoreTest` — durable keyboard stop queue
- `RecordingSessionRecoveryKeepSessionTest` — keep files protected-path TTL
- `RecordingStateManagerTest` — stopping timeout scaling and handler await ordering
- `RecordingServiceStopOutcomesTest` — NoAudioFile row delete and stop-generation guard

## Resolved (recording-lifecycle-gap-closure)

| Gap | Resolution |
|-----|------------|
| Widget keyboard stop when IME not bound | `KeyboardPendingStopStore` + drain on keyboard bind |
| Cancel releases lock before IO cleanup | Abandon journal → IO cleanup → `forceCancel()` |
| Tags hidden during `Starting` | `recordingId` on `Starting` via `onRecordingIdAssigned()` |
| Keep files journal/audio retention | Protected-path TTL (7 days) + immediate journal removal |
| Keyboard stopping timeout | Per-origin handlers in service + `KeyboardSessionCoordinator` |

## Resolved (stop-persistence-integrity)

| Gap | Resolution |
|-----|------------|
| `NoAudioFile` stop deletes in-progress row | `RecordingServiceStopOutcomeApplier` + journal abandon before `onRecordingCompleted()` |
| Stop timeout vs persist race | `stopGeneration` guard discards late persist; timeout increments generation first |
| Timeout handler cleanup ordering | Suspend handlers awaited before Error transition and lock release |

## Resolved (recovery-data-integrity)

| Gap | Resolution |
|-----|------------|
| `recoverSession()` re-finalizes completed rows / duplicate `createRecording` | Pre-flight status guard; idempotent `Recovered` without re-enqueue |
| Keep files leaves permanent `RECORDING` DB row | `keepSession()` deletes in-progress row after protecting paths |
| Reconciler skips journal when DB row missing | Reconciler finalizes orphan journals and deletes capture artifacts |
| `RecordingRecoveryDeferStore` fire-and-forget persist (minor) | Spec requirement documented; await persist deferred to follow-up |

## Resolved (processing-studio-resilience)

| Gap | Resolution |
|-----|------------|
| Studio invalid UUID → spinner with no back | `ProcessingStudioLoadState.InvalidId` + barrier screen with back |
| Studio missing/deleted recording → eternal skeleton | Grace-period / delete detection → `NotFound` state; observation cancelled |
| Home import does not open Studio | `HomeViewModel.openStudioForRecordingId` + Home navigation handoff |
| FAILED Studio duplicate error + recovery blocks | `studioFailurePresentation` consolidates retry on error banner |

## Resolved (recording-edge-case-races)

| Gap | Resolution |
|-----|------------|
| Cancel during `Starting` before journal exists | Start-generation guard + mutex; abandon journal only when file exists; delete in-progress row and capture artifacts |
| Done before `onRecordingIdAssigned` | Done disabled until `activeRecordingId` assigned; snackbar fallback if tapped early |
| Pending keyboard stop reconcile mismatch | `KeyboardPendingStopStore.reconcileStale(RecordingState)` retains KEYBOARD Starting/Recording/Paused/Stopping |
| Widget tap during `Stopping` is no-op | Widget shows "Saving…"; tap shows "Finishing current recording" toast |

## Resolved (transcription-pipeline-hardening)

| Gap | Resolution |
|-----|------------|
| Orphan cleaner skips `.mp3` | `OrphanedAudioCleaner` scans `m4a`, `wav`, `mp3` with same grace/safelist/protected-path rules |
| TranscriptionWorker unbounded active wait | `awaitRecordingInactive` with duration-scaled timeout; `active_recording_wait_timeout` reliability event |
| WAV MediaCodec device risk | `AudioDecoder.decodeWavPcmDirect` bypasses MediaCodec for valid PCM WAV; MediaCodec failure retries direct read |

## Resolved (nav-search-playback-polish)

| Gap | Resolution |
|-----|------------|
| Home list item → Studio without `launchSingleTop` | `NavController.navigateToStudio` with `launchSingleTop` + `restoreState` from Home, search, import, share, mini player |
| Search may show `RECORDING` rows list hides | `RecordingDao.searchRecordings` excludes `RECORDING`; `HomeViewModel` defense filter |
| Mini player keeps playing when opening different Studio | `ProcessingStudioViewModel.loadRecording` calls `pauseIfDifferentRecording` |
| Checklist: keyboard mode row expand vs crossfade | `openspec/UI_POLISH_QA_CHECKLIST.md` M1 |
| Checklist: player pushes content not tab row | `openspec/UI_POLISH_QA_CHECKLIST.md` M2 |

## Resolved (docs-test-hygiene)

| Gap | Resolution |
|-----|------------|
| Reliability matrix cites wrong tests for some rows | Matrix rows corrected (`InlineTranscriptionOutcomeMappingTest`, `RecordingRecoveryDeferStoreTest`); gapless and outputFormat rows added |
| Unused `TranscriptionProgressPanel` wrappers | Removed `feature-studio/.../TranscriptionProgressUi.kt`; call sites import `core-ui` directly |
| Gapless capture untested | `GaplessSegmentCaptureFactoryTest`, `GaplessWavSegmentCaptureTest` |
| Migration tests omit structured_outcome DAO open | `MigrationTest.createDb` opens `structuredOutcomeSnapshotDao()` |
| `AudioSettingsStore` outputFormat not written on legacy migration | Backfill on read when key missing; `AudioSettingsStoreTest` legacy scenario |

## Audit backlog (2026-05-25)

Findings from multi-agent audit. **Canonical fix specs:** `openspec/changes/AUDIT_INDEX.md` and per-change folders below. Do not implement fixes without an OpenSpec change.

### P0 — Data loss / corruption / trap

_(none — see Resolved processing-studio-resilience)_

### P1 — High-risk lifecycle / hidden state

_(none — see Resolved recording-edge-case-races)_

### P2 — UX / cleanup gaps

_(none — see Resolved transcription-pipeline-hardening)_

### P3 — Edge cases / polish risks

_(none — see Resolved docs-test-hygiene)_

### P4 — Low / test / device / checklist

_(none — see Resolved docs-test-hygiene)_
