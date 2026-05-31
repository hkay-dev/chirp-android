## Why

The audit instructions define Android 16 / API 36 as the only supported runtime baseline, but every Android module still compiles with SDK 35 and most modules declare `minSdk = 26`. That drift keeps legacy branches reachable and weakens Android 16 platform validation.

## What Changes

- Align every Android module to `compileSdk = 36`.
- Align app `targetSdk = 36`.
- Align all module `minSdk` values to 36 unless a test runner or dependency fails the build and proves a blocker.
- Remove or follow up on version guards that become unreachable after minSdk alignment.

## Capabilities

### Modified Capabilities

- `app-structure`: build configuration matches the Android 16-only product baseline.

## Impact

- Modules: all Android modules
- Depends on: local Android SDK API 36, already installed
- Verification: `./gradlew :app:assembleDebug`
