# llm-processing Specification

## Purpose
TBD - created by archiving change add-v2-features. Update Purpose after archive.
## Requirements
### Requirement: Processing Mode Selection
The system SHALL support multiple processing modes for transcription post-processing.

#### Scenario: Available modes
- **WHEN** user views processing mode options
- **THEN** the following modes are available:
  - Raw (no LLM processing)
  - Formal (professional tone, proper grammar)
  - Casual (conversational tone)
  - Email (email-appropriate formatting)
  - Code (preserves technical terms, formatting)
  - Smart (auto-detect content type)
  - Custom (user-defined prompt)

#### Scenario: Mode affects output
- **WHEN** user selects "Formal" mode
- **AND** dictates casual speech
- **THEN** the output is formatted in professional tone

#### Scenario: Raw mode bypasses LLM
- **WHEN** user selects "Raw" mode
- **AND** completes a transcription
- **THEN** the raw transcription is used without LLM processing

---

### Requirement: Custom Processing Prompt
The system SHALL allow users to define custom processing prompts.

#### Scenario: Configure custom prompt
- **WHEN** user selects "Custom" mode in Settings
- **THEN** a text field is shown for entering custom instructions
- **AND** the prompt is saved for future use

#### Scenario: Custom prompt applied
- **WHEN** user has Custom mode selected with a defined prompt
- **AND** completes a transcription
- **THEN** the custom prompt is used for LLM processing

---

### Requirement: Smart Formatting
The system SHALL auto-detect content type and apply appropriate formatting in Smart mode.

#### Scenario: Detect list content
- **WHEN** Smart mode is active
- **AND** user dictates content with list patterns (e.g., "first... second... third...")
- **THEN** the output is formatted as a proper list

#### Scenario: Detect email content
- **WHEN** Smart mode is active
- **AND** user dictates email patterns (e.g., "Dear...", "Best regards...")
- **THEN** the output is formatted as an email

#### Scenario: Detect code content
- **WHEN** Smart mode is active
- **AND** user dictates technical/code patterns
- **THEN** technical terms and syntax are preserved

---

### Requirement: Keyboard Mode Selector
The keyboard UI SHALL provide quick access to change processing mode.

#### Scenario: View current mode
- **WHEN** keyboard is displayed
- **THEN** the current processing mode is visible

#### Scenario: Change mode from keyboard
- **WHEN** user taps on mode selector in keyboard
- **THEN** available modes are shown
- **AND** user can select a different mode

#### Scenario: Mode persists
- **WHEN** user changes processing mode
- **THEN** the selection persists across keyboard sessions

### Requirement: Text Processing Modes

The system SHALL support multiple text processing modes for transcript polishing when LLM is enabled.

#### Scenario: LLM disabled
- **WHEN** LLM processing is disabled
- **THEN** raw Sherpa transcription is used as-is
- **AND** no API calls are made

#### Scenario: Proofread mode
- **WHEN** Proofread mode is selected
- **THEN** the LLM fixes typos and grammar
- **AND** preserves the user's voice and style

#### Scenario: Formal mode
- **WHEN** Formal mode is selected
- **THEN** the LLM transforms text to professional tone
- **AND** uses corporate-appropriate language

#### Scenario: Casual mode
- **WHEN** Casual mode is selected
- **THEN** the LLM transforms text to conversational tone
- **AND** uses friendly, informal language

#### Scenario: Email mode
- **WHEN** Email mode is selected
- **THEN** the LLM formats text as professional email
- **AND** adds appropriate greetings and closings

#### Scenario: Code mode
- **WHEN** Code mode is selected
- **THEN** the LLM preserves technical syntax
- **AND** maintains function names, brackets, and formatting

#### Scenario: Smart mode
- **WHEN** Smart mode is selected
- **THEN** the LLM analyzes content type
- **AND** applies the most appropriate mode automatically

#### Scenario: Custom mode
- **WHEN** Custom mode is selected
- **THEN** the LLM uses the user-defined prompt
- **AND** applies custom instructions to the text

