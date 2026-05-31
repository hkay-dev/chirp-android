# Validation

Use targeted verification. Do not rely on a full test run as the only proof.

## Static Searches

Run these during the audit:

```bash
rg -n "fallbackToDestructiveMigration|fallbackToDestructiveMigrationOnDowngrade"
rg -n "assertTrue\\(true\\)|TODO|FIXME|@Ignore|ignore\\(" --glob '*Test*.kt'
rg -n "GlobalScope|CoroutineScope\\(|launch\\(|async\\(|withTimeout|withTimeoutOrNull|Mutex|synchronized|volatile|Atomic|StateFlow|SharedFlow"
rg -n "RECORDING|STOPPING|PENDING_TRANSCRIPTION|TRANSCRIBING|FAILED|COMPLETED"
rg -n "enqueue|WorkManager|ExistingWorkPolicy|beginUniqueWork|recording_finalize_pipeline|TRANSCRIPTION"
rg -n "InputConnection|commitText|deleteSurroundingText|onStartInput|onFinishInput|onBindInput|onUnbindInput"
rg -n "model|ModelReadiness|Recognizer|Sherpa|Whisper|download|sha256|cache|warmup"
rg -n "Build\\.VERSION|SDK_INT|VERSION_CODES|compileSdk|minSdk|targetSdk|compat|legacy|fallback|Fallback|retry|Retry"
rg -n "collectAsState\\(|collectAsStateWithLifecycle|LaunchedEffect|DisposableEffect|rememberUpdatedState"
```

## Targeted Test Commands

Prefer the narrowest command that proves or disproves a finding:

```bash
./gradlew :core-contracts:testDebugUnitTest
./gradlew :core-audio:testDebugUnitTest
./gradlew :feature-recording:testDebugUnitTest
./gradlew :feature-keyboard:testDebugUnitTest
./gradlew :feature-transcription:testDebugUnitTest
./gradlew :app:testDebugUnitTest
./gradlew :data:compileDebugAndroidTestKotlin
./gradlew :feature-recording:compileDebugAndroidTestKotlin
```

Use `./scripts/run-reliability-matrix.sh` only after scoped fixes or proposal-backed changes are ready for a broader gate.

## APK Build Gate

After every meaningful implementation change, run:

```bash
./gradlew :app:assembleDebug
```

Expected APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

A finding is not resolved until this build succeeds after the fix. If a fix requires several staged edits that are expected to break compilation temporarily, record the exception before starting the sequence, finish the sequence promptly, and run `./gradlew :app:assembleDebug` immediately afterward.

Meaningful implementation changes include Kotlin/Java code, Gradle files, manifests, resources, Room schema or migrations, DI wiring, tests that require production compilation, and generated OpenSpec implementation tasks marked complete. Pure audit report text or proposal-only OpenSpec drafting does not require an APK build, but any proposal marked implemented does.

## Manual Validation To Preserve

These are real done gates for recording reliability:

1. Record 60+ minutes with screen off and stop cleanly.
2. Force-kill mid-recording, relaunch, recover, and verify playable audio plus queued transcription.
3. Pause/resume across at least two segment rotations.
4. Trigger low storage and confirm no silent data loss.
5. Record from keyboard, stop from widget, and verify no global state desync.
6. Widget stop during keyboard recording while IME is unbound, then bind Chirp keyboard and verify pending stop drains.
7. Verify WAV and MP3 paths on at least two OEM/device profiles when audio format behavior changes.

Do not mark those manual risks fully closed from JVM tests alone.
