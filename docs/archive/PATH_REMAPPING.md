# Archive path remapping (post core split)

Historical audit reports under this folder cite paths from before the layered core refactor. Use this table when reading archived findings.

## Module layout (current)

| Old path prefix | Current module | Notes |
|-----------------|----------------|-------|
| `core/src/main/java/.../recording/` | `core-contracts/src/main/java/.../recording/` | State, commands, guards |
| `core/src/main/java/.../transcription/` | `core-contracts/src/main/java/.../transcription/` | Queue contracts, outcomes |
| `core/src/main/java/.../util/` | `core-contracts/src/main/java/.../util/` | Time/duration helpers |
| `core/src/main/java/.../reliability/` | `core-contracts/src/main/java/.../reliability/` | Event logger |
| `core/src/main/java/.../modelreadiness/` | `core-contracts/src/main/java/.../modelreadiness/` | Speech model gate contracts |
| `core/src/main/java/.../llm/` | `core-contracts/src/main/java/.../llm/` | `RecordingTextEnrichment` interface |
| `core/src/main/java/.../audio/` (capture) | `core-audio/src/main/java/.../audio/` | Focus, recorder, settings |
| `core/src/main/java/.../storage/` | `core-audio/src/main/java/.../storage/` | e.g. `AllFilesAccessRequester` |
| `core/src/main/java/.../preferences/` | `core-audio/src/main/java/.../preferences/` | `KeyboardPreferences` |
| `core/src/main/java/.../ui/` | `core-ui/src/main/java/.../ui/` | Compose components, theme |
| `core/src/main/java/.../audio/RecordingPlayback*` | `core-playback/src/main/java/.../playback/` | Renamed package May 2026 |
| `core/src/main/java/.../ui/playback/` | `core-playback/src/main/java/.../ui/playback/` | Mini/full player composables |

## Common file moves

| Archived reference | Current location |
|--------------------|------------------|
| `feature-keyboard/.../VoiceRecorder.kt` | `core-audio/.../audio/recorder/VoiceRecorder.kt` |
| `feature-keyboard/.../AudioEncoder.kt` | `core-audio/.../audio/recorder/AudioEncoder.kt` |
| `core/.../ScreenScaffold.kt` | Removed; see `ChirpLeafScaffold` / `ChirpSettingsHubScaffold` in `core-ui` |
| Gradle `project(":core")` | Explicit `core-contracts`, `core-audio`, `core-ui`, `core-playback` per consumer |

The `:core` aggregator module was removed in commit `f8b2581`.
