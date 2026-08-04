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

## Local Parakeet warm window

The process-wide recognizer follows these rules.

1. A visible Chirp Voice window keeps Parakeet resident.
2. Hiding the IME starts a five-minute grace window. Any later recognizer use moves that deadline
   forward, so quick field and app switches stay warm.
3. Active recording, an in-flight recognizer lease, and queued local transcription all block an
   idle release.
4. Confirmed low-memory trim signals and severe-or-higher thermal status may release an unused
   recognizer immediately.
5. The next IME bind or transcription request loads the model again if it was released.

Warmup loads and verifies model resources only. Audio capture still begins solely from an explicit
recording action, so the warm policy never opens or reserves the microphone.
