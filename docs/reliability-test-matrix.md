# Reliability Test Matrix

This matrix maps critical reliability risk classes to automated coverage and execution commands.

## Stage Coverage

| Stage | Risk Class | Automated Coverage | Command |
| --- | --- | --- | --- |
| Recording stop handoff | Duplicate stop signals or lifecycle interruption causes dropped save/queue handoff; Done must navigate immediately to studio stitching UI | `RecordingStopOrchestratorTest`, `StopRequestGateTest`, `RecordingServiceStopRaceTest`, `RecordViewModelTest` | `:feature-recording:testDebugUnitTest`, `:feature-recording:compileDebugAndroidTestKotlin` |
| Queue recovery | Pending work orphaned or stale transcribing/enhancing states not recovered | `TranscriptionQueueReconciliationPolicyTest` | `:feature-transcription:testDebugUnitTest` |
| Transcription result semantics | Engine/model failures treated as successful empty text | `TranscriptionWorkerSupportTest`, `TranscriptionOutcomeMappingTest`, `KeyboardTranscriptionOutcomeMappingTest` | `:feature-transcription:testDebugUnitTest`, `:feature-keyboard:testDebugUnitTest` |
| Recognition persistence integrity | Partial write of recording without transcript | `RecordingRepositoryTransactionTest`, `RecognitionHistoryPersistenceTest` | `:data:compileDebugAndroidTestKotlin`, `:app:testDebugUnitTest` |
| Model artifact integrity | Corrupt or interrupted model downloads accepted as ready | `ModelDownloaderIntegrityTest` | `:app:testDebugUnitTest` |
| Reliability event observability | Missing stage failure visibility or unredacted diagnostics | `ReliabilityEventLoggerTest` | `:core-contracts:testDebugUnitTest` |
| Session journal durability | Interrupted recordings deleted as orphans; stale journals reconciled; abandoned entries pruned | `RecordingSessionJournalTest`, `RecordingSessionReconcilerTest`, `RecordingSessionRecoveryLiveSessionTest`, `RecordingSessionRecoveryKeepSessionTest`, `RecordingSessionJournalCancelOrderingTest`, `OrphanedAudioCleanerTest` | `:feature-recording:testDebugUnitTest` |
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

## Gap closure automated coverage

| Stage | Risk Class | Automated Coverage | Status |
| --- | --- | --- | --- |
| Pending keyboard stop | Widget stop lost when IME unbound | `KeyboardPendingStopStoreTest` | Implemented |
| Cancel ordering | Recovery prompt after cancel + restart | `RecordingSessionJournalCancelOrderingTest` | Implemented |
| Starting-state tags | Tags unavailable at session start | `RecordingStateTest`, `RecordingStateManagerTest` | Implemented |
| Keep files retention | Kept audio deleted before user intent expires | `RecordingSessionRecoveryKeepSessionTest` | Implemented |
| NoAudioFile DB cleanup | Empty stop removes in-progress row | `RecordingServiceStopOutcomesTest` | Implemented |
| Stop timeout vs persist race | Generation guard discards late persist | `RecordingServiceStopOutcomesTest` | Implemented |
| Timeout handler await | Lock released after cleanup completes | `RecordingStateManagerTest.stoppingTimeout_awaitsHandlerBeforeErrorTransition` | Implemented |

## Audit backlog (2026-05-25) — proposed OpenSpec changes

Index: `openspec/changes/AUDIT_INDEX.md`. Each row maps to a change folder with `proposal.md`, `design.md`, `tasks.md`, and spec deltas.

| Priority | Risk class | OpenSpec change | Planned tests (on implement) |
| --- | --- | --- | --- |
| P0 | recoverSession duplicate finalize | `recovery-data-integrity` | `RecordingSessionRecoveryTest` |
| P0 | Studio invalid/missing recording trap | `processing-studio-resilience` | Studio ViewModel/Screen tests |
| P1 | Cancel during Starting | `recording-edge-case-races` | Service cancel+start integration |
| P1 | Keep files DB zombie | `recovery-data-integrity` | keepSession DB terminal status test |
| P2 | Orphan cleaner MP3 gap | `transcription-pipeline-hardening` | `OrphanedAudioCleanerTest` mp3 |
| P2 | Home import → Studio | `processing-studio-resilience` | `HomeViewModelTest` import nav |
| P2 | FAILED Studio duplicate UI | `processing-studio-resilience` | Compose/UI test |
| P2 | TranscriptionWorker active wait | `transcription-pipeline-hardening` | Worker timeout test |
| P2 | Reconciler missing DB row | `recovery-data-integrity` | `RecordingSessionReconcilerTest` |
| P3 | Early Done handoff | `recording-edge-case-races` | `RecordViewModelTest` Starting |
| P3 | Pending stop reconcile mismatch | `recording-edge-case-races` | reconcile integration test |
| P3 | Widget Stopping no-op | `recording-edge-case-races` | Widget receiver test |
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
