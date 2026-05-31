# profiles Specification

## Purpose
TBD - created by archiving change transform-to-recording-app. Update Purpose after archive.
## Requirements
### Requirement: Profile Management

The system SHALL allow users to create and manage recording profiles.

#### Scenario: Create profile
- **WHEN** user taps "Create Profile"
- **THEN** a profile editor opens
- **AND** user can configure all profile settings

#### Scenario: Edit profile
- **WHEN** user taps on an existing profile
- **THEN** the profile editor opens with current settings
- **AND** user can modify and save changes

#### Scenario: Delete profile
- **WHEN** user deletes a profile
- **THEN** the profile is removed
- **AND** existing recordings retain their profile reference (orphaned)

#### Scenario: List profiles
- **WHEN** user opens the Profiles screen
- **THEN** all profiles are displayed
- **AND** each shows name and icon

### Requirement: Profile Settings

The system SHALL support the following profile configuration options.

#### Scenario: Configure name and icon
- **WHEN** user edits profile name and icon
- **THEN** the profile displays with the custom name
- **AND** shows the selected emoji icon

#### Scenario: Configure default processing mode
- **WHEN** user sets a default processing mode
- **THEN** new recordings with this profile use that mode
- **AND** the mode is auto-applied after transcription

#### Scenario: Configure auto-transcribe
- **WHEN** user enables auto-transcribe
- **THEN** recordings with this profile transcribe automatically
- **AND** transcription starts when recording stops

#### Scenario: Configure auto-title
- **WHEN** user enables auto-title
- **THEN** recordings with this profile get LLM-generated titles
- **AND** title generation runs after transcription

#### Scenario: Configure auto-summary
- **WHEN** user enables auto-summary
- **THEN** recordings with this profile get LLM-generated summaries
- **AND** summary generation runs after transcription

#### Scenario: Configure Obsidian export
- **WHEN** user sets an Obsidian vault path
- **THEN** recordings with this profile export to that vault
- **AND** export happens after transcription completes

#### Scenario: Configure default tags
- **WHEN** user selects default tags
- **THEN** recordings with this profile get those tags applied
- **AND** tags are added when recording is created

### Requirement: Profile Selection

The system SHALL allow users to select a profile when recording.

#### Scenario: Select profile before recording
- **WHEN** user long-presses the record FAB
- **THEN** a profile picker appears
- **AND** selecting a profile starts recording with those settings

#### Scenario: Quick record without profile
- **WHEN** user taps the record FAB
- **THEN** recording starts with default settings
- **AND** no profile is associated

### Requirement: Profile App Shortcuts

The system SHALL optionally create Android app shortcuts for profiles.

#### Scenario: Create shortcut
- **WHEN** user enables shortcut for a profile
- **THEN** an Android app shortcut is created
- **AND** the shortcut appears in launcher long-press menu

#### Scenario: Launch via shortcut
- **WHEN** user taps a profile shortcut
- **THEN** the app opens directly to recording
- **AND** uses that profile's settings

### Requirement: Keyboard and Profile Interaction

The system SHALL define how keyboard interacts with profiles.

#### Scenario: Keyboard uses global settings
- **WHEN** user records via keyboard IME
- **THEN** the global processing mode setting is used
- **AND** no profile is associated with the recording
- **AND** keyboard remains fast and simple

#### Scenario: Keyboard recordings and profiles are separate
- **WHEN** keyboard recording is created
- **THEN** profileId is null on the Recording entity
- **AND** profile-specific features (auto-title, auto-summary, auto-export) do not apply

#### Scenario: Default profile for quick app recording
- **WHEN** user taps record FAB (not long-press)
- **THEN** recording starts with global default settings
- **AND** profileId is null (no profile associated)
- **AND** user can assign profile later if desired
