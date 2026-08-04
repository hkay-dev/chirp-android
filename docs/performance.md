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

1. The selected downloaded model prewarms shortly after process startup. A failed warmup gets two
   spaced retries, so one transient native or storage failure does not leave later dictations cold.
2. A loaded model stays resident across completed dictations, IME hides, app switches, and idle
   time. There is no idle-release timer.
3. Active recording, an in-flight recognizer lease, and queued local transcription block pressure
   release.
4. Confirmed low-memory signals and severe-or-higher thermal status may release an unused model.
5. The next startup, IME bind, or transcription request loads a model again if pressure freed it.

Warmup loads and verifies model resources only. Audio capture still begins solely from an explicit
recording action, so residency never opens or reserves the microphone.

## Keyboard capture hot path

The microphone starts independently of recognizer readiness. The user-facing recording state waits
only for the first `AudioRecord` block to finish its direct file write. The first local block creates
a checkpoint on the teardown dispatcher, so its sync cannot delay that speak-now boundary. At
recovery time the checkpoint identifies the owned file and the complete float-aligned file length
extends recovery through audio written after that first marker.

The urgent capture thread allocates its read and conversion buffers once per session. Float encoding,
gain, silence detection, and waveform amplitude share one sample pass per block. UI amplitude no
longer rescans the buffer after the durable write.

## GGUF continuous decode ceiling

Parakeet TDT 110M GGUF receives one continuous 16 kHz PCM buffer for recordings up to five minutes.
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

Each native call reports transcribe.cpp's load, mel, encoder, and decoder stage times plus the
wall-clock total. Chirp keeps the newest 64 content-free entries across process restarts in app
storage. The history contains model ID, actual compute backend, thread count, durations, source
kind, native status, and outcome only. It never contains audio, transcript text, file paths,
package names, prompts, or error messages. Writes run on one background thread and use an atomic
temporary-file replacement.

A decode watchdog uses a 30 to 90 second audio-scaled deadline. It asks transcribe.cpp to abort at
its next safe polling boundary, waits for the native call to unwind on the dedicated decode thread,
and retries from the untouched audio in bounded overlapping chunks. Operation identifiers prevent
a late timeout from cancelling a newer decode. File recovery validates the exact byte length and
reads little-endian float32 samples in bounded slices, leaving the source file unchanged.

The Galaxy S25 Ultra production cap remains four native decode threads. A controlled five-minute
warm run at four threads completed in 37.2 seconds. An eight-thread run had not completed after 75
seconds and grew beyond 2.1 GB native RSS, so higher parallelism was rejected. The 110M model
advertises `streaming=false`; its continuous path is offline whole-utterance decode rather than a
cache-aware native stream.

`scripts/benchmark-gguf-trial.sh all` installs an instrumentation harness, pushes the same float32
PCM file and verified Q8, Q6_K, and Q4_K_M models, and runs each model in forward and reverse
order. Every invocation measures a cold model load, one cache-cold decode, and three warm decodes.
Each native run uses the production 90-second maximum and records a timeout rather than hanging.
Logs include stage timing, actual backend, thread count, model size, thermal status, and a transcript
SHA-256 digest. Transcript text and audio are not logged.

The first controlled CPU run on the Galaxy S25 Ultra loaded Q8 in 751 ms and decoded the five-minute
clip in 61.8 seconds cold, followed by warm runs of 68.0, 67.6, and 66.0 seconds. All four transcript
digests matched. Q6_K and Q4_K_M both missed the 90-second production cutoff on their first decode,
so Q8 remains the performance default pending a kernel improvement. The smaller files save storage
and load bytes, though they do not currently improve transcription latency on this device.

KleidiAI kernels are enabled by default; `-Pchirp.gguf.kleidiai=false` keeps a reproducible generic
CPU control build, and generic GGML kernels stay linked as the runtime fallback. On the same Q8
clip, KleidiAI cut cold decode from 61.8 to 55.1 seconds and warm decode from the 66.0 to 68.0
second range to 57.0 seconds with an identical transcript digest. Vulkan selection is wired as a
beta experiment with automatic load-time
and decode-time CPU recovery, though the pinned Android native build does not yet ship Vulkan.
The upstream shader generator currently emits declarations with no linked shader data in this
embedded Android build, so selecting Vulkan reports the actual CPU fallback rather than pretending
GPU work ran.

An explicit `POSIX_FADV_WILLNEED` plus `MADV_WILLNEED` mapped-audio experiment was rejected during
the beta pass. Its clean-device Q8 run took 57.6 seconds cold and 69.2 seconds warm with the same
transcript digest, versus the established 55.1-second cold and 57.0-second warm KleidiAI result.
The existing sequential mapping hint remains; beta does not keep an unmeasured read-ahead tweak.

The pinned 110M GGUF converter drops the hybrid model's auxiliary CTC head and exports its TDT head.
A genuine CTC-only GGUF option therefore remains gated until a comparably small verified artifact
exists. The available pure-CTC model is the much larger 0.6B class and is not a useful replacement
for this low-latency experiment.
