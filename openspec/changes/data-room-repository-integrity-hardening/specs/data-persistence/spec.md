## ADDED Requirements

### Requirement: Non-Destructive Parent Entity Writes

Repository writes for parent entities SHALL preserve dependent child rows unless the operation explicitly deletes or replaces those relationships in the same transaction.

#### Scenario: Recording update preserves child rows

- **GIVEN** a recording has transcript, tag assignment, and queue metadata rows
- **WHEN** the recording title or status is updated
- **THEN** the child rows remain present and attached to the same recording ID.

#### Scenario: Profile update preserves relationships

- **GIVEN** a profile has default tag relationships
- **WHEN** profile metadata is edited
- **THEN** the default tag relationships remain present.

#### Scenario: Tag update preserves assignments

- **GIVEN** a tag is assigned to recordings and profiles
- **WHEN** the tag label or color is edited
- **THEN** existing assignments remain present.

### Requirement: Guarded Recording Status Transitions

Recording status mutations SHALL declare allowed source statuses and SHALL reject stale updates that would overwrite a newer or terminal status.

#### Scenario: Expected transition succeeds

- **GIVEN** a recording is in one of the allowed source statuses
- **WHEN** a repository transition is applied
- **THEN** the destination status is persisted and the result reports that the transition was applied.

#### Scenario: Stale transition is rejected

- **GIVEN** a recording has already moved to a newer or terminal status
- **WHEN** an older worker attempts to write a stale status
- **THEN** the repository rejects the transition and leaves the current status unchanged.

### Requirement: Committed Room Schema History

Every Room schema version SHALL have a committed schema file and migration validation SHALL use those schemas.

#### Scenario: Database version changes

- **WHEN** `AppDatabase` increments its version
- **THEN** the matching Room schema file is generated and committed with the migration.

#### Scenario: Migration tests run

- **WHEN** migration tests run
- **THEN** they validate the migrated schema against the committed Room schema.

### Requirement: Bounded Repository Queries

Repository query APIs SHALL enforce stable limits and chunk large ID lists below SQLite bind limits.

#### Scenario: Broad search is limited

- **WHEN** a search term matches more rows than the caller requested
- **THEN** the repository returns only the bounded, stably ordered result set.

#### Scenario: Bulk enrichment chunks IDs

- **WHEN** a caller requests enrichment for more IDs than SQLite can bind in one statement
- **THEN** the repository splits the query into bounded chunks and returns a complete combined result.

### Requirement: Migration Validation Covers Integrity Invariants

Migration tests SHALL verify relationship preservation and normalized relationship backfills, not only schema shape.

#### Scenario: Full migration path runs

- **WHEN** a database migrates from the oldest supported version to the newest version
- **THEN** existing recordings, transcripts, tags, profiles, and queue metadata are preserved.

#### Scenario: Normalized relationship is migrated

- **GIVEN** a legacy profile stores default tags as CSV values
- **WHEN** the migration runs
- **THEN** valid tag IDs are migrated into relationship rows and missing tag IDs are ignored.
