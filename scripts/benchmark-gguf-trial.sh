#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
artifact_root="${CHIRP_BENCHMARK_ARTIFACTS:-/Users/harsha/Development/workspaces/ops_workspace/workbench/reports/chirp-gguf-benchmark}"
package="dev.chirpboard.app"
runner="$package.test/androidx.test.runner.AndroidJUnitRunner"
remote_root="/sdcard/Documents/.chirpboard/benchmarks"
audio_name="vergecast-5min-16k-mono.f32pcm"

declare -a models=(
  "q8:parakeet-tdt_ctc-110m-Q8_0.gguf"
  "q6-k:parakeet-tdt_ctc-110m-Q6_K.gguf"
  "q4-k-m:parakeet-tdt_ctc-110m-Q4_K_M.gguf"
)

require_device() {
  [[ "$(adb get-state 2>/dev/null || true)" == "device" ]] || {
    echo "Expected one authorized ADB device." >&2
    exit 1
  }
}

prepare() {
  require_device
  for item in "${models[@]}"; do
    [[ -f "$artifact_root/${item#*:}" ]] || { echo "Missing ${item#*:}" >&2; exit 1; }
  done
  [[ -f "$artifact_root/$audio_name" ]] || { echo "Missing $audio_name" >&2; exit 1; }

  "$repo_root/gradlew" -p "$repo_root" :app:assembleDebug :app:assembleDebugAndroidTest
  adb install -r "$repo_root/app/build/outputs/apk/debug/app-debug.apk"
  adb install -r "$repo_root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
  adb shell appops set "$package" MANAGE_EXTERNAL_STORAGE allow
  adb shell mkdir -p "$remote_root"
  adb push "$artifact_root/$audio_name" "$remote_root/$audio_name"
  for item in "${models[@]}"; do
    adb push "$artifact_root/${item#*:}" "$remote_root/${item#*:}"
  done
}

run_one() {
  local label="$1"
  local filename="$2"
  local thermal
  thermal="$(adb shell dumpsys thermalservice | grep -m1 'Thermal Status' | tr -d '\r' || true)"
  echo "benchmark model=$label ${thermal:-thermal=unknown}"
  adb shell am instrument -w -r \
    -e class dev.chirpboard.app.GgufNativeBenchmarkTest \
    -e modelLabel "$label" \
    -e modelPath "$remote_root/$filename" \
    -e audioPath "$remote_root/$audio_name" \
    -e backend cpu \
    -e threads "${CHIRP_GGUF_THREADS:-4}" \
    -e warmRuns "${CHIRP_GGUF_WARM_RUNS:-3}" \
    "$runner"
  adb logcat -d -v epoch -s ChirpGgufBenchmark:I '*:S'
  adb logcat -c
}

benchmark() {
  require_device
  adb logcat -c
  local item
  for item in "${models[@]}"; do
    run_one "${item%%:*}" "${item#*:}"
    sleep "${CHIRP_GGUF_COOLDOWN_SECONDS:-10}"
  done
  for ((index=${#models[@]} - 1; index>=0; index--)); do
    item="${models[$index]}"
    run_one "${item%%:*}" "${item#*:}"
    sleep "${CHIRP_GGUF_COOLDOWN_SECONDS:-10}"
  done
}

case "${1:-benchmark}" in
  prepare) prepare ;;
  benchmark) benchmark ;;
  all) prepare; benchmark ;;
  *) echo "Usage: $0 [prepare|benchmark|all]" >&2; exit 2 ;;
esac
