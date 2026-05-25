## ADDED Requirements

### Requirement: Keyboard processing phase crossfade

Transcription and LLM polishing phases on the keyboard SHALL animate using shared `ChirpMotion` crossfade tokens when transitioning between processing sub-states.

#### Scenario: Transcribing to polishing crossfade

- **WHEN** keyboard state changes from `Transcribing` to `Polishing`
- **THEN** status text and processing indicator SHALL crossfade using `ChirpMotion.keyboardProcessingCrossfade`
- **AND** transition duration SHALL use the Quick/Standard tier (200–250 ms) with `FastOutSlowInEasing`

#### Scenario: Processing indicator continuity

- **WHEN** processing phase changes on the keyboard
- **THEN** `ThinkingDots` animation SHALL continue without a full-screen flash or instant swap
- **AND** outgoing and incoming status labels SHALL fade per crossfade spec

#### Scenario: ChirpMotion token defined

- **WHEN** keyboard processing UI references crossfade animation
- **THEN** it SHALL use `ChirpMotion.keyboardProcessingCrossfade` from `core-ui`
- **AND** SHALL NOT define ad hoc tween durations inline in `KeyboardUI`

---

### Requirement: Keyboard major state transitions

Major keyboard state changes (Idle, Recording, Processing, Error) SHALL continue to animate via `AnimatedContent` without instant content swaps.

#### Scenario: Idle to recording animates

- **WHEN** keyboard state changes from Idle to Recording
- **THEN** main content region crossfades over 200 ms with `FastOutSlowInEasing`
- **AND** hoisted mode selector row outside animated content SHALL NOT participate in the crossfade (no flicker)

#### Scenario: Recording to processing animates

- **WHEN** keyboard state changes from Recording to Transcribing
- **THEN** waveform and stop controls fade out
- **AND** processing indicator fades in over Standard tier duration

## MODIFIED Requirements

### Requirement: State Transition Animation

All state-driven UI content switches SHALL animate using `AnimatedContent` or `Crossfade` composables. Content MUST NOT appear or disappear instantly.

#### Scenario: Keyboard state changes animate

- **WHEN** keyboard state changes from Idle to Recording
- **THEN** the main animated content region crossfades over 200ms (old content fades out, new content fades in)
- **AND** persistently hoisted controls (mode selector, LLM chip) remain stable outside the animated region

#### Scenario: Recording status text animates

- **WHEN** processing status text changes (e.g., "Transcribing..." to "Polishing...")
- **THEN** the text crossfades smoothly using `ChirpMotion.keyboardProcessingCrossfade`
- **AND** duration aligns with the 200–250 ms standard for status copy changes

#### Scenario: Play/pause icon animates

- **WHEN** audio player toggles between play and pause
- **THEN** the icon crossfades over 200ms

---

### Requirement: Animation Timing Standards

All UI animations SHALL use standardized duration tiers and easing functions to ensure visual consistency across the application.

| Tier | Duration | Use Case |
|------|----------|----------|
| Quick | 200ms | Micro-interactions, ripples, icon swaps |
| Standard | 300ms | State transitions, visibility changes |
| Emphasis | 400ms | Important state changes (recording start/stop) |

All tween animations SHALL use `FastOutSlowInEasing` unless spring physics are more appropriate.

#### Scenario: State transition uses standard duration

- **WHEN** a UI element transitions between states (e.g., idle to recording)
- **THEN** the animation completes in 300ms with FastOutSlowInEasing

#### Scenario: Micro-interaction uses quick duration

- **WHEN** a user taps a button or toggles a switch
- **THEN** the visual feedback animation completes in 200ms

#### Scenario: Emphasis animation for recording

- **WHEN** recording starts or stops
- **THEN** the main action button color transition uses 400ms with emphasized easing

#### Scenario: Keyboard processing uses crossfade tier

- **WHEN** keyboard processing phase label changes between transcribing and polishing
- **THEN** crossfade duration SHALL be 250ms fade-in and 200ms fade-out per `ChirpMotion.keyboardProcessingCrossfade`
- **AND** easing SHALL be `FastOutSlowInEasing`
