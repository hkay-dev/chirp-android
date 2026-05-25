## MODIFIED Requirements

### Requirement: Interactive Feedback

All interactive elements SHALL provide immediate visual feedback on user interaction. Processing Studio copy and studio action buttons SHALL follow the same feedback patterns as other outlined actions.

#### Scenario: Card press shows ripple
- **WHEN** user presses a clickable card
- **THEN** a ripple effect emanates from the touch point

#### Scenario: Settings item press shows scale
- **WHEN** user presses a settings navigation item
- **THEN** the item scales to 0.98x during press, returns to 1.0x on release with spring animation

#### Scenario: Dialog appears with animation
- **WHEN** a dialog is shown
- **THEN** the dialog fades in (200ms) while scaling from 0.85x to 1.0x

#### Scenario: Studio outlined action press feedback
- **WHEN** user presses a `StudioOutlinedAction` or `CopyActionButton`
- **THEN** Material outlined button ripple/press feedback applies
- **AND** icon and label remain visually aligned during press

### Requirement: State Transition Animation

All state-driven UI content switches SHALL animate using `AnimatedContent` or `Crossfade` composables, or `AnimatedVisibility` for enter/exit. Content MUST NOT appear or disappear instantly.

#### Scenario: Keyboard state changes animate
- **WHEN** keyboard state changes from Idle to Recording
- **THEN** the content crossfades over 200ms (old content fades out, new content fades in)

#### Scenario: Recording status text animates
- **WHEN** status text changes (e.g., "Recording" to "Saving...")
- **THEN** the text crossfades smoothly over 250ms

#### Scenario: Play/pause icon animates
- **WHEN** audio player toggles between play and pause
- **THEN** the icon crossfades over 200ms

#### Scenario: Chat typing indicator animates
- **WHEN** `isTyping` transitions from false to true on Chat tab
- **THEN** typing indicator bubble enters with studio reveal transition (`ChirpMotion.studioRevealTransition` or equivalent)
- **AND** exit uses studio hide transition when typing completes

#### Scenario: Structured outcome progress animates
- **WHEN** structured outcome generation starts or completes on Summary tab
- **THEN** compact morphing progress enters and exits via `AnimatedVisibility` with studio transitions
- **AND** does not pop in/out without animation

## ADDED Requirements

### Requirement: LLM progress morph reuse

Non-transcription LLM wait states in Processing Studio SHALL reuse `MorphingTranscriptionProgress` layout morph behavior (compact variant) with optional custom leading icon and copy, preserving spring-animated corner radius, padding, and spinner size transitions.

#### Scenario: Compact morph for structured outcomes
- **WHEN** Summary tab shows structured outcome generation progress
- **THEN** `MorphingTranscriptionProgress(compact = true, …)` is used
- **AND** layout properties animate via `ChirpMotion.layoutMotionSpring` when compact flag applies

#### Scenario: Optional leading icon for LLM waits
- **WHEN** morphing progress represents LLM structured outcome generation
- **THEN** an `AutoAwesome` (or configured) leading icon MAY display alongside the progress indicator
- **AND** transcription phase icons are not shown unless transcription phase kind is supplied

#### Scenario: Thinking animation for chat typing
- **WHEN** Chat tab typing indicator is visible
- **THEN** `ThinkingDots` (or equivalent core-ui thinking animation) animates continuously until typing ends
- **AND** animation uses staggered dot bounce with 400ms cycle per studio micro-interaction tier

### Requirement: Optional copy confirmation animation

When inline copy micro-feedback is implemented, copy action icons SHALL crossfade to a confirmation icon using quick-tier motion timing.

#### Scenario: Copy icon crossfade
- **WHEN** user successfully triggers a copy action with inline feedback enabled
- **THEN** action icon crossfades to check icon over 200ms (`ChirpMotion` quick tier)
- **AND** reverts to copy icon after up to 1.5 seconds or on next interaction
