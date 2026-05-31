# Design

## Non-Destructive Entity Writes

Parent entities such as recordings, profiles, and tags must not be persisted with conflict strategies that delete and reinsert rows. Repository APIs should distinguish:

- create operations that fail on duplicate IDs unless explicitly idempotent
- field updates that mutate only changed columns
- relationship replacement APIs that run in transactions and validate referenced IDs

This avoids relationship loss from `REPLACE` behavior on SQLite-backed foreign keys.

## Guarded Recording Transitions

Recording status mutations should live behind repository methods that declare valid source states and return an explicit result:

- `TransitionApplied`
- `AlreadyTerminal`
- `RejectedStaleState`
- `MissingRecording`

Callers that finalize, retry, recover, or fail recordings must handle rejected transitions without overwriting newer state.

## Room Schema History

KSP Room arguments should set a stable schema output directory such as `data/schemas`. Schema files must be committed when `AppDatabase.version` changes. Migration tests should use committed schemas and `runMigrationsAndValidate`.

## Normalized Profile Default Tags

Replace CSV-backed `Profile.defaultTagIds` with a join table:

```text
profile_default_tags(
  profileId TEXT NOT NULL,
  tagId TEXT NOT NULL,
  PRIMARY KEY(profileId, tagId),
  FOREIGN KEY(profileId) REFERENCES profiles(id) ON DELETE CASCADE,
  FOREIGN KEY(tagId) REFERENCES tags(id) ON DELETE CASCADE
)
```

The migration should:

- create the relationship table
- parse legacy CSV values
- insert only tag IDs that still exist
- rebuild the profile table without the CSV column if required

Profile default assignment should happen in one transaction when creating the initial recording row.

## Bounded Queries

Repository query surfaces must own limits and chunking:

- search calls require a limit and stable ordering
- `IN (...)` enrichment calls chunk large IDs below SQLite bind limits
- bulk delete and bulk update APIs chunk while preserving transactional semantics where needed

The UI should not need to know database bind limits.
