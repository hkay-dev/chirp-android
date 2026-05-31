# Tasks

- [x] 1.1 Add a `checkModuleBoundaries` verification task and wire it into `check`.
- [x] 1.2 Define forbidden module edges for keyboard, transcription, LLM, Obsidian, `core-ui`, and `core-contracts`.
- [x] 1.3 Remove `core-ui` imports of data module classes.
- [x] 1.4 Move shared display/domain values needed by UI into contracts or UI display models.
- [x] 1.5 Create a runtime implementation module for DataStore-backed state and Hilt bindings currently in contracts.
- [x] 1.6 Replace keyboard imports of transcription, LLM, and Obsidian implementations with ports.
- [x] 1.7 Replace transcription imports of LLM implementations with `RecordingTextEnhancementPort`.
- [x] 1.8 Add app-level Hilt bindings for the new ports.
- [x] 1.9 Refactor `RecordingService` into an Android service adapter around injected orchestrators.
- [x] 1.10 Run dependency guard tests and full Gradle checks.
