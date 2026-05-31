## ADDED Requirements

### Requirement: Profile LLM Processing Applies To Saved Recording Pipeline

Saved app and widget recordings with an associated profile SHALL apply that profile's LLM processing settings after local transcription.

#### Scenario: Profile default mode transforms transcript

- **GIVEN** a saved recording has a profile with `defaultProcessingMode`
- **WHEN** background transcription completes local speech recognition and word replacement
- **THEN** the worker SHALL process the transcript with the profile's default mode
- **AND** store the transformed text as `processedText`.

#### Scenario: Profile metadata flags control enhancement

- **GIVEN** a saved recording has a profile
- **WHEN** background enhancement runs
- **THEN** title generation SHALL follow the profile `autoTitle` setting
- **AND** summary generation SHALL follow the profile `autoSummary` setting.

#### Scenario: No profile falls back to global enhancement preferences

- **GIVEN** a saved recording has no profile
- **WHEN** background enhancement runs
- **THEN** title and summary generation SHALL follow the global LLM preferences.
