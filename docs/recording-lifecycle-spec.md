# Recording Lifecycle Specification

Operational summary for recording, recovery, and stop handoff. **Canonical requirements** live in OpenSpec:

- Baseline: `openspec/specs/recording/spec.md`, `recording-stability/spec.md`, `widget/spec.md`, `keyboard-recording/spec.md`, `tags/spec.md`
- Applied: `openspec/changes/archive/2026-05-25-recording-lifecycle-gap-closure/`
- Applied: `openspec/changes/archive/2026-05-25-stop-persistence-integrity/`
- Applied: `openspec/changes/archive/2026-05-25-recovery-data-integrity/`
- **Active audit fixes:** `openspec/changes/AUDIT_INDEX.md` (remaining proposed changes)

When editing behavior, update the OpenSpec change or baseline first, then mirror here.

## State machine

Global coordination lives in `RecordingStateManager` (`core-contracts`):

```
Idle → Starting → Recording ⇄ Paused → Stopping → Idle
                                      ↘ Error → Idle
```

Only one recording origin may hold the lock at a time (`APP`, `WIDGET`, `KEYBOARD`).

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

1. `transitionToStopping()` then finalize capture → `RecordingStopOrchestrator.persistAndQueueRecording()`.
2. Success: `markFinalized()` deletes journal → `onRecordingCompleted(recordingId)` → UI navigates to Processing Studio.
3. **Done handoff:** On Done (or save-from-back), RecordScreen navigates **immediately** using `activeRecordingId` so Processing Studio shows the “Stitching” finalizing state while the service completes stop. `lastCompletedRecordingId` is fallback-only (deduped via `hasNavigatedToComplete`).
4. Failure: `markAbandoned()` + delete in-progress row + `onRecordingError()`.
5. Stopping timeout (service-owned): abandon journal, delete in-progress row, invalidate in-flight stop job, `finishStopLifecycle()`.

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

## Audit backlog (2026-05-25)

Findings from multi-agent audit. **Canonical fix specs:** `openspec/changes/AUDIT_INDEX.md` and per-change folders below. Do not implement fixes without an OpenSpec change.

### P0 — Data loss / corruption / trap

_(none — see Resolved processing-studio-resilience)_

### P1 — High-risk lifecycle / hidden state

| Gap | Change |
|-----|--------|
| Cancel during `Starting` before journal exists | `recording-edge-case-races` |

### P2 — UX / cleanup gaps

| Gap | Change |
|-----|--------|
| Orphan cleaner skips `.mp3` | `transcription-pipeline-hardening` |
| TranscriptionWorker waits forever on stuck active state | `transcription-pipeline-hardening` |

### P3 — Edge cases / polish risks

| Gap | Change |
|-----|--------|
| Done before `onRecordingIdAssigned` (handoff fallback only) | `recording-edge-case-races` |
| Pending keyboard stop reconcile signal mismatch | `recording-edge-case-races` |
| Widget tap during `Stopping` is no-op | `recording-edge-case-races` |
| Home list item → Studio without `launchSingleTop` | `nav-search-playback-polish` |
| Search may show `RECORDING` rows list hides | `nav-search-playback-polish` |
| Mini player keeps playing when opening different Studio | `nav-search-playback-polish` |
| Reliability matrix cites wrong tests for some rows | `docs-test-hygiene` |
| Unused `TranscriptionProgressPanel` wrappers | `docs-test-hygiene` |

### P4 — Low / test / device / checklist

| Gap | Change |
|-----|--------|
| Checklist: keyboard mode row expand vs crossfade | `nav-search-playback-polish` |
| Checklist: player pushes content not tab row | `nav-search-playback-polish` |
| WAV transcription depends on MediaCodec path | `transcription-pipeline-hardening` |
| Gapless capture / protected-path store untested | `docs-test-hygiene` |
| Migration tests omit structured_outcome DAO open | `docs-test-hygiene` |
| `AudioSettingsStore` outputFormat not written on legacy migration | `docs-test-hygiene` |
