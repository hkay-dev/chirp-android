# Tasks

## Implementation

- [x] 1.1 Configure Room schema export and commit generated schemas for all database versions.
- [x] 1.2 Add `ProfileDefaultTag` entity, DAO methods, and repository APIs.
- [x] 1.3 Add the migration that backfills profile default tag rows and removes or deprecates CSV persistence.
- [x] 1.4 Replace destructive parent `REPLACE` writes for recordings, profiles, and tags.
- [x] 1.5 Add guarded recording status transition DAO/repository methods.
- [x] 1.6 Add explicit search limits and stable ordering to repository search APIs.
- [x] 1.7 Chunk large ID lists in profile, tag, transcript, and bulk mutation paths.
- [x] 1.8 Update callers to use relationship and transition APIs rather than direct parent writes.

## Validation

- [x] 2.1 Add repository tests proving parent updates preserve child rows.
- [x] 2.2 Add stale transition tests for finalize, recovery, retry, and failure paths.
- [x] 2.3 Add profile default tag create, edit, delete, cascade, migration, and atomic assignment tests.
- [x] 2.4 Add tests for ID lists larger than SQLite bind limits.
- [x] 2.5 Add search bound and ordering tests.
- [x] 2.6 Run full migration validation with committed schemas.
