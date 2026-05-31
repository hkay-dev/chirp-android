# app-structure Specification

## Purpose
TBD - created by archiving change add-v2-features. Update Purpose after archive.
## Requirements
### Requirement: History-First Navigation
The app SHALL display History as the default/home tab when launched.

#### Scenario: App launch shows history
- **WHEN** user opens the Parakeet Keyboard app
- **THEN** the History tab is selected and displayed by default

#### Scenario: Navigation order
- **WHEN** user views the bottom navigation bar
- **THEN** tabs appear in order: History, Notes, Settings

---

### Requirement: Notes Screen
The app SHALL provide a Notes screen for creating voice memos using the keyboard.

#### Scenario: Create voice note
- **WHEN** user navigates to Notes tab
- **AND** taps on the input field
- **AND** uses Parakeet keyboard to dictate
- **THEN** the transcribed text appears in the input field
- **AND** user can save the note

#### Scenario: View saved notes
- **WHEN** user has saved notes
- **AND** views the Notes screen
- **THEN** saved notes are displayed in a list below the input

#### Scenario: Empty state
- **WHEN** user has no saved notes
- **THEN** a helpful empty state message is displayed

---

### Requirement: Consolidated Settings Screen
The app SHALL consolidate setup and configuration into a single Settings screen.

#### Scenario: View setup status
- **WHEN** user navigates to Settings tab
- **THEN** setup steps are shown with completion status
- **AND** completed steps are collapsed by default

#### Scenario: Expand setup step
- **WHEN** user taps on a setup step
- **THEN** the step expands to show details and action button

#### Scenario: Model status visible
- **WHEN** user views Settings screen
- **THEN** current model status (ready/not ready) is visible

#### Scenario: Processing mode configuration
- **WHEN** user views Settings screen
- **THEN** processing mode options are available for configuration

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
