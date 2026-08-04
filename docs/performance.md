# Performance and model residency

## Release profiling

The `baseline-profile` Android test module owns two connected-device tools.

- `BaselineProfileGenerator` collects app cold start, Chirp Voice IME startup, and keyboard
  settings navigation. Its small editor host exists only in the test APK.
- `StartupBenchmark` compares cold startup with no compilation against the packaged Baseline
  Profile.

The app applies the AndroidX Baseline Profile plugin, consumes `project(":baseline-profile")`, and
ships ProfileInstaller 1.4.1 for the sideloaded release path. R8 rewrites the generated rules for
the minified release build.

Generate the committed profile on the connected Android 16 S25 with the display unlocked.

```bash
./gradlew :app:generateReleaseBaselineProfile
```

The generator temporarily selects Chirp Voice to exercise real IME creation, restores the prior
default IME in a `finally` block, and never taps the microphone. Generated rules are copied to
`app/src/release/generated/baselineProfiles/`. Verify the packaged binary after a release build.

```bash
./gradlew :app:assembleRelease
unzip -l app/build/outputs/apk/release/app-release.apk | grep assets/dexopt
```

Run the comparison benchmarks separately on an unlocked, idle phone.

```bash
./gradlew :baseline-profile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.chirpboard.app.baselineprofile.StartupBenchmark
```

## Local recognizer residency

The process-wide recognizer follows these rules.

1. The selected downloaded model prewarms shortly after process startup.
2. A loaded model stays resident across completed dictations, IME hides, app switches, and idle
   time. There is no idle-release timer.
3. Active recording, an in-flight recognizer lease, and queued local transcription block pressure
   release.
4. Confirmed low-memory signals and severe-or-higher thermal status may release an unused model.
5. The next startup, IME bind, or transcription request loads a model again if pressure freed it.

Warmup loads and verifies model resources only. Audio capture still begins solely from an explicit
recording action, so residency never opens or reserves the microphone.

## GGUF continuous decode ceiling

Parakeet CTC 110M Q8 receives one continuous 16 kHz PCM buffer for recordings up to five minutes.
Longer recordings take the preserved-audio recovery path with 30-second chunks and two-second
overlap. The ceiling is a memory-safety gate based on the Galaxy S25 Ultra stress run. A 32:52
whole-file call grew to about 5 GB RSS and Android killed the process, while the bounded recovery
finished the same file with the model resident.

## GGUF file-backed fast path

Keyboard captures are durable little-endian float32 PCM files. The GGUF backend maps an eligible
complete capture read-only and passes those pages straight to transcribe.cpp. This skips the
one-second Java read loop, float reconstruction, whole-utterance `FloatArray`, and possible JNI
array copy. The file length must match the declared sample count exactly. Any map or native decode
failure keeps the file intact and falls back to the existing overlapping recovery path.

The Galaxy S25 Ultra production cap remains four native decode threads. A controlled five-minute
warm run at four threads completed in 37.2 seconds. An eight-thread run had not completed after 75
seconds and grew beyond 2.1 GB native RSS, so higher parallelism was rejected. The 110M model
advertises `streaming=false`; its continuous path is offline whole-utterance decode rather than a
cache-aware native stream.
