## 1. Implementation

- [x] 1.1 Set all Android module `compileSdk` values to 36.
- [x] 1.2 Set app `targetSdk` to 36.
- [x] 1.3 Set all module `minSdk` values to 36 unless verification proves a blocker.
- [x] 1.4 Remove low-risk unreachable SDK guards introduced only for pre-Android 16 devices.
- [x] 1.5 Align Android Gradle Plugin and Gradle wrapper to build API 36 without D8 API-level warnings.

## 2. Tests

- [x] 2.1 Run debug APK build after SDK alignment.

## 3. Verification

- [x] 3.1 `./gradlew :app:assembleDebug`
