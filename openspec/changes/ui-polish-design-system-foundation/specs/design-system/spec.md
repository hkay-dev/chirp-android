## ADDED Requirements

### Requirement: Settings detail scaffold

The app SHALL provide `ChirpSettingsDetailScaffold` in `core-ui` for leaf settings screens that need a collapsing large title, back navigation, and scroll-coordinated top bar.

#### Scenario: Leaf settings screen structure

- **WHEN** a leaf settings screen (e.g., Audio, Keyboard, LLM) is displayed
- **THEN** it uses `ChirpSettingsDetailScaffold` with a large collapsing title
- **AND** the back affordance uses the unified core-ui back content description string
- **AND** top bar colors match `ChirpSettingsHubScaffold` (surface / surfaceContainer on scroll)

#### Scenario: Optional snackbar host

- **WHEN** a leaf settings screen needs transient feedback (e.g., Dev Menu)
- **THEN** `ChirpSettingsDetailScaffold` accepts an optional `SnackbarHostState`
- **AND** renders a `SnackbarHost` when provided

#### Scenario: Scroll behavior passthrough

- **WHEN** screen content is vertically scrollable
- **THEN** the scaffold applies `nestedScroll` for the provided `TopAppBarScrollBehavior`
- **AND** title typography uses `MaterialTheme.typography.headlineMedium` (not ad-hoc `FontWeight.Bold`)

---

### Requirement: Settings dropdown list item

The app SHALL provide `SettingsDropdownListItem` in `core-ui` for enum- or option-picker rows with headline, supporting text, pill-style current value, and check-marked dropdown menu.

#### Scenario: Display current selection

- **WHEN** a settings row presents a discrete set of options (input device policy, recording quality, processing mode)
- **THEN** the row shows headline and supporting text via `ListItem`
- **AND** the trailing pill displays the human-readable label of the current selection
- **AND** tapping the row opens a `DropdownMenu`

#### Scenario: Select new option

- **WHEN** user selects an option from the dropdown
- **THEN** the menu dismisses
- **AND** the selected option shows a trailing check icon
- **AND** the host screen receives the new value via callback

#### Scenario: Duplicate removal

- **WHEN** this component is adopted
- **THEN** private dropdown list item implementations in Audio, Keyboard, and Processing Mode settings are removed
- **AND** no functionally equivalent private copy remains in feature modules

---

### Requirement: Chirp typography token

The app SHALL define `ChirpTypography` in `core-ui` and apply it to `MaterialTheme` via `ChirpTheme`.

#### Scenario: Theme wiring

- **WHEN** any screen wrapped in `ChirpTheme` renders text
- **THEN** `MaterialTheme.typography` reflects `ChirpTypography` overrides (not default empty `Typography()`)
- **AND** title styles used by scaffolds derive from theme tokens

#### Scenario: Keyboard theme parity

- **WHEN** the keyboard IME uses `KeyboardTheme`
- **THEN** typography matches `ChirpTheme` because `KeyboardTheme` delegates to it

---

### Requirement: Chirp shapes in MaterialTheme

The app SHALL map `ChirpShapes` scale values into `MaterialTheme.shapes` inside `ChirpTheme`.

#### Scenario: Shape token availability

- **WHEN** composables reference `MaterialTheme.shapes.small`, `medium`, or `large`
- **THEN** corner radii align with `ChirpShapes.Small`, `ChirpShapes.Medium`, and `ChirpShapes.Large` respectively
- **AND** direct `ChirpShapes` references remain valid for components needing explicit scale (chips, cards)

---

### Requirement: Unified back navigation content description

The app SHALL expose exactly one core-ui string resource for back-navigation icon content descriptions.

#### Scenario: Scaffold back affordance

- **WHEN** any `ChirpLeafScaffold`, `ChirpSettingsHubScaffold`, or `ChirpSettingsDetailScaffold` renders a back icon
- **THEN** it uses the canonical core-ui string (value: "Navigate back")

#### Scenario: Legacy string removal

