# Data and Room Repository Integrity Hardening

## Why

The data layer still has integrity risks that can corrupt relationships or hide stale state under normal app usage:

- Room parent writes use conflict replacement in places where child rows can be deleted.
- Recording status changes are not consistently guarded against stale transitions.
- Room schemas are not committed for every version, which weakens migration review and validation.
- Search and bulk ID queries can exceed SQLite bind limits or return unbounded result sets.
- `Profile.defaultTagIds` stores relationships as CSV, which allows stale IDs and makes atomic assignment difficult.

These issues directly affect recording reliability, profile defaults, tag integrity, and migration safety.

## What Changes

- Replace destructive parent entity writes with non-destructive create/update/upsert repository APIs.
- Route recording status changes through guarded repository methods with allowed source states.
- Configure Room schema export and commit schema files for every database version.
- Bound search result sizes and chunk large ID lists in repository enrichment paths.
- Normalize profile default tags into a relationship table with migration coverage.
- Extend migration and repository tests for parent-child preservation, guarded transitions, profile defaults, and large ID sets.

## Impact

- Affected specs: `data-persistence`, `profiles`, `tags`
- Affected modules: `data`, repository callers in feature modules, migration tests
- Risk: Medium. The profile default tag migration changes persisted schema shape and needs migration coverage before implementation lands.
