#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
artifact_root="${CHIRP_BENCHMARK_ARTIFACTS:-/Users/harsha/Development/workspaces/ops_workspace/workbench/reports/chirp-gguf-benchmark}"
apk="$repo_root/app/build/outputs/apk/ggufTrial/app-ggufTrial.apk"
model="$artifact_root/parakeet-tdt_ctc-110m-Q8_0.gguf"
clip="$artifact_root/vergecast-5min-16k-mono.wav"
full="$artifact_root/vergecast-full-16k-mono.wav"

require_device() {
    local count
    count="$(adb devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }')"
    if [[ "$count" -ne 1 ]]; then
        echo "Expected exactly one authorized ADB device, found $count." >&2
        exit 1
    fi
}

prepare() {
    require_device
    [[ -f "$apk" ]] || "$repo_root/gradlew" -p "$repo_root" :app:assembleGgufTrial
    [[ -f "$model" ]] || { echo "Missing model at $model" >&2; exit 1; }
    [[ -f "$clip" ]] || { echo "Missing benchmark clip at $clip" >&2; exit 1; }
    [[ -f "$full" ]] || { echo "Missing full benchmark audio at $full" >&2; exit 1; }

    adb install -r "$apk"
    adb shell mkdir -p /sdcard/Documents/.chirpboard/models/parakeet-tdt-ctc-110m-q8
    adb push "$model" /sdcard/Documents/.chirpboard/models/parakeet-tdt-ctc-110m-q8/
    adb push "$clip" /sdcard/Download/chirp-vergecast-5min.wav
    adb push "$full" /sdcard/Download/chirp-vergecast-full.wav
    adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
        -d file:///sdcard/Download/chirp-vergecast-5min.wav >/dev/null
    adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
        -d file:///sdcard/Download/chirp-vergecast-full.wav >/dev/null
    adb logcat -c

    echo "Prepared Chirp GGUF Trial and both Vergecast files."
    echo "Import chirp-vergecast-5min.wav in standard Chirp, followed by Chirp GGUF Trial."
    echo "Run '$0 logs' once both transcriptions finish."
}

logs() {
    require_device
    adb logcat -d -v epoch \
        -s SherpaRecognizerProvider:I GgufTrialRecognizer:I ChirpGgufNative:I '*:S'
}

case "${1:-prepare}" in
    prepare) prepare ;;
    logs) logs ;;
    *) echo "Usage: $0 [prepare|logs]" >&2; exit 2 ;;
esac