- **WHEN** migration completes
- **THEN** duplicate `desc_back` entries in `app` and `shared_ui_strings.xml` are removed or aliased to the canonical resource
- **AND** all settings leaf screens reference the canonical core-ui string

---

### Requirement: Settings connection status row

The app SHALL provide `SettingsConnectionStatusRow` in `core-ui` for integration status display (configured / error / loading) with animated tint and leading status icon.

#### Scenario: Configured integration

- **WHEN** an integration is configured and healthy (LLM API key set, Obsidian vault accessible)
- **THEN** the row shows a primary-tinted success icon and status headline
- **AND** optional supporting text uses `onSurfaceVariant`

#### Scenario: Misconfigured integration

- **WHEN** an integration is missing or unhealthy
- **THEN** the row shows an error-tinted warning icon
- **AND** supporting text communicates the failure state

#### Scenario: Loading state

- **WHEN** status is indeterminate (e.g., model download in progress)
- **THEN** the leading slot MAY show `ChirpInlineLoadingIcon` or an animated loading indicator
- **AND** tint animates per Material motion guidelines

---

### Requirement: Settings action button row

The app SHALL provide `SettingsActionButtonRow` in `core-ui` for horizontal primary / secondary / tertiary action groups below settings forms.

#### Scenario: Primary and secondary actions

- **WHEN** a settings section offers Save and Test Connection actions
- **THEN** Save uses `Button` (filled primary)
- **AND** Test Connection uses `OutlinedButton`
- **AND** buttons are spaced consistently with 8.dp horizontal gap and 16.dp outer padding

#### Scenario: Destructive tertiary action

- **WHEN** a Clear or Remove action is available after configuration
- **THEN** it uses `TextButton`
- **AND** appears after primary/secondary actions in reading order

#### Scenario: Loading on secondary action

- **WHEN** a secondary action is in progress (connection test, download)
- **THEN** the button shows `ChirpInlineLoadingIcon` crossfading to spinner
- **AND** the button is disabled while loading

---

### Requirement: Inline loading icon for buttons

The app SHALL provide `ChirpInlineLoadingIcon` that crossfades between a leading icon and a compact `CircularProgressIndicator`.

#### Scenario: Idle state

- **WHEN** `isLoading` is false
- **THEN** the composable displays the provided icon at button-appropriate size (16–20.dp)

#### Scenario: Loading state

- **WHEN** `isLoading` is true
- **THEN** the composable crossfades to a compact circular progress indicator
- **AND** animation duration matches existing settings button patterns (~200ms)

---

### Requirement: Settings icon family convention

Settings surfaces SHALL use the `Icons.Rounded` icon family for list and section iconography unless an AutoMirrored variant is required.

#### Scenario: Settings hub icons

- **WHEN** user views the Settings hub list
- **THEN** all section icons use `Icons.Rounded.*`

#### Scenario: LLM feature icon

- **WHEN** LLM settings or hub entry displays a feature icon
- **THEN** it uses `Icons.Rounded.AutoAwesome`

#### Scenario: Leaf settings migration

- **WHEN** leaf settings screens are migrated
- **THEN** status and action icons previously using `Icons.Default.*` are updated to `Icons.Rounded.*` equivalents where available

---

### Requirement: Button hierarchy documentation

The design system SHALL document when to use `Button`, `OutlinedButton`, and `TextButton` in settings and forms.

#### Scenario: Primary commit action

- **WHEN** user commits configuration (Save, Select Vault, Download)
- **THEN** the action uses filled `Button`

#### Scenario: Secondary non-destructive action

- **WHEN** user runs a reversible side effect (Test Connection, Change Vault)
- **THEN** the action uses `OutlinedButton`

#### Scenario: Tertiary or low-emphasis action

- **WHEN** user clears optional configuration or dismisses without committing
- **THEN** the action uses `TextButton`

#### Scenario: Documentation location

- **WHEN** implementers need guidance
- **THEN** button hierarchy rules are recorded in the change design artifact
- **AND** referenced from inline KDoc on `SettingsActionButtonRow`
