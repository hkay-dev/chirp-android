## ADDED Requirements

### Requirement: Home list filter empty state

The home recordings list SHALL distinguish global empty (no recordings ever) from filtered empty (no rows match active filter or search).

#### Scenario: Filtered empty preserves shell
- **WHEN** user has one or more recordings total
- **AND** an active list filter or search yields zero display items
- **THEN** stats pills and list chrome remain visible
- **AND** an inline empty-filter message with clear-filter affordance is shown
- **AND** the first-run empty state is not shown

#### Scenario: Processing filter guard
- **WHEN** user taps the processing stat pill
- **AND** no recordings are in a processing pipeline status
- **THEN** the processing filter is not activated
- **WHEN** the processing filter is active
- **THEN** the pill shows selected styling and a dismissible processing filter chip is available

### Requirement: Home search field layout

The home search field SHALL remain fully visible when search mode is active.

#### Scenario: Search expands without clipping
- **WHEN** user activates search on the home screen
- **THEN** the search input renders in a full-width row below the top app bar
- **AND** the input is not clipped by collapsing top app bar scroll behavior
