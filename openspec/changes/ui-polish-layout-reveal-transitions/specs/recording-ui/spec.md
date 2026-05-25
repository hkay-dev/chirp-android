## ADDED Requirements

### Requirement: Home list animated chrome

Home screen conditional list header items and row extras SHALL animate height changes.

#### Scenario: Recovery and filter header rows
- **WHEN** recovery banner, stats row, processing filter chip, recover-stuck row, search results label, or filter-empty message appears or disappears
- **THEN** `PushDownReveal` wraps the row content

#### Scenario: Recording list item growth
- **WHEN** a list item gains or loses transcription progress banner, summary, stuck recovery text, or tag chips
- **THEN** the item column uses `animatePushDownLayout`
- **AND** each optional block uses `PushDownReveal`

#### Scenario: FAB quick start stack
- **WHEN** quick-start surface toggles visibility above the record FAB
- **THEN** `PushDownReveal` animates the stack
- **AND** FAB column uses `animatePushDownLayout`

### Requirement: Processing Studio animated chrome

Processing Studio conditional blocks below metadata SHALL animate without snapping the tab pager.

#### Scenario: Recovery and error blocks
- **WHEN** transcription recovery affordances or error surface appear
- **THEN** each block uses `PushDownReveal` inside the studio content column with `animatePushDownLayout`

#### Scenario: Title edit swap
- **WHEN** user enters or exits title edit mode
- **THEN** metadata header uses `AnimatedContent` with size spring

#### Scenario: Record profile badge
- **WHEN** active profile is shown on Record screen
- **THEN** badge uses `PushDownReveal`

## MODIFIED Requirements

### Requirement: Full-Screen Recording Interface

The app SHALL provide a dedicated full-screen recording interface that users navigate to when starting a new recording.

#### Scenario: Home search reveal
- **WHEN** user activates search on home
- **THEN** search field uses `PushDownReveal` below the top app bar
- **AND** list content is pushed down smoothly

#### Scenario: Studio player reveal
- **WHEN** processing completes enough to show the studio player
- **THEN** player region uses `PushDownReveal` in `StudioProcessingHeader`
- **AND** tab content below animates via parent `animatePushDownLayout`
