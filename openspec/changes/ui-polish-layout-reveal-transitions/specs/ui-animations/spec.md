## ADDED Requirements

### Requirement: Push-down layout reveal

Vertical chrome that occupies layout space alongside other content SHALL use push-down reveal transitions so siblings animate smoothly.

#### Scenario: Push-down reveal enter
- **WHEN** conditional vertical chrome becomes visible (search field, player, banner, filter row, recovery block)
- **THEN** content enters with `expandVertically` from top combined with fade over `ChirpMotion.STUDIO_REVEAL_MS`
- **AND** the parent container uses `animateContentSize` with `ChirpMotion.layoutSizeSpring` so siblings are pushed without snapping

#### Scenario: Push-down reveal exit
- **WHEN** conditional vertical chrome becomes hidden
- **THEN** content exits with `shrinkVertically` toward top combined with fade over `ChirpMotion.STUDIO_HIDE_MS`

#### Scenario: Shared composable
- **WHEN** implementing push-down reveal in feature modules
- **THEN** callers SHOULD use `PushDownReveal` from `core-ui` rather than ad-hoc transition copies

### Requirement: Bottom-attached mini player reveal

The global recording mini player SHALL slide in from the bottom while the nav column animates height.

#### Scenario: Mini player show
- **WHEN** global mini player becomes visible
- **THEN** bar enters with `ChirpMotion.miniPlayerRevealTransition`
- **AND** nav shell column uses `animateContentSize(ChirpMotion.layoutSizeSpring)`

#### Scenario: Mini player hide
- **WHEN** global mini player hides
- **THEN** bar exits with `ChirpMotion.miniPlayerHideTransition`

### Requirement: In-place content swap transitions

Content occupying the same layout slot (skeleton ↔ transcript, empty ↔ list, keyboard state) SHALL use size-aware crossfade, not push-down chrome.

#### Scenario: Same-slot swap
- **WHEN** UI swaps between states in the same region without adding parallel chrome
- **THEN** `AnimatedContent` or crossfade with `layoutSizeSpring` on the container is used
- **AND** push-down reveal is NOT applied to both layers simultaneously

## MODIFIED Requirements

### Requirement: Interactive Feedback

All interactive elements SHALL provide immediate visual feedback on user actions.

#### Scenario: Layout chrome feedback
- **WHEN** user action causes new vertical chrome (search, filters, recovery, player)
- **THEN** appearance uses push-down reveal rather than instantaneous layout insertion
- **AND** disappearance uses matching hide transition
