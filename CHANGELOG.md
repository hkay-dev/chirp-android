# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- State transitions now use atomic `update{}` pattern (prevents race conditions)
- KeyboardState now derived from RecordingState via mapping function
- Audio format validation uses safe accessors with defaults
- File I/O moved off main thread in ViewModels

### Fixed
- **MEDIUM**: Stopping state no longer gets stuck forever (5s timeout with auto-recovery)
- **MEDIUM**: Thread safety for accumulatedSegmentMs (AtomicLong) and amplitudeHistory (update{})
- **MEDIUM**: Thread safety for WhisperModelManager download state
- **MEDIUM**: User feedback when transcription blocked by low battery/storage
- **MEDIUM**: Malformed audio files no longer crash decoder (graceful validation)
- **MEDIUM**: SAF operations no longer block main thread (potential ANR fix)

### Added
- `AudioFocusManager` for managing audio focus during recording
- `PhoneCallHandler` for detecting phone calls (API 21+, TelephonyCallback on API 31+)
- `AudioEncoder` for encoding keyboard recordings to M4A format
- Minimum recording duration check (300ms) with user feedback

### Fixed
- **HIGH**: AudioRecord errors now detected and reported to user (was infinite loop)
- **HIGH**: Recording now requests exclusive audio focus (pauses other audio apps)
- **HIGH**: Recording stops automatically during phone calls
- **HIGH**: First 300ms of audio no longer lost (fixed race condition with CompletableDeferred)
- **HIGH**: ChunkBuffer performance improved from O(n²) to O(n) using ArrayDeque
- **HIGH**: Keyboard recordings now saved when "Save keyboard recordings" is enabled

### Security
- API keys now stored in EncryptedSharedPreferences
- Removed hardcoded default API key from source code
- Added automatic migration from plaintext to encrypted storage

### Fixed
- **CRITICAL**: Database no longer destroys user data on schema changes
  - Removed `fallbackToDestructiveMigration()` from DataModule
  - Implemented proper Room migrations infrastructure (`Migrations.kt`)
  - Added migration tests (`MigrationTest.kt`)

- **CRITICAL**: Recordings stuck in TRANSCRIBING status now recovered on app restart
  - Extended `processPendingOnStartup()` to also recover TRANSCRIBING status
  - Added automatic call in `ChirpApplication.onCreate()`
  - Added comprehensive logging for recovery operations

- **CRITICAL**: Long recordings no longer cause OOM crashes
  - Implemented `ChunkedAudioProcessor` with 30-second chunks and 2-second overlap
  - Memory usage reduced from ~76MB to ~4MB for 10-minute recordings
  - Added memory pressure monitoring and graceful OOM handling

- **HIGH**: Recording deletion now deletes DB record before file (prevents orphaned entries)
- **HIGH**: Obsidian export uses atomic write pattern (prevents corruption on crash)
- **HIGH**: Database operations wrapped in transactions where needed

### Changed
- Consolidated database to single source of truth in `data` module
  - Removed duplicate `AppDatabase` from `app` module
  - All database access now goes through `data` module repositories
  
- Removed orphaned Note feature (NotesScreen, Note entity, NoteDao)
  - Feature was never accessible in the app UI
  - No user data affected

- `ChirpRecognitionService` now properly uses `RecordingRepository` from data module
  - Removed direct database access
  - Consistent with app-wide architecture

### Added
- Migration infrastructure for safe database schema evolution
  - `Migrations.kt` with patterns and documentation
  - `MigrationTest.kt` for verifying upgrade paths
  - Schema documentation in `AppDatabase.kt`

- Streaming audio processing for transcription
  - `ChunkedAudioProcessor` for memory-efficient audio handling
  - `AudioDecoder.decodeAsFlow()` for streaming decode
  - 15 unit tests covering edge cases
