## MODIFIED Requirements

### Requirement: Content Size Animation

UI containers whose content changes size SHALL use `animateContentSize()` modifier to smoothly animate dimension changes. A single composable subtree SHALL own layout-size animation for a given screen region; nested containers MUST NOT stack competing `animateContentSize` modifiers on the same axis.

#### Scenario: Summary section expands
- **WHEN** a summary is added to the detail screen
- **THEN** the container height animates smoothly (not instant jump)

#### Scenario: Error message appears
- **WHEN** an error message is shown
- **THEN** the container expands with `expandVertically` animation

#### Scenario: Processing Studio single animation owner
- **WHEN** Processing Studio header content switches between progress banner and audio player
- **THEN** only the studio root content column applies `animateContentSize`
- **AND** `StudioProcessingHeader` does not apply its own `animateContentSize`

---

### Requirement: State Transition Animation

All state-driven UI content switches SHALL animate using `AnimatedContent` or `Crossfade` composables, or `AnimatedVisibility` for enter/exit. Content MUST NOT appear or disappear instantly. Alpha-only visibility via `graphicsLayer { alpha = 0f }` without `AnimatedVisibility` MUST NOT be used for major content regions.

#### Scenario: Keyboard state changes animate
- **WHEN** keyboard state changes from Idle to Recording
- **THEN** the content crossfades over 200ms (old content fades out, new content fades in)

#### Scenario: Recording status text animates
- **WHEN** status text changes (e.g., "Recording" to "Saving...")
- **THEN** the text crossfades smoothly over 250ms

#### Scenario: Play/pause icon animates
- **WHEN** audio player toggles between play and pause
- **THEN** the icon crossfades over 200ms

#### Scenario: Transcript chrome uses AnimatedVisibility
- **WHEN** Processing Studio transcript tab transitions between processing, editing, and readable states
- **THEN** copy actions, correction banner, and transcript body use `AnimatedVisibility` with `ChirpMotion` studio enter/exit transitions
- **AND** hidden content is not composed as touchable at alpha zero

## ADDED Requirements

### Requirement: Studio transcription progress morph visuals

Transcription progress UI in Processing Studio SHALL morph layout properties (corner radius, padding, spinner size) via spring animation and SHALL display phase-specific leading icons for Finalizing, Transcribing, and Enhancing states.

#### Scenario: Phase icon updates on pipeline change
- **WHEN** recording status moves from finalizing to transcribing to enhancing
- **THEN** `MorphingTranscriptionProgress` displays a distinct icon per `TranscriptionProgressKind`
- **AND** icon and copy crossfade using `ChirpMotion.studioContentCrossfade`

#### Scenario: Compact and expanded variants share phase icons
- **WHEN** progress is shown in the header banner (compact) or transcript tab panel (expanded)
- **THEN** both variants display the same phase-specific icon semantics
- **AND** compact variant retains a progress indicator appropriate to the layout

#### Scenario: Layout morph obeys motion tokens
- **WHEN** progress transitions between compact and expanded presentation
- **THEN** corner radius, padding, and spinner size animate via `ChirpMotion.layoutMotionSpring`
- **AND** animation completes without discrete jumps
