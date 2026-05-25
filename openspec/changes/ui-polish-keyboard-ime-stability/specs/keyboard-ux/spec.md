## ADDED Requirements

### Requirement: IME composition lifecycle stability

The keyboard IME service SHALL retain its `ComposeView` and active composition across input view hide/show cycles. Composition SHALL only be disposed when the IME service is destroyed.

#### Scenario: Field switch preserves composition

- **WHEN** user switches to a different text field while the keyboard remains enabled
- **THEN** the IME SHALL reuse the cached `ComposeView` without calling `disposeComposition()`
- **AND** `ModeSelector` horizontal scroll position SHALL be preserved if unchanged between Idle and Recording

#### Scenario: Keyboard hide does not destroy composition

- **WHEN** user dismisses the keyboard (`onFinishInputView` is invoked)
- **THEN** the service SHALL NOT call `disposeComposition()` on the cached view
- **AND** the service SHALL NOT null out the `ComposeView` reference

#### Scenario: Service destroy cleans up composition

- **WHEN** `ChirpKeyboardService.onDestroy()` is invoked
- **THEN** the service SHALL call `disposeComposition()` on the cached `ComposeView`
- **AND** clear the view reference before superclass destroy

#### Scenario: Input view creation reuses cache

- **WHEN** `onCreateInputView()` is invoked and a cached `ComposeView` exists
- **THEN** the service SHALL return the existing view
- **AND** SHALL NOT dispose composition before returning the view

---

### Requirement: Keyboard voice input visual parity

The keyboard recording and processing UI SHALL use the same core voice-input affordances as the in-app voice recognition dialog.

#### Scenario: Recording timer during capture

- **WHEN** keyboard is in `Recording` state
- **THEN** elapsed recording time SHALL be displayed using the shared `RecordingTimer` component
- **AND** typography SHALL use monospace styling consistent with the voice recognition dialog

#### Scenario: Thinking indicator during processing

- **WHEN** keyboard is in `Transcribing` or `Polishing` state
- **THEN** the UI SHALL display `ThinkingDots` instead of a standalone circular progress indicator
- **AND** phase-specific status text SHALL remain visible alongside the dots

#### Scenario: LLM chip uses AutoAwesome icon

- **WHEN** LLM processing is enabled on the keyboard
- **THEN** the LLM filter chip SHALL show `AutoAwesome` as its leading icon
- **AND** the chip label SHALL reflect the active processing mode name (not the literal text "LLM")

#### Scenario: LLM chip when disabled

- **WHEN** LLM processing is disabled on the keyboard
- **THEN** the chip SHALL show an enable label without the AutoAwesome leading icon
- **AND** mode selector chips SHALL be hidden

---

### Requirement: Stop control icon semantics

The keyboard stop affordance during active recording SHALL use a Stop icon, not a Check icon.

#### Scenario: Stop FAB shows Stop icon

- **WHEN** keyboard is in `Recording` state
- **THEN** the primary stop floating action button SHALL display a Stop icon
- **AND** content description SHALL indicate stopping recording (not confirming or completing)

#### Scenario: Stop icon matches app recording surfaces

- **WHEN** user compares keyboard stop control with the voice recognition dialog stop control
- **THEN** both SHALL use Stop iconography for the active-recording stop action

---

### Requirement: Persistent mode selector placement

The processing mode selector and LLM chip row SHALL remain outside state-driven animated content regions so scroll position survives Idle ↔ Recording transitions.

#### Scenario: Mode selector survives recording start

- **WHEN** user scrolls the mode chip row horizontally
- **AND** user starts recording from Idle
- **THEN** the mode chip row scroll offset SHALL be preserved
- **AND** the row SHALL remain visible during recording when LLM is enabled

#### Scenario: Mode selector hidden during processing

- **WHEN** keyboard enters `Transcribing`, `Polishing`, `Downloading`, `ModelNotReady`, or error states
- **THEN** the mode selector row MAY be hidden
- **AND** scroll state SHALL be restored when returning to Idle or Recording

---

### Requirement: Model not ready call to action

The keyboard SHALL provide an actionable control when the transcription model is not ready.

#### Scenario: Download CTA visible

- **WHEN** keyboard state is `ModelNotReady`
- **THEN** the UI SHALL display a primary button in addition to explanatory text
- **AND** the button label SHALL indicate opening the app to download or configure the model

#### Scenario: CTA opens main app

- **WHEN** user taps the ModelNotReady primary button
- **THEN** the main application activity SHALL be launched
- **AND** the keyboard SHALL attempt model initialization if appropriate when user returns

#### Scenario: ModelNotReady tap on mic area

- **WHEN** user taps the main keyboard action while in `ModelNotReady`
- **THEN** the service SHALL retry model initialization (existing behavior preserved)

## MODIFIED Requirements

### Requirement: Audio Waveform Visualization

The keyboard SHALL display a waveform visualization while recording audio.

#### Scenario: Waveform appears during recording

- **WHEN** user starts voice recording
- **THEN** an animated waveform visualization appears
- **AND** the waveform responds to audio input amplitude
- **AND** elapsed recording time is displayed above or adjacent to the waveform via `RecordingTimer`

#### Scenario: Waveform disappears after recording

- **WHEN** user stops voice recording
- **THEN** the waveform visualization disappears
- **AND** is replaced by the transcription result or processing indicator (`ThinkingDots` with status text)

#### Scenario: Waveform styling

- **WHEN** waveform is displayed
- **THEN** it uses Material 3 color scheme
- **AND** adapts to light/dark theme
