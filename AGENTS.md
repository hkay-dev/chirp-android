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
- All migrations must be registered by appending them to `Migrations.ALL` in `Migrations.kt`
- Migration tests live in `data/src/androidTest/java/dev/chirpboard/app/data/db/MigrationTest.kt`

**Never use:**
- `fallbackToDestructiveMigration()` - destroys user data
- `fallbackToDestructiveMigrationOnDowngrade()` - also destroys data

### Schema Versioning
When modifying the database schema:
1. Increment `version` in `@Database` annotation
2. Create a new `Migration` object in `Migrations.kt`
3. Append the migration to `Migrations.ALL` in `Migrations.kt`
4. Write a migration test to verify the upgrade path
5. Document the schema change in the `AppDatabase.kt` class comment