### Requirement: Auto-Title Generation

The system SHALL automatically generate titles for app/widget recordings using LLM.

#### Scenario: Generate title automatically
- **WHEN** auto-title is enabled for a profile
- **AND** transcription completes for an APP or WIDGET recording
- **THEN** the LLM generates a concise title from first 500 words
- **AND** the title is stored in Recording.title

#### Scenario: Generate title on demand
- **WHEN** user taps "Generate Title" on a recording
- **THEN** the LLM analyzes the transcript
- **AND** updates Recording.title with the result

#### Scenario: Keyboard recordings skip auto-title
- **WHEN** recording source is KEYBOARD
- **THEN** auto-title is not applied
- **AND** title remains null or user-provided

### Requirement: Summary Generation

The system SHALL generate brief summaries for app/widget recordings to display in the recordings list.

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

### Requirement: Context-Aware Processing

The system SHALL apply different LLM features based on recording source.

#### Scenario: Keyboard context
- **WHEN** recording source is KEYBOARD
- **THEN** only processing mode is applied (if LLM enabled)
- **AND** auto-title and summary are skipped
- **AND** focus is on speed for immediate text insertion

#### Scenario: App/Widget context
- **WHEN** recording source is APP or WIDGET
- **THEN** processing mode, auto-title, and summary are available
- **AND** each is controlled by profile settings
- **AND** runs as background processing after transcription

### Requirement: LLM Configuration

The system SHALL allow users to configure LLM settings.

#### Scenario: Set API key
- **WHEN** user enters a Gemini API key
- **THEN** the key is stored securely (encrypted)
- **AND** LLM features become available

#### Scenario: Select model
- **WHEN** user selects a Gemini model
- **THEN** future LLM requests use that model
- **AND** the selection persists across sessions

#### Scenario: LLM unavailable
- **WHEN** LLM features are requested
- **AND** no API key is configured
- **THEN** the user is prompted to configure LLM
- **AND** raw transcript is preserved without processing

### Requirement: Processing Pipeline Order

The system SHALL process transcripts in a defined order to ensure consistency.

#### Scenario: Full pipeline execution
- **WHEN** a recording completes and is queued for processing
- **THEN** the system executes steps in this order:
  1. Sherpa transcription produces rawText
  2. Word replacements applied to rawText
  3. Processing mode applied (if LLM enabled) produces processedText
  4. Auto-title generation (if enabled, APP/WIDGET only)
  5. Summary generation (if enabled, APP/WIDGET only)
  6. Obsidian export triggered (if enabled, after ALL above complete)

#### Scenario: Smart mode detection timing
- **WHEN** Smart mode is selected
- **THEN** content type detection runs on the text passed into LLM processing
- **AND** saved recording enhancement uses word-replacement output as that input

#### Scenario: Word replacement preservation
- **WHEN** text contains user-defined word replacements
- **THEN** LLM processing SHALL preserve replaced words
- **AND** NOT undo or modify user-specified replacements

### Requirement: Saved Recording Profile Enhancement

The system SHALL use recording profile LLM settings for saved app and widget recordings.

#### Scenario: Profile default mode runs before metadata generation

- **WHEN** a saved app or widget recording has a profile default processing mode
- **THEN** the LLM transform runs after word replacement
- **AND** title and summary generation use the transformed text.

#### Scenario: Profile metadata flags override global metadata settings

- **WHEN** a saved app or widget recording has a profile
- **THEN** auto-title and auto-summary follow the profile settings
- **AND** global metadata preferences are used only when no profile is associated.

### Requirement: Word Replacement Integration

The system SHALL integrate word replacements with LLM processing correctly.

#### Scenario: Replacements apply regardless of LLM state
- **WHEN** LLM processing is disabled
- **THEN** word replacements are still applied to rawText
- **AND** the corrected text is used for display/insertion

#### Scenario: Pipeline with LLM disabled
- **WHEN** LLM is disabled
- **THEN** pipeline is: Sherpa → Word replacements → Done
- **AND** no API calls are made
