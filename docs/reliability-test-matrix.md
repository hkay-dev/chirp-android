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

## Matrix Runner

Use `scripts/run-reliability-matrix.sh` to execute the gate locally.

## Known Gap

- Dedicated instrumentation coverage for service teardown race under process death is still pending and tracked in OpenSpec (`add-reliability-regression-test-matrix`, task 2.1).
