#!/usr/bin/env bash
# Reliability regression matrix for personal-use Chirp.
# Data safety: unit tests in :data plus compile of MigrationTest/DAO androidTests.
# Optional on-device run: ./scripts/run-device-verification.sh
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
