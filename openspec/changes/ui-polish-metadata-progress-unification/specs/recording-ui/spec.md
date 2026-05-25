## ADDED Requirements

### Requirement: Shared recording metadata pills

The app SHALL display per-recording metadata (date, duration, source) using a single shared `MetadataPillRow` composable from `core-ui` on both the home recording list and the Processing Studio header.

#### Scenario: Home list item metadata

- **WHEN** a recording appears in the home list
- **THEN** date, duration, and source are shown via `MetadataPillRow`
- **AND** pills use `Date.formatRelative()` for the date label
- **AND** pills use `Long.formatAsDuration()` for the duration label

#### Scenario: Studio header metadata

- **WHEN** a recording is displayed in Processing Studio with loaded metadata
- **THEN** date, duration, and source are shown via the same `MetadataPillRow` composable as the home list
- **AND** inline bespoke metadata `Surface` pills are not used

#### Scenario: Source icon consistency

- **WHEN** metadata pills render the recording source
- **THEN** APP uses the PhoneAndroid icon
- **AND** KEYBOARD uses the Keyboard icon
- **AND** WIDGET uses the Widgets icon
- **AND** IMPORTED uses the FileOpen icon
- **AND** icon mapping is defined in one shared location consumed by all metadata pill call sites

---

### Requirement: Stats and metadata pill separation

The app SHALL keep aggregate home statistics (`StatsPillRow`) and per-recording metadata (`MetadataPillRow`) as separate composables with distinct responsibilities.

#### Scenario: Aggregate stats on home

- **WHEN** the home screen displays list-level statistics
- **THEN** recording count, total duration, and processing count use `StatsPillRow`
- **AND** per-recording date/duration/source are not embedded in `StatsPillRow`

#### Scenario: Per-recording metadata

- **WHEN** a single recording's metadata is displayed on a list item or studio header
- **THEN** `MetadataPillRow` is used
- **AND** aggregate counts or filter actions are not embedded in `MetadataPillRow`

#### Scenario: Shared duration formatting

- **WHEN** either component displays a duration value
- **THEN** the label uses `Long.formatAsDuration()` for consistency

---

### Requirement: Home list compact processing progress

The home recording list SHALL show a compact in-row transcription progress affordance for recordings in pipeline statuses, using the same progress semantics as the Processing Studio compact banner.

#### Scenario: Processing item shows compact progress

- **WHEN** a list item's status is PENDING_TRANSCRIPTION, TRANSCRIBING, PENDING_ENHANCEMENT, or ENHANCING
- **THEN** a compact progress row is visible below the item's metadata pills
- **AND** the progress UI uses the shared compact morphing transcription progress component (or equivalent extracted to `core-ui`)
- **AND** progress title and subtitle match the studio header for the same status phase

#### Scenario: Completed item hides progress

- **WHEN** a list item's status is COMPLETED or FAILED
- **THEN** no in-row transcription progress affordance is shown

#### Scenario: Scroll performance preserved

- **WHEN** the user scrolls the home recording list containing processing items
- **THEN** list scrolling maintains smooth performance per the list-performance spec
- **AND** progress composables do not attach layout-size animations to list item roots

---

### Requirement: Dead recording card UI removed

The app SHALL NOT retain unused `RecordingCard` composables or their associated private helpers once the home list uses `RecordingListItem` exclusively.

#### Scenario: No RecordingCard references

- **WHEN** the codebase is compiled after this change
- **THEN** `RecordingCard`, `RecordingCardHeader`, `RecordingCardContent`, and `RecordingCardMenu` are removed
- **AND** no remaining references to the deleted card stack exist in production source

#### Scenario: ProcessingIndicator retired

- **WHEN** processing progress is shown on the home list
- **THEN** the legacy `ProcessingIndicator` composable (spinner + linear bar from the card stack) is not used
- **AND** compact morphing progress is used instead
