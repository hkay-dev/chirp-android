#!/usr/bin/env bash
set -euo pipefail

./gradlew \
	:core:testDebugUnitTest \
	:feature-transcription:testDebugUnitTest \
	:feature-keyboard:testDebugUnitTest \
	:app:testDebugUnitTest \
	:feature-recording:compileDebugKotlin \
	:data:compileDebugAndroidTestKotlin
