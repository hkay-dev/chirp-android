#!/usr/bin/env bash
set -euo pipefail

./gradlew \
	:core:testDebugUnitTest \
	:data:testDebugUnitTest \
	:feature-transcription:testDebugUnitTest \
	:feature-keyboard:testDebugUnitTest \
	:feature-recording:testDebugUnitTest \
	:feature-studio:testDebugUnitTest \
	:feature-llm:testDebugUnitTest \
	:feature-obsidian:testDebugUnitTest \
	:app:testDebugUnitTest \
	:data:compileDebugAndroidTestKotlin \
	:app:compileDebugAndroidTestKotlin \
	:feature-recording:compileDebugAndroidTestKotlin \
	:feature-studio:compileDebugAndroidTestKotlin \
	detekt
