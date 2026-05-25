## Project Lifecycle (tend)

This project is managed by `tend`. Record significant events using `tend note`:

- **Breakthroughs**: When something clicks or a major milestone is reached
- **Pivots**: When changing approach, technology, or direction
- **Blockers**: When stuck on something - document what and why
- **Frustrations**: When things aren't working - helps identify patterns
- **Decisions**: When making significant architectural or design choices

Examples:
```bash
tend note "switched from REST to GraphQL - better fits nested data model"
tend note "BLOCKER: auth flow broken after upgrade, rolling back"
tend note "finally got caching working - was missing invalidation on write"
```

These notes become git commits, building a narrative for future analysis.
Quick fleeting thoughts go in `tend ramble` instead (temporary inbox).

## Database Architecture

### Single Source of Truth
The app uses a **single database** located in the `data` module:
- **Location**: `data/src/main/java/dev/chirpboard/app/data/db/AppDatabase.kt`
- **Database name**: `chirp.db`
- **All modules** must access the database through the `data` module's repositories

### Migration Strategy
**CRITICAL: No destructive migrations allowed.**

- Migrations are defined in `data/src/main/java/dev/chirpboard/app/data/db/Migrations.kt`
- Each schema change requires a new `Migration` class (e.g., `MIGRATION_1_2`)
- All migrations must be registered in `DataModule.kt`
- Migration tests live in `data/src/androidTest/java/dev/chirpboard/app/data/db/MigrationTest.kt`

**Never use:**
- `fallbackToDestructiveMigration()` - destroys user data
- `fallbackToDestructiveMigrationOnDowngrade()` - also destroys data

### Schema Versioning
When modifying the database schema:
1. Increment `version` in `@Database` annotation
2. Create a new `Migration` object in `Migrations.kt`
3. Add the migration to the builder in `DataModule.kt`
4. Write a migration test to verify the upgrade path
5. Document the schema change in the `AppDatabase.kt` class comment
