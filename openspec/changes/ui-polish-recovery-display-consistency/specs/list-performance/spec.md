## ADDED Requirements

### Requirement: Home List Enrichment Reacts To Metadata-Only Updates

The Home list SHALL refresh enriched row data when transcript, summary, tag, profile, title, or processing metadata changes even if the recording list identity is unchanged.

#### Scenario: Transcript summary updates row

- **GIVEN** a recording remains in the same list position
- **WHEN** its transcript summary changes
- **THEN** the Home row updates without requiring a full list reload.

#### Scenario: Tag update refreshes row

- **GIVEN** a recording remains in the same list position
- **WHEN** its tag assignments change
- **THEN** the Home row displays the current tags.

#### Scenario: Search retains freshness

- **GIVEN** the user is viewing filtered search results
- **WHEN** metadata changes for a visible recording
- **THEN** the visible row refreshes while preserving the active search.

### Requirement: Reactive Enrichment Preserves List Performance

Reactive Home enrichment SHALL preserve stable item keys and SHALL batch related metadata loads.

#### Scenario: Stable keys are preserved

- **WHEN** enrichment metadata changes for a visible row
- **THEN** the row keeps the same stable item key.

#### Scenario: Batch enrichment avoids per-row polling

- **WHEN** metadata changes for multiple visible recordings
- **THEN** enrichment loads are batched by recording IDs rather than polling each row independently.
