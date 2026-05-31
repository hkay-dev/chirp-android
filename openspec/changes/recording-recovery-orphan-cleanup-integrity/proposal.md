## Why

Orphan cleanup scanned only top-level recording files. Stale nested capture directories under `.capture` could survive forever after crashes before journal cleanup.

## What Changes

- Recursively clean stale unreferenced capture directories after the orphan grace window.
- Keep journal-referenced, safelisted, repository-owned, or recovery-protected capture files.
- Add tests for deleting unreferenced capture dirs and retaining active journal capture dirs.

## Capabilities

### Modified Capabilities

- `recording-stability`: recovery cleanup includes stale nested capture artifacts without deleting live or protected audio.

## Impact

- Modules: `feature-recording`
- Verification: `./gradlew :feature-recording:testDebugUnitTest`
