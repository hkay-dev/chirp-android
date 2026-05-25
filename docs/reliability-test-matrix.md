# Reliability Test Matrix

This matrix maps critical reliability risk classes to automated coverage and execution commands.

## Stage Coverage

| Stage | Risk Class | Automated Coverage | Command |
| --- | --- | --- | --- |
| Recording stop handoff | Duplicate stop signals or lifecycle interruption causes dropped save/queue handoff; Done must navigate immediately to studio stitching UI | `RecordingStopOrchestratorTest`, `StopRequestGateTest`, `RecordingServiceStopRaceTest`, `RecordViewModelTest` | `:feature-recording:testDebugUnitTest`, `:feature-recording:compileDebugAndroidTestKotlin` |
| Queue recovery | Pending work orphaned or stale transcribing/enhancing states not recovered | `TranscriptionQueueReconciliationPolicyTest` | `:feature-transcription:testDebugUnitTest` |
| Transcription result semantics | Engine/model failures treated as successful empty text | `TranscriptionWorkerSupportTest`, `TranscriptionOutcomeMappingTest`, `KeyboardTranscriptionOutcomeMappingTest` | `:feature-transcription:testDebugUnitTest`, `:feature-keyboard:testDebugUnitTest` |
| Transcription worker active wait | Worker does not block forever when another recording stays active | `TranscriptionWorkerSupportTest` active wait timeout | `:feature-transcription:testDebugUnitTest` |
| Recognition persistence integrity | Partial write of recording without transcript | `RecordingRepositoryTransactionTest`, `RecognitionHistoryPersistenceTest` | `:data:compileDebugAndroidTestKotlin`, `:app:testDebugUnitTest` |
| Model artifact integrity | Corrupt or interrupted model downloads accepted as ready | `ModelDownloaderIntegrityTest` | `:app:testDebugUnitTest` |
| Reliability event observability | Missing stage failure visibility or unredacted diagnostics | `ReliabilityEventLoggerTest` | `:core-contracts:testDebugUnitTest` |
| Session journal durability | Interrupted recordings deleted as orphans; stale journals reconciled; abandoned entries pruned; recover idempotent | `RecordingSessionJournalTest`, `RecordingSessionReconcilerTest`, `RecordingSessionRecoveryTest`, `RecordingSessionRecoveryLiveSessionTest`, `RecordingSessionRecoveryKeepSessionTest`, `RecordingSessionJournalCancelOrderingTest`, `OrphanedAudioCleanerTest` | `:feature-recording:testDebugUnitTest` |
| Orphan cleaner format parity | Unreferenced mp3/wav/m4a orphans deleted; referenced and protected paths retained | `OrphanedAudioCleanerTest` mp3 cases | `:feature-recording:testDebugUnitTest` |
| Recovery deferral persistence | Dismissed recovery prompts do not reappear after process death | `RecordingRecoveryDeferStore` (manual), `RecordingRecoveryStore` integration | `:feature-recording:testDebugUnitTest` |
| Origin-aware stop routing | Widget stop reaches keyboard quick-capture without desyncing global state | `KeyboardRecordingStopBridgeTest`, `KeyboardPendingStopStoreTest` | `:core-contracts:testDebugUnitTest` |
| Stop timeout cleanup | Hung finalize abandons journal/DB row and releases service resources | `RecordingStateManagerTest.stoppingTimeout_awaitsHandlerBeforeErrorTransition`, `RecordingServiceStopOutcomesTest` | `:core-contracts:testDebugUnitTest`, `:feature-recording:testDebugUnitTest` |
| Stop validation | Invalid M4A saved after failed finalize | `RecordingFileValidatorTest`, `RecordingStopOrchestratorTest` | `:feature-recording:testDebugUnitTest` |
| Capture constraints | Storage exhaustion mid-session | `RecordingStorageMonitorTest` | `:core-audio:testDebugUnitTest` |
| Input device policy | Wrong mic route (BT over built-in) | `AudioInputDeviceSelectorTest` | `:core-audio:testDebugUnitTest` |
| Stop timeout scaling | False timeout on long finalize | `RecordingStateManagerTest` | `:core-contracts:testDebugUnitTest` |
| NoAudioFile stop DB hygiene | Empty capture leaves phantom RECORDING row | `RecordingServiceStopOutcomesTest.noAudioFile_deletesInProgressRowAndCompletes` | `:feature-recording:testDebugUnitTest` |
| Stop timeout vs persist race | Late persist after timeout flips Error→Idle or duplicates row | `RecordingServiceStopOutcomesTest.staleGeneration_discardsPersistResultWithoutStateTransition` | `:feature-recording:testDebugUnitTest` |

## Long-session recording soak (manual)

Use this checklist on a physical device (e.g. S25 Ultra) before trusting hour-long sessions:

