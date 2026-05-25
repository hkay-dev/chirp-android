# Codebase Pass 6 Fixes

## 1. Flow Replay / Cache Bugs
* **Defect:** `HomeRecordEntryViewModel` used `MutableSharedFlow<HomeRecordEntryEvent>(extraBufferCapacity = 1)` for UI navigation events. When a UI event is emitted, if there are no active collectors (e.g. during a configuration change or in the background), the event can be silently dropped. Alternatively, if a developer switches to `replay = 1` to fix the drop, it causes the event to replay continuously every time the screen recomposes.
* **Fix:** Migrated `_events` to use `Channel<HomeRecordEntryEvent>(Channel.BUFFERED)` and exposed it via `events = _events.receiveAsFlow()`. Sent events using `_events.send()`. This guarantees events are delivered exactly once without loss or multi-delivery on rotation.
* **File:** `app/src/main/java/dev/chirpboard/app/navigation/HomeRecordEntryViewModel.kt`

## 2. Heavy CompositionLocal Computations
* **Defect:** Scanned for `CompositionLocalProvider` providing heavy objects reallocation. None found. 
* **Fix:** Checked `KeyboardModule.kt` and UI injection points. App relies appropriately on standard `HiltViewModel` injection and state hoisting, with no abusive CompositionLocal structures. 

## 3. Unnecessary recompositions (Unstable Data Classes)
* **Defect:** Found multiple `@Composable` functions taking cross-module data classes (`Tag`, `Profile`, `Recording`) from the `data` module. Since the `data` module does not depend on Compose, these entities are treated as Unstable, causing components like `CompactTagChip`, `ProfileCard`, and list items in `HomeScreen`, `ProfileListScreen`, and `TagManagementScreen` to recompose excessively on scroll.
* **Fix:** 
    * Refactored `CompactTagChip` to take primitive `name: String` and `colorHex: String?` parameters. 
    * Created stable UI wrappers `@Stable data class ProfileItemState(val profile: Profile)` and `@Stable data class TagItemUiState(val tag: Tag)`. Updated list screens and item cards to exchange these stable wrapper classes.
    * Added `@Stable` directly to `RecordingDisplayItem` and `HomeStats` in `HomeViewModel.kt` (since this module has Compose dependencies). 
* **Files:**
    * `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/ui/HomeViewModel.kt`
    * `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/ui/HomeScreen.kt`
    * `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/ui/profile/ProfileListScreen.kt`
    * `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/ui/profile/ProfileCard.kt`
    * `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/ui/tag/TagManagementScreen.kt`

## 4. Overdraw / Modifier Misuse
* **Defect:** Audited usages of `Modifier.fillMaxSize()`. While widespread, components correctly place `Modifier.fillMaxSize()` *before* `.verticalScroll(rememberScrollState())` in the Modifier chain, effectively using `fillMaxSize` to measure against the parent's constraints, not the scrollable view's infinite constraints. The usages of `EmptyState` and `LoadingState` with `fillMaxSize()` were correctly isolated to top-level conditions or `AnimatedContent` blocks with bounded scaffolding parents.
* **Fix:** Checked combinations of `fillMaxSize` inside `LazyColumn` items and `verticalScroll` containers. Codebase correctly manages constraints. 
