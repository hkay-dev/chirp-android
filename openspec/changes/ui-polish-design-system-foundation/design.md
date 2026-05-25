# Design: UI polish — design system foundation

## Context

`core-ui` already ships `ChirpLeafScaffold`, `ChirpSettingsHubScaffold`, `SettingsSectionHeader`, `SettingsListItem`, and `ChirpShapes`. The main Settings hub (`SettingsScreen`) adopted `ChirpSettingsHubScaffold`; six leaf settings screens did not. Each leaf screen copies ~40 lines of `Scaffold` + `LargeTopAppBar` + scroll behavior + colors.

Parallel duplication exists for:
- Dropdown enum pickers (`InputDevicePolicyListItem`, `RecordingQualityListItem`, `ProcessingModeListItem`)
- Connection/status rows (LLM API key, Obsidian vault access, Transcription model card header)
- Action button rows (Save / Test / Clear patterns)
- Inline loading in `OutlinedButton` (LLM test connection, Transcription download)

Theme layer: `ChirpTheme` and `KeyboardTheme` are near-identical; both pass `Typography()` defaults and ignore `ChirpShapes` in `MaterialTheme.shapes`. `ChirpShapes` is referenced ad hoc in recording cards and keyboard UI.

Accessibility strings: `core-ui/strings.xml` defines `desc_navigate_back`; `shared_ui_strings.xml` and `app/strings.xml` define `desc_back` ("Back"). Scaffolds use the former; most leaf screens use the latter.

Icon inconsistency: Settings hub standardized on `Icons.Rounded`; leaf screens and LLM sections still use `Icons.Default`.

## Goals / Non-Goals

**Goals:**

- Single source of truth for leaf settings scaffold, dropdown rows, status rows, action rows, and inline loading.
- Wire typography and shapes into `MaterialTheme` once; keyboard inherits via alias.
- Migrate six named settings screens to shared scaffolds without behavior changes.
- Document button hierarchy for settings forms.
- Standardize settings icon family and LLM `AutoAwesome` icon.

**Non-Goals:**

- Visual redesign of settings content (sliders, toggles, cards stay as-is beyond token wiring).
- Migrating Profile, Studio, or recording leaf screens in this change.
- Screenshot test overhaul (update only if tests break on resource/scaffold changes).
- New dependency modules or design-token code generation.

## Decisions

### 1. `ChirpSettingsDetailScaffold` API

Mirror `ChirpSettingsHubScaffold` API with additions for leaf-screen needs:

```kotlin
@Composable
fun ChirpSettingsDetailScaffold(
    title: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable (PaddingValues) -> Unit,
)
```

**Rationale:** Keeps hub vs. detail naming symmetric. Optional snackbar covers Dev Menu without forcing all settings screens to allocate host state.

**Alternative considered:** Extend `ChirpSettingsHubScaffold` with a `variant` enum — rejected; separate composables clarify intent and avoid unused snackbar params on hub.

### 2. `SettingsDropdownListItem` generic API

```kotlin
@Composable
fun <T> SettingsDropdownListItem(
    title: String,
    supportingText: String,
    options: List<T>,
    selectedOption: T,
    optionLabel: @Composable (T) -> String,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
)
```

**Rationale:** One implementation serves `AudioInputDevicePolicy`, `RecordingQualityPreset`, and nullable processing mode IDs. Call sites supply label lambda to keep strings module-local.

**Alternative considered:** Separate sealed wrappers per domain type — rejected as over-abstraction for three call sites.

### 3. `ChirpTypography` mapping

Define `ChirpTypography` object mirroring Material 3 type scale with selective overrides:

| Token | Override intent |
|-------|-----------------|
| `headlineMedium` | Settings large titles (scaffold) |
| `titleMedium` | List item headlines, section values |
| `bodySmall` | Supporting / help text |
| Others | Default Material 3 values unless visual QA finds drift |

Wire in `ChirpTheme`:

```kotlin
MaterialTheme(
    colorScheme = colorScheme,
    typography = ChirpTypography,
    shapes = chirpMaterialShapes, // mapped from ChirpShapes
    content = content,
)
```

Map shapes:

| `MaterialTheme.shapes` | `ChirpShapes` |
|------------------------|-----------------|
| `extraSmall` | `ExtraSmall` |
| `small` | `Small` |
| `medium` | `Medium` |
| `large` | `Large` |
| `extraLarge` | `ExtraLarge` |

**Rationale:** Composables already using `MaterialTheme.shapes.medium` (LLM result surfaces, transcription cards) gain consistency without import churn.

### 4. Back string consolidation

**Decision:** Keep `desc_navigate_back` ("Navigate back") as canonical in `core-ui/src/main/res/values/strings.xml`. Remove `desc_back` from `shared_ui_strings.xml`; update all references to `CoreR.string.desc_navigate_back`.

**Rationale:** Scaffolds already use the more descriptive a11y string; TalkBack benefit outweighs brevity of "Back".

**Alternative considered:** Rename to `desc_back` everywhere — rejected because scaffolds and newer code already standardized on `desc_navigate_back`.

### 5. `SettingsConnectionStatusRow`

Extract common ListItem + animated leading icon pattern:

```kotlin
@Composable
fun SettingsConnectionStatusRow(
    headline: String,
    supportingText: String? = null,
    status: SettingsConnectionStatus, // Configured, Error, Loading
    modifier: Modifier = Modifier,
)
```

