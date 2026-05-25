## ADDED Requirements

### Requirement: Global Mini Player Shell Integration

The app navigation shell SHALL animate global mini player visibility and reflow surrounding content without abrupt layout jumps.

#### Scenario: Mini player slides in when playback starts
- **WHEN** global playback becomes active and `shouldShowGlobalMiniPlayer` returns true
- **THEN** `RecordingMiniPlayerBar` enters with vertical slide and fade over 300ms
- **AND** the nav content area height adjusts via `animateContentSize()` rather than an instant jump

#### Scenario: Mini player slides out when playback stops
- **WHEN** global playback stops or mini player eligibility ends
- **THEN** `RecordingMiniPlayerBar` exits with vertical slide and fade over 260ms
- **AND** the nav content area expands smoothly to reclaim the space

#### Scenario: NavHost remains mounted during mini player transitions
- **WHEN** mini player visibility toggles
- **THEN** the current `NavHost` destination stays mounted and does not reset scroll or transient UI state

---

### Requirement: Shared Audio Import Overlay

Shared-audio intake SHALL present loading and failure states as animated overlays above the navigation shell rather than replacing the root content tree.

#### Scenario: Loading overlay preserves underlying screen
- **WHEN** shared-audio intake enters `Loading` state
- **THEN** a semi-opaque scrim fades in over the app
- **AND** a centered loading indicator and message are visible
- **AND** the `NavHost` remains composed underneath (not swapped out for a full-screen loading root)

#### Scenario: Loading overlay dismisses smoothly
- **WHEN** shared-audio intake completes and state returns to `Idle`
- **THEN** the scrim and loading card fade out over the standard visibility duration
- **AND** the underlying screen is immediately interactive without remount flash

#### Scenario: Failure overlay blocks interaction
- **WHEN** shared-audio intake enters `Failure` state
- **THEN** failure content appears on the same scrim overlay pattern as loading
- **AND** retry and dismiss actions remain reachable
- **AND** startup prompts remain suppressed until intake is idle (existing gating preserved)

---

### Requirement: Single-Path Recording Completion Navigation

Recording completion from the full-screen Record flow SHALL trigger exactly one navigation action to the post-record destination.

#### Scenario: Done navigates once after persistence
- **WHEN** user taps Done on an active recording and save completes successfully
- **THEN** the app navigates to Processing Studio for that recording exactly once
- **AND** no duplicate `navigate()` calls originate from both synchronous stop handlers and `lastCompletedRecordingId` observation

#### Scenario: Completion waits for persisted recording ID
- **WHEN** recording stop is initiated
- **THEN** navigation to Processing Studio uses the persisted recording ID published by `RecordingStateManager`
- **AND** navigation does not rely on a stale pre-stop `activeRecordingId` alone

#### Scenario: Back stack after completion
- **WHEN** navigation to Processing Studio occurs after Done
- **THEN** Home remains below Studio in the back stack (popUpTo Home, not inclusive)
- **AND** `launchSingleTop` prevents duplicate Studio entries for the same session
