## 1. Implementation

- [x] 1.1 Add recursive cleanup for stale `.capture/<session>` directories.
- [x] 1.2 Retain capture directories referenced by DB rows, journals, safelists, or protected paths.

## 2. Tests

- [x] 2.1 Cover stale unreferenced capture directory deletion.
- [x] 2.2 Cover journal-referenced capture directory retention.

## 3. Verification

- [x] 3.1 `./gradlew :feature-recording:testDebugUnitTest`
