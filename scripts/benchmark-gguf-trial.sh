#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
artifact_root="${CHIRP_BENCHMARK_ARTIFACTS:-/Users/harsha/Development/workspaces/ops_workspace/workbench/reports/chirp-gguf-benchmark}"
package="dev.chirpboard.app"
runner="$package.test/androidx.test.runner.AndroidJUnitRunner"
remote_root="/sdcard/Documents/.chirpboard/benchmarks"
audio_name="vergecast-5min-16k-mono.f32pcm"
kleidiai="${CHIRP_GGUF_KLEIDIAI:-true}"

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

sha256_file() {
  shasum -a 256 "$1" | awk '{print $1}'
}

installed_apk_sha256() {
  local installed_package="$1"
  local apk_path
  apk_path="$(adb shell pm path "$installed_package" | sed -n 's/^package://p' | head -1 | tr -d '\r')"
  [[ -n "$apk_path" ]] || return 1
  adb shell sha256sum "$apk_path" | awk '{print $1}' | tr -d '\r'
}

verify_benchmark_inputs() {
  local app_apk="$repo_root/app/build/outputs/apk/debug/app-debug.apk"
  local test_apk="$repo_root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
  [[ -f "$app_apk" && -f "$test_apk" ]] || {
    echo "Run prepare so the local APKs and device install are paired." >&2
    exit 1
  }
  [[ "$(sha256_file "$app_apk")" == "$(installed_apk_sha256 "$package")" ]] || {
    echo "Installed app APK does not match the local benchmark build; run prepare." >&2
    exit 1
  }
  [[ "$(sha256_file "$test_apk")" == "$(installed_apk_sha256 "$package.test")" ]] || {
    echo "Installed test APK does not match the local benchmark build; run prepare." >&2
    exit 1
  }

  local local_path remote_path local_sha remote_sha item
  for item in "$audio_name" "${models[@]}"; do
    local_path="$artifact_root/${item#*:}"
    remote_path="$remote_root/${item#*:}"
    [[ -f "$local_path" ]] || { echo "Missing $local_path" >&2; exit 1; }
    local_sha="$(sha256_file "$local_path")"
    remote_sha="$(adb shell sha256sum "$remote_path" 2>/dev/null | awk '{print $1}' | tr -d '\r')"
    [[ "$local_sha" == "$remote_sha" ]] || {
      echo "Device artifact ${item#*:} does not match the local fixture; run prepare." >&2
      exit 1
    }
    echo "artifactSha256 name=${item#*:} sha256=$local_sha"
  done
}

print_provenance() {
  local app_apk="$repo_root/app/build/outputs/apk/debug/app-debug.apk"
  local test_apk="$repo_root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
  echo "commit=$(git -C "$repo_root" rev-parse HEAD)"
  if [[ -n "$(git -C "$repo_root" status --porcelain)" ]]; then
    echo "worktree=dirty"
  else
    echo "worktree=clean"
  fi
  [[ ! -f "$app_apk" ]] || echo "appApkSha256=$(shasum -a 256 "$app_apk" | awk '{print $1}')"
  [[ ! -f "$test_apk" ]] || echo "testApkSha256=$(shasum -a 256 "$test_apk" | awk '{print $1}')"
  echo "deviceFingerprint=$(adb shell getprop ro.build.fingerprint | tr -d '\r')"
}

prepare() {
  require_device
  for item in "${models[@]}"; do
    [[ -f "$artifact_root/${item#*:}" ]] || { echo "Missing ${item#*:}" >&2; exit 1; }
  done
  [[ -f "$artifact_root/$audio_name" ]] || { echo "Missing $audio_name" >&2; exit 1; }

  "$repo_root/gradlew" -p "$repo_root" -Pchirp.gguf.kleidiai="$kleidiai" :app:assembleDebug :app:assembleDebugAndroidTest
  echo "kleidiaiBuildRequest=$kleidiai"
  adb install -r "$repo_root/app/build/outputs/apk/debug/app-debug.apk"
  adb install -r "$repo_root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
  adb shell appops set "$package" MANAGE_EXTERNAL_STORAGE allow
  adb shell mkdir -p "$remote_root"
  adb push "$artifact_root/$audio_name" "$remote_root/$audio_name"
  for item in "${models[@]}"; do
    adb push "$artifact_root/${item#*:}" "$remote_root/${item#*:}"
  done
  print_provenance
}

run_one() {
  local label="$1"
  local filename="$2"
  local thermal
  thermal="$(adb shell dumpsys thermalservice | grep -m1 'Thermal Status' | tr -d '\r' || true)"
  echo "benchmark model=$label ${thermal:-thermal=unknown}"
  if ! adb shell timeout "${CHIRP_GGUF_HOST_TIMEOUT_SECONDS:-420}s" am instrument -w -r \
    -e class dev.chirpboard.app.GgufNativeBenchmarkTest \
    -e modelLabel "$label" \
    -e modelPath "$remote_root/$filename" \
    -e audioPath "$remote_root/$audio_name" \
    -e backend cpu \
    -e threads "${CHIRP_GGUF_THREADS:-4}" \
    -e warmRuns "${CHIRP_GGUF_WARM_RUNS:-3}" \
    "$runner"; then
    adb logcat -d -v epoch -s ChirpGgufBenchmark:I AndroidRuntime:E '*:S'
    adb shell am force-stop "$package"
    return 1
  fi
  adb logcat -d -v epoch -s ChirpGgufBenchmark:I '*:S'
  adb logcat -c
  adb shell am force-stop "$package"
}

benchmark() {
  require_device
  verify_benchmark_inputs
  print_provenance
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
