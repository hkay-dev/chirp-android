## ADDED Requirements

### Requirement: Orphan Cleanup Handles Capture Directories

Recovery cleanup SHALL delete stale nested capture artifacts only when no repository, journal, safelist, or protected-path owner references them.

#### Scenario: Capture directory is stale and unreferenced

- **WHEN** `.capture/<session>` contains audio files older than the orphan grace window
- **AND** none of the files are referenced or protected
- **THEN** the capture directory is deleted recursively.

#### Scenario: Capture directory belongs to a live journal

- **WHEN** a capture segment path is referenced by a safelisted journal
- **THEN** orphan cleanup retains the capture directory and segment files.
