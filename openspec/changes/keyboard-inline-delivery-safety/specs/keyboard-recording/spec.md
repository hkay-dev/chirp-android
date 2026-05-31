## ADDED Requirements

### Requirement: Inline Dictation Commits Only To Current Input

Keyboard inline transcription SHALL commit text only to the input session that requested the transcription.

#### Scenario: Input changes before transcription finishes

- **WHEN** keyboard dictation starts in one input field
- **AND** the IME input generation changes before transcription finishes
- **THEN** the late transcript is not committed to the new input connection.

### Requirement: Sensitive Inputs Reject Dictation Commit

Keyboard dictation SHALL NOT insert transcribed text into sensitive input fields.

#### Scenario: Password field receives focus

- **WHEN** the keyboard starts input for a password or no-personalized-learning field
- **THEN** dictation commit is refused and any active keyboard recording is stopped through the normal keyboard stop path.
