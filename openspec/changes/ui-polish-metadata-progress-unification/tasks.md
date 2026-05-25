# Tasks: Metadata and progress unification

## 1. Shared metadata infrastructure (`core-ui`)

- [x] 1.1 Create `RecordingSourceUi.kt` in `core-ui` with canonical `RecordingSource` → icon (`PhoneAndroid`, `Keyboard`, `Widgets`, `FileOpen`) and label string resource mapping
- [x] 1.2 Move `MetadataPillRow` and private `MetadataPill` from `feature-recording/ui/components/` to `core-ui/components/MetadataPillRow.kt`
- [x] 1.3 Refactor `MetadataPillRow` to use `RecordingSourceUi` for icon and label; confirm formatters are `formatRelative()` and `formatAsDuration()` only
- [x] 1.4 Update `feature-recording` imports to `dev.chirpboard.app.core.ui.components.MetadataPillRow`; delete old file location

## 2. Studio metadata unification

- [x] 2.1 Replace inline metadata `Surface` pills in `ProcessingStudioScreen.kt` with `MetadataPillRow(createdAtMs, durationMs, source)`
- [x] 2.2 Remove studio-local `formatForHeader()` / `formatAsHumanReadableDuration()` usage from metadata display; remove inline source icon `when` block
- [x] 2.3 Verify studio header metadata visually matches home list pills (shape, icons, date/duration strings)

## 3. Shared compact transcription progress (`core-ui`)

- [x] 3.1 Extract `TranscriptionProgressCopy`, `TranscriptionProgressKind`, status→kind/copy mapping, and `MorphingTranscriptionProgress` to `core-ui` (relocate or co-locate string resources)
- [x] 3.2 Add public `TranscriptionProgressBanner` wrapper in `core-ui` for compact usage
- [x] 3.3 Update `feature-studio/TranscriptionProgressUi.kt` to delegate to `core-ui` implementations (thin internal wrappers acceptable)
- [x] 3.4 Confirm studio header compact banner behavior unchanged after extraction

## 4. Home list processing indicator

- [x] 4.1 In `RecordingListItem` (`HomeScreenComponents.kt`), show `TranscriptionProgressBanner` when `item.status.transcriptionProgressKind() != null`
- [x] 4.2 Place compact progress below `MetadataPillRow` and above summary/stuck-recovery sections
- [x] 4.3 Ensure list item root does not use `animateContentSize`; verify scroll smoothness with processing items visible

## 5. Remove dead RecordingCard stack

- [x] 5.1 Run repo-wide search confirming zero references to `RecordingCard`, `RecordingCardHeader`, `RecordingCardContent`, `RecordingCardMenu`, `ProcessingIndicator`
- [x] 5.2 Delete `RecordingCard.kt`, `RecordingCardHeader.kt`, `RecordingCardContent.kt`, `RecordingCardMenu.kt`
- [x] 5.3 Compile `:feature-recording` and full app to confirm no broken references

## 6. StatsPillRow documentation and alignment

- [x] 6.1 Add KDoc to `StatsPillRow` clarifying it is for aggregate home stats only; reference `MetadataPillRow` for per-recording metadata
- [x] 6.2 Add KDoc to `MetadataPillRow` clarifying complementary relationship with `StatsPillRow`
- [x] 6.3 Confirm `StatsPillRow` total duration continues using `formatAsDuration()` (no behavioral change expected)

## 7. Verification

- [x] 7.1 Add or update Compose test: studio screen with loaded metadata renders `MetadataPillRow` content (date relative, duration formatted, source icon)
- [x] 7.2 Add or update Compose test: home list item in `TRANSCRIBING` status shows compact progress row
- [x] 7.3 Manual smoke: compare home list item and studio header for same recording — matching date, duration, source icon, and progress copy during transcription
- [x] 7.4 Run `./gradlew :core-ui:compileDebugKotlin :feature-recording:compileDebugKotlin :feature-studio:compileDebugKotlin` and relevant androidTests
