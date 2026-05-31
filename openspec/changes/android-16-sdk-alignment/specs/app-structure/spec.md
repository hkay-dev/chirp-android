## ADDED Requirements

### Requirement: Android 16 SDK Baseline

The project SHALL declare Android 16 / API 36 as the compile, target, and minimum runtime baseline for Android modules unless a dependency or test runner has a documented blocker.

#### Scenario: Building the debug app

- **WHEN** the project builds `:app:assembleDebug`
- **THEN** Android modules compile against API 36 and the app targets API 36.

### Requirement: Dead Legacy SDK Branches Removed

Production code SHALL NOT keep low-risk runtime branches that only support SDK levels below the declared API 36 minimum.

#### Scenario: Auditing SDK guards

- **WHEN** production code is scanned for direct pre-API-36 `Build.VERSION.SDK_INT` checks
- **THEN** low-risk branches for audio focus, input device selection, haptics, shared audio handoff, notification permission prompts, dynamic color, and all-files access are absent.
