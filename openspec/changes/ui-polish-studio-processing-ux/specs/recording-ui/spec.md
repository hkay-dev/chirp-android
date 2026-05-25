## MODIFIED Requirements

### Requirement: Detail Screen Content Animation

Recording detail (Processing Studio) content changes SHALL animate smoothly without instant content swaps. Initial load for a **valid recording ID** SHALL present the studio shell (top bar, tabs, metadata placeholders) immediately rather than a full-screen blocking loader. Full-screen loading indicators SHALL be reserved for invalid or missing recording IDs only.

#### Scenario: Valid recording opens with skeleton shell
- **WHEN** user navigates to Processing Studio with a parseable recording UUID
- **AND** recording data has not yet arrived from the database
- **THEN** the top app bar, tab row, and metadata placeholders are visible
- **AND** a full-screen centered spinner does not replace the entire screen

#### Scenario: Invalid recording ID shows full-screen loading
- **WHEN** user navigates to Processing Studio with a missing, empty, sentinel (`-1`), or unparseable recording ID
- **THEN** the screen shows the full-screen loading state
- **AND** the studio scaffold is not rendered

#### Scenario: Transcript appears with animation
- **WHEN** transcription completes and text is available
- **THEN** transcript section enters using `AnimatedVisibility` with studio reveal/hide transitions (not alpha-only `graphicsLayer` fade)
- **AND** enter duration aligns with `ChirpMotion.STUDIO_REVEAL_MS`

#### Scenario: Loading state animates
- **WHEN** transcription is in progress
- **THEN** progress indicator and copy fade in with slide over `ChirpMotion.STUDIO_REVEAL_MS`
- **AND** the transcript tab body shows a processing fallback (progress panel or skeleton lines), not an empty content area

#### Scenario: Summary section animates open
- **WHEN** AI summary is generated
- **THEN** summary card expands vertically with fade over 300ms
- **AND** content below shifts smoothly (animateContentSize)

---

### Requirement: Audio Player State Animation

Sticky studio audio player SHALL animate all state changes for polished interaction. Player visibility SHALL defer until the record-to-studio handoff interval elapses, synchronized with playback controller preparation.

#### Scenario: Player slides in from bottom
- **WHEN** audio player becomes visible
- **THEN** player slides in from bottom edge over 300ms with fade

#### Scenario: Play/pause icon crossfades
- **WHEN** playback state toggles
- **THEN** icon crossfades between play and pause over 200ms

#### Scenario: Progress slider animates
- **WHEN** playback position updates
- **THEN** slider thumb position animates smoothly (100ms tween)
- **AND** no visible jumping between positions

#### Scenario: Skip buttons have press feedback
- **WHEN** user presses skip forward/backward
- **THEN** icon scales to 0.85x during press with spring return

#### Scenario: Player reveal aligns with handoff timing
- **WHEN** Processing Studio opens for a recording with available audio
- **THEN** the player slot becomes visible only after `ChirpMotion.RECORD_HANDOFF_MS` from studio open
- **AND** deferred playback preparation uses the same `RECORD_HANDOFF_MS` delay before calling `onStudioOpened`
- **AND** the player region maintains a minimum height during progress-to-player transitions so content below does not jump

## ADDED Requirements

### Requirement: Studio layout animation ownership

Processing Studio SHALL use a single `animateContentSize` modifier on the root content column. Nested header components SHALL NOT apply competing layout-size animations.

#### Scenario: Header progress and player swap
- **WHEN** transcription progress hides and the audio player appears (or the reverse)
- **THEN** layout height animates via the studio root column only
- **AND** no nested `animateContentSize` on the processing header causes double spring overshoot

#### Scenario: Pager height remains stable
- **WHEN** the processing header transitions between progress banner and player
- **THEN** the horizontal pager's height does not collapse to zero between phases
- **AND** a fixed minimum height is reserved for the player slot when either progress or player is shown

### Requirement: Studio tab pager prefetch

Processing Studio tab pager SHALL pre-compose adjacent pages to prevent first-switch visual flash.

#### Scenario: Adjacent tab pre-composed
- **WHEN** Processing Studio is displayed
- **THEN** `HorizontalPager` uses `beyondViewportPageCount = 1`
- **AND** switching between Transcript, Summary, and Chat tabs does not flash uninitialized content on first visit
