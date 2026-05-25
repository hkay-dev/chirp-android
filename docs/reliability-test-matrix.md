# Reliability Test Matrix

This matrix maps critical reliability risk classes to automated coverage and execution commands.

## Stage Coverage

| Stage | Risk Class | Automated Coverage | Command |
| --- | --- | --- | --- |
| Recording stop handoff | Duplicate stop signals or lifecycle interruption causes dropped save/queue handoff | `RecordingService` stop orchestration hardening compile path | `:feature-recording:compileDebugKotlin` |
| Queue recovery | Pending work orphaned or stale transcribing/enhancing states not recovered | `TranscriptionQueueReconciliationPolicyTest` | `:feature-transcription:testDebugUnitTest` |
| Transcription result semantics | Engine/model failures treated as successful empty text | `TranscriptionOutcomeMappingTest`, `KeyboardTranscriptionOutcomeMappingTest` | `:feature-transcription:testDebugUnitTest`, `:feature-keyboard:testDebugUnitTest` |
| Recognition persistence integrity | Partial write of recording without transcript | `RecordingRepositoryTransactionTest`, `RecognitionHistoryPersistenceTest` | `:data:compileDebugAndroidTestKotlin`, `:app:testDebugUnitTest` |
| Model artifact integrity | Corrupt or interrupted model downloads accepted as ready | `ModelDownloaderIntegrityTest` | `:app:testDebugUnitTest` |
| Reliability event observability | Missing stage failure visibility or unredacted diagnostics | `ReliabilityEventLoggerTest` | `:core:testDebugUnitTest` |
| Session journal durability | Interrupted recordings deleted as orphans | `RecordingSessionJournalTest`, `OrphanedAudioCleanerTest` | `:feature-recording:testDebugUnitTest` |
| Stop validation | Invalid M4A saved after failed finalize | `RecordingFileValidatorTest`, `RecordingStopOrchestratorTest` | `:feature-recording:testDebugUnitTest` |
| Capture constraints | Storage exhaustion mid-session | `RecordingStorageMonitorTest` | `:core:testDebugUnitTest` |
| Input device policy | Wrong mic route (BT over built-in) | `AudioInputDeviceSelectorTest` | `:core:testDebugUnitTest` |
| Stop timeout scaling | False timeout on long finalize | `RecordingStateManagerTest` | `:core:testDebugUnitTest` |

## Long-session recording soak (manual)

Use this checklist on a physical device (e.g. S25 Ultra) before trusting hour-long sessions:

1. Record 60+ minutes from the app with screen off; verify stop completes without error.
2. Force-kill the app mid-recording; relaunch and confirm recovery dialog appears.
3. Recover the session; verify recording appears in Home and transcription enqueues.
4. Connect USB mic; set Manual input in Audio settings; record 5 minutes and confirm capture works.
5. With Bluetooth headset connected, Automatic policy should prefer built-in mic.
6. Trigger low storage (or use emulator disk quota) and confirm warning/stop before data loss.

## Matrix Runner

Use `scripts/run-reliability-matrix.sh` to execute the gate locally.

## Known Gap

- Process-death instrumentation during active capture remains optional/local due to emulator flakiness; unit tests cover journal safelist and recovery orchestration instead.
