## ADDED Requirements

### Requirement: Crash-Tolerant Live Capture Segments

Service-owned app and widget recordings SHALL write live capture segments in a recoverable PCM/WAV container before stop/finalize, while preserving the user-selected final export format.

#### Scenario: Process dies before stop finalizes selected M4A export

- **GIVEN** the user-selected recording output format is M4A
- **AND** an app or widget recording is actively capturing audio
- **WHEN** the app process dies before stop/finalize writes the final export
- **THEN** the active session journal SHALL reference WAV segment artifacts that are recoverable on startup
- **AND** recovery SHALL be able to materialize the final M4A export from those segments

#### Scenario: User-selected export format remains unchanged

- **GIVEN** the user-selected recording output format is M4A or MP3
- **WHEN** the user stops an app or widget recording normally
- **THEN** the saved recording path SHALL use the selected output extension
- **AND** internal WAV capture segments SHALL NOT leak into the saved recording list
