## MODIFIED Requirements

### Requirement: Summary Generation

The system SHALL generate brief summaries for app/widget recordings to display in the recordings list. Processing Studio SHALL present in-tab progress while LLM-backed structured outcome extraction runs.

#### Scenario: Generate summary automatically
- **WHEN** auto-summary is enabled for a profile
- **AND** transcription completes for an APP or WIDGET recording
- **THEN** the LLM generates a 1-2 sentence summary
- **AND** the summary is stored in Transcript.summary

#### Scenario: Summary in list view
- **WHEN** recordings list is displayed
- **AND** a recording has a summary
- **THEN** the summary appears as a subline under the title
- **AND** helps users identify past recordings

#### Scenario: Keyboard recordings skip summary
- **WHEN** recording source is KEYBOARD
- **THEN** summary is not generated
- **AND** Transcript.summary remains null

#### Scenario: Structured outcome generation progress in Summary tab
- **WHEN** user triggers structured outcome generation or regeneration from Processing Studio Summary tab
- **AND** `structuredOutcomeSection.isGenerating` is true
- **THEN** the Summary tab shows compact morphing progress with LLM-appropriate title and subtitle strings
- **AND** static text-only "generating" info is not the sole progress affordance

#### Scenario: Structured outcome generation copy
- **WHEN** structured outcomes are generating
- **THEN** progress title and subtitle are loaded from string resources (`rec_structured_generating_title` / `rec_structured_generating_subtitle` or equivalent split of existing generating copy)
- **AND** copy is distinct from transcription phase strings (transcribing, enhancing, finalizing)

## ADDED Requirements

### Requirement: Studio chat LLM typing affordance

While Processing Studio chat awaits an LLM assistant response, the UI SHALL indicate that the assistant is composing a reply without implying transcription or structured-outcome pipeline activity.

#### Scenario: Typing distinct from transcription progress
- **WHEN** chat `isTyping` is true
- **THEN** the typing indicator appears only on the Chat tab
- **AND** transcription progress header/banner semantics are not reused for chat typing

#### Scenario: Typing during active chat exchange
- **WHEN** `completeStudioChatExchange` is in flight after user sends a message
- **THEN** `ProcessingStudioState.isTyping` remains true until the exchange completes
- **AND** Chat tab reflects this state via the typing indicator bubble

#### Scenario: Chat failure clears typing
- **WHEN** chat exchange completes (success or handled failure path in workflow)
- **THEN** `isTyping` is set false in resulting state
- **AND** typing indicator is not left visible indefinitely
