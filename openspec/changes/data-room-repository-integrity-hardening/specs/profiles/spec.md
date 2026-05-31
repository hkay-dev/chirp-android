## ADDED Requirements

### Requirement: Normalized Profile Default Tags

Profile default tags SHALL be stored as normalized relationships that reference existing tags.

#### Scenario: Configure profile default tags

- **GIVEN** a user assigns default tags to a profile
- **WHEN** the profile is saved
- **THEN** the default tag relationships reference only existing tag IDs.

#### Scenario: Delete default tag

- **GIVEN** a tag is configured as a profile default
- **WHEN** that tag is deleted
- **THEN** the profile no longer references the deleted tag.

### Requirement: Atomic Profile Default Tag Application

Applying profile default tags to a new recording SHALL happen in the same transaction that establishes the recording and its initial metadata.

#### Scenario: Recording starts with defaults

- **GIVEN** a selected profile has default tags
- **WHEN** a recording row is created for a new capture
- **THEN** the recording receives the profile default tags atomically with the recording creation.

#### Scenario: No valid tags

- **GIVEN** a profile has no valid default tag relationships
- **WHEN** a recording row is created
- **THEN** the recording is created without default tags and without failing the capture.