LLM API key row, Obsidian vault access row, and Transcription model header adopt or compose with this. Transcription's richer card (progress bar, download actions) keeps a local wrapper but uses shared status icon slot via `ChirpInlineLoadingIcon`.

### 6. `SettingsActionButtonRow`

```kotlin
@Composable
fun SettingsActionButtonRow(
    primary: SettingsAction? = null,
    secondary: SettingsAction? = null,
    tertiary: SettingsAction? = null,
    modifier: Modifier = Modifier,
)

data class SettingsAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val loading: Boolean = false,
    val leadingIcon: ImageVector? = null,
)
```

**Rationale:** Encodes button hierarchy in one place; loading state delegates to `ChirpInlineLoadingIcon`.

### 7. `ChirpInlineLoadingIcon`

```kotlin
@Composable
fun ChirpInlineLoadingIcon(
    isLoading: Boolean,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp,
    contentDescription: String? = null,
)
```

Uses `Crossfade` + compact `CircularProgressIndicator` (16.dp, 2.dp stroke) matching LLM test button.

### 8. `KeyboardTheme` collapse

Replace body with:

```kotlin
@Composable
fun KeyboardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) = ChirpTheme(darkTheme = darkTheme, dynamicColor = dynamicColor, content = content)
```

Keep `KeyboardTheme` name as stable public API for `feature-keyboard` call sites; add `@Deprecated` KDoc only if we later want direct `ChirpTheme` usage — **not** deprecated in this change to avoid noise.

### 9. Icon standardization

| Surface | Before | After |
|---------|--------|-------|
| Settings hub | `Icons.Rounded.*` | unchanged |
| LLM hub + settings | mixed `Default.Star` | `Icons.Rounded.AutoAwesome` |
| Status icons | `Default.CheckCircle/Warning` | `Rounded.CheckCircle/Warning` |
| Obsidian folder actions | `Default.Folder` | `Rounded.Folder` |
| Transcription download/delete | `Default.*` | `Rounded.*` |

AutoMirrored icons (`ArrowBack`, `Label`) remain AutoMirrored variants.

### 10. Button hierarchy (documented convention)

| Role | Component | When |
|------|-----------|------|
| Primary commit | `Button` | Persists user input; single main CTA per section (Save, Download, Select Vault) |
| Secondary action | `OutlinedButton` | Reversible or verify actions (Test Connection, Change Vault) |
| Tertiary / low emphasis | `TextButton` | Clear, dismiss, optional cleanup |
| Destructive confirm | `Button` with error colors OR `OutlinedButton` | Only when design explicitly marks destructive — out of scope unless existing pattern |

**Rules:**
- At most one filled `Button` per `SettingsActionButtonRow`.
- Secondary actions show loading via `ChirpInlineLoadingIcon`; primary Save stays instant unless explicitly loading in future.
- `TextButton` never paired as the only action — always alongside primary or secondary.

Document in KDoc on `SettingsActionButtonRow` and this design section.

## Component placement (file map)

| Component | File |
|-----------|------|
| `ChirpSettingsDetailScaffold` | `core-ui/.../ChirpScaffolds.kt` |
| `SettingsDropdownListItem` | `core-ui/.../SettingsDropdownListItem.kt` |
| `SettingsConnectionStatusRow` | `core-ui/.../SettingsConnectionStatusRow.kt` |
| `SettingsActionButtonRow` | `core-ui/.../SettingsActionButtonRow.kt` |
| `ChirpInlineLoadingIcon` | `core-ui/.../ChirpInlineLoadingIcon.kt` |
| `ChirpTypography` | `core-ui/.../theme/ChirpTypography.kt` |
| Shape wiring | `core-ui/.../theme/Theme.kt` |

## Migration Plan

1. **Foundation first** — Add typography, shapes wiring, new composables, string consolidation in `core-ui`. No feature module changes until API stable.
2. **Theme alias** — Collapse `KeyboardTheme`; smoke-test IME in light/dark.
3. **Screen migrations** — One screen per commit-friendly PR slice:
   - Audio + Keyboard (dropdown extraction proof)
   - LLM (status row + action row + AutoAwesome)
   - Obsidian (status row + action row)
   - Transcription (inline loading + icons; partial status row)
   - Dev Menu (scaffold + snackbar)
4. **Cleanup** — Delete private duplicate composables; grep for `desc_back`, bespoke `LargeTopAppBar` in migrated files.
5. **Verification** — `./gradlew :core-ui:assemble :app:assembleDebug` + affected module unit/compose tests.

**Rollback:** Each screen migration is independently revertible; foundation components are additive until call sites switch.

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| Typography override shifts layout in dense keyboard UI | Test keyboard height-sensitive layouts; limit overrides to scale tokens that keyboard already inherits via Material defaults |
| Generic dropdown loses domain-specific menu logic | Keep `optionLabel` and `onOptionSelected` at call site; no ViewModel coupling in core-ui |
| Transcription model card too bespoke for full extraction | Use shared status icon only; defer full card extraction |
| String resource ID change breaks tests | Grep and update test `stringResource`/`hasText` references in same PR |
| `Icons.Rounded` missing equivalent for rare icons | Fall back to closest Rounded variant or keep AutoMirrored/Default only when Rounded absent |

## Open Questions

- Should `AboutScreen` migrate to `ChirpSettingsDetailScaffold` in this change or a follow-up? **Recommendation:** follow-up (not in scope list).
- Add `@Preview` composables for new settings components in `core-ui`? **Recommendation:** yes, lightweight previews for dropdown and action row.
