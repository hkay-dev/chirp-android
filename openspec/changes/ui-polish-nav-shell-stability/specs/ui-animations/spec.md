## ADDED Requirements

### Requirement: Modal Overlay Animation Pattern

Full-screen blocking overlays (shared intake, scrims) SHALL use `AnimatedVisibility` with fade transitions rather than instant composable swaps.

#### Scenario: Scrim fades in
- **WHEN** a blocking overlay becomes visible
- **THEN** the scrim alpha animates from 0 to target opacity over 300ms with `FastOutSlowInEasing`
- **AND** overlay content does not appear without a transition

#### Scenario: Scrim fades out
- **WHEN** a blocking overlay is dismissed
- **THEN** the scrim and content fade out over 260ms
- **AND** underlying UI is not unmounted during the transition

---

### Requirement: Voice Recognition Sheet Animation

The keyboard voice-recognition bottom sheet SHALL enter, exit, and animate internal recording visuals without blank frames or asymmetric teardown.

#### Scenario: Sheet appears without entry flash
- **WHEN** the voice recognition dialog is shown
- **THEN** the sheet becomes visible on the first frame (no intentional blank delay before content)
- **AND** enter transition uses fade plus vertical slide with spring physics

#### Scenario: Cancel waits for exit animation
- **WHEN** user taps cancel on the voice recognition dialog
- **THEN** the sheet plays the same exit transition as programmatic dismiss
- **AND** `onDismissComplete` (or equivalent teardown callback) runs only after exit animation completes (~250ms total)

#### Scenario: Glow animates with recording state
- **WHEN** recording becomes active inside the voice recognition dialog
- **THEN** `RecordingGlowBackground` enters via `AnimatedVisibility` with fade
- **AND** glow exits with fade when recording stops or processing begins

#### Scenario: Waveform animates with recording state
- **WHEN** recording is active and not in stopping/processing state
- **THEN** `AudioWaveform` is shown via `AnimatedVisibility` with fade
- **AND** waveform hides with fade when recording pauses or enters processing

---

## MODIFIED Requirements

### Requirement: Interactive Feedback

All interactive elements SHALL provide immediate visual feedback on user interaction.

#### Scenario: Card press shows ripple
- **WHEN** user presses a clickable card
- **THEN** a ripple effect emanates from the touch point

#### Scenario: Settings item press shows scale
- **WHEN** user presses a settings navigation item
- **THEN** the item scales to 0.98x during press, returns to 1.0x on release with spring animation

#### Scenario: Dialog appears with animation
- **WHEN** a dialog is shown
- **THEN** the dialog fades in (200ms) while scaling from 0.85x to 1.0x

#### Scenario: Recovery and confirmation dialogs use AnimatedAlertDialog
- **WHEN** a session recovery prompt or recording confirmation dialog is shown on Home or Record screens
- **THEN** the dialog uses `AnimatedAlertDialog` (scale 0.9→1.0 and fade 0→1 over 250ms)
- **AND** stock unanimated `AlertDialog` is not used for these flows

---

### Requirement: Content Size Animation

UI containers whose content changes size SHALL use `animateContentSize()` modifier to smoothly animate dimension changes.

#### Scenario: Summary section expands
- **WHEN** a summary is added to the detail screen
- **THEN** the container height animates smoothly (not instant jump)

#### Scenario: Error message appears
- **WHEN** an error message is shown
- **THEN** the container expands with `expandVertically` animation

#### Scenario: Nav shell reflows for mini player
- **WHEN** the global mini player appears or disappears in `AppNavigation`
- **THEN** the nav shell column animates height change via `animateContentSize()`

#### Scenario: Mini player seek track changes height
- **WHEN** the mini player seek track appears or disappears
- **THEN** the mini player bar container animates height via `animateContentSize()` or equivalent expand/shrink visibility transition
