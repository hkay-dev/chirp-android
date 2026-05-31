## MODIFIED Requirements

### Requirement: Profile Settings

The system SHALL support profile LLM settings as saved-recording enhancement requests after offline transcription persists a transcript.

#### Scenario: Configure default processing mode

- **WHEN** user sets a default processing mode
- **THEN** new recordings with this profile SHALL request that mode as asynchronous enhancement after local transcription completes.

#### Scenario: Configure auto-title

- **WHEN** user enables auto-title
- **THEN** recordings with this profile SHALL request LLM-generated titles
- **AND** title generation SHALL run in enhancement work after transcript persistence.

#### Scenario: Configure auto-summary

- **WHEN** user enables auto-summary
- **THEN** recordings with this profile SHALL request LLM-generated summaries
- **AND** summary generation SHALL run in enhancement work after transcript persistence.
