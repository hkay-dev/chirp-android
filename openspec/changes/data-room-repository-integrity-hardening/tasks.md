# Tasks

## Implementation

- [ ] 1.1 Configure Room schema export and commit generated schemas for all database versions.
- [ ] 1.2 Add `ProfileDefaultTag` entity, DAO methods, and repository APIs.
- [ ] 1.3 Add the migration that backfills profile default tag rows and removes or deprecates CSV persistence.
- [ ] 1.4 Replace destructive parent `REPLACE` writes for recordings, profiles, and tags.
- [ ] 1.5 Add guarded recording status transition DAO/repository methods.
- [ ] 1.6 Add explicit search limits and stable ordering to repository search APIs.
- [ ] 1.7 Chunk large ID lists in profile, tag, transcript, and bulk mutation paths.
- [ ] 1.8 Update callers to use relationship and transition APIs rather than direct parent writes.

## Validation

- [ ] 2.1 Add repository tests proving parent updates preserve child rows.
- [ ] 2.2 Add stale transition tests for finalize, recovery, retry, and failure paths.
- [ ] 2.3 Add profile default tag create, edit, delete, cascade, migration, and atomic assignment tests.
- [ ] 2.4 Add tests for ID lists larger than SQLite bind limits.
- [ ] 2.5 Add search bound and ordering tests.
- [ ] 2.6 Run full migration validation with committed schemas.