1. Record 60+ minutes from the app with screen off; verify stop completes without error.
2. Force-kill the app mid-recording; relaunch and confirm recovery dialog appears.
3. Recover the session; verify recording appears in Home and transcription enqueues.
4. Connect USB mic; set Manual input in Audio settings; record 5 minutes and confirm capture works.
5. With Bluetooth headset connected, Automatic policy should prefer built-in mic.
6. Trigger low storage (or use emulator disk quota) and confirm warning/stop before data loss.
7. Dismiss recovery prompt, force-stop app, relaunch — deferred session should not auto-prompt until user opens recovery banner.
8. Record from keyboard, tap widget stop — keyboard should stop/transcribe without global state desync.
9. *(Gap closure)* Widget stop during keyboard recording with Gboard focused — pending stop enqueued; switch to Chirp keyboard — dictation stops and transcribes.
10. *(Gap closure)* Cancel recording then immediately start new one — no recovery prompt for canceled session.
11. *(Gap closure)* Tag picker visible during first second of record screen auto-start; profile default tags already applied.
12. *(Gap closure)* WAV recording transcribes on device — verify direct PCM decode path on 2+ OEMs (Samsung + Pixel or equivalent).

## Gap closure automated coverage

| Stage | Risk Class | Automated Coverage | Status |
| --- | --- | --- | --- |
| Pending keyboard stop | Widget stop lost when IME unbound | `KeyboardPendingStopStoreTest` | Implemented |
| Cancel ordering | Recovery prompt after cancel + restart | `RecordingSessionJournalCancelOrderingTest` | Implemented |
| Starting-state tags | Tags unavailable at session start | `RecordingStateTest`, `RecordingStateManagerTest` | Implemented |
| Cancel during Starting | Pre-journal cancel leaves no recoverable session | `RecordingSessionJournalCancelOrderingTest`, service start guard | Implemented |
| Early Done handoff | Done waits for recording id | `RecordViewModelTest` | Implemented |
| Pending stop reconcile | Stale queue retained during KEYBOARD Stopping | `KeyboardPendingStopStoreTest`, `RecordingStartupCoordinatorTest` | Implemented |
| Widget Stopping tap | Toast feedback, no silent no-op | `WidgetReceiverStoppingTest` | Implemented |
| Keep files retention | Kept audio deleted before user intent expires; in-progress row removed | `RecordingSessionRecoveryKeepSessionTest` | Implemented |
| Recover session idempotency | Duplicate recover does not re-finalize or re-enqueue | `RecordingSessionRecoveryTest` | Implemented |
| Reconciler orphan journal | Missing DB row finalizes stale journal | `RecordingSessionReconcilerTest` | Implemented |
| NoAudioFile DB cleanup | Empty stop removes in-progress row | `RecordingServiceStopOutcomesTest` | Implemented |
| Stop timeout vs persist race | Generation guard discards late persist | `RecordingServiceStopOutcomesTest` | Implemented |
| Timeout handler await | Lock released after cleanup completes | `RecordingStateManagerTest.stoppingTimeout_awaitsHandlerBeforeErrorTransition` | Implemented |
| Studio invalid recording id | Malformed deep link shows barrier, not infinite loading | `ProcessingStudioViewModelTest` invalid id | Implemented |
| Studio missing recording | Null row after grace or delete shows NotFound | `ProcessingStudioViewModelTest` missing/deleted | Implemented |
| Studio FAILED duplicate UI | Single error banner + retry; no recovery duplicate | `ProcessingStudioPresentationTest` | Implemented |
| Home import studio handoff | Import success navigates to Processing Studio | `HomeViewModelTest` import navigates | Implemented |
| Orphan cleaner mp3 coverage | Unreferenced mp3 deleted; referenced/protected retained | `OrphanedAudioCleanerTest` mp3 | Implemented |
| Transcription worker active wait | Bounded wait fails with reliability event | `TranscriptionWorkerSupportTest` timeout | Implemented |

## Audit backlog (2026-05-25) — proposed OpenSpec changes

Index: `openspec/changes/AUDIT_INDEX.md`. Each row maps to a change folder with `proposal.md`, `design.md`, `tasks.md`, and spec deltas.

| Priority | Risk class | OpenSpec change | Planned tests (on implement) |
| --- | --- | --- | --- |
| P3 | Nav/search/mini player polish | `nav-search-playback-polish` | Manual + nav tests |
| P3–P4 | Matrix drift, dead wrappers, coverage gaps | `docs-test-hygiene` | Matrix audit script |

## Unit test standards

- Do not enable `isReturnDefaultValues` in module `build.gradle.kts`; stub Android framework APIs explicitly (for example `mockkStatic(Log::class)` or fakes).
- Prefer behavior assertions over constant-echo tests that only restate production literals.
- Keep trivial wiring tests out of the matrix; each automated row should cover a reliability risk or non-obvious invariant.
- Share JVM Android stubs via the `:test-support` module (`MockAndroidLogRule`) instead of copying helpers per module.

## Matrix Runner

Use `scripts/run-reliability-matrix.sh` to execute the gate locally.

## Known gaps

- Process-death instrumentation during active capture remains optional/local due to emulator flakiness; unit tests cover journal safelist and recovery orchestration instead.
- **Audit backlog (P0–P4):** see § Audit backlog above and `openspec/changes/AUDIT_INDEX.md`. Do not close audit items without archiving the corresponding change.
