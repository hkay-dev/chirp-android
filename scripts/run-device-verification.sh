#!/usr/bin/env bash
# Runs connected instrumentation when a device/emulator is available.
# Data safety coverage: Room migration tests + DAO androidTests.
set -euo pipefail

if ! command -v adb >/dev/null 2>&1; then
	echo "adb not found; skipping device verification"
	exit 0
fi

device_count="$(adb devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }')"
if [[ "${device_count}" -eq 0 ]]; then
	echo "No connected Android device; skipping device verification"
	exit 0
fi

./gradlew \
	:data:connectedDebugAndroidTest \
	:feature-recording:connectedDebugAndroidTest
