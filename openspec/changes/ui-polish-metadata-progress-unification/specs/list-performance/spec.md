## ADDED Requirements

### Requirement: List item processing progress without layout penalty

Recording list items displaying transcription progress SHALL use lightweight compact progress UI that does not degrade list scroll performance.

#### Scenario: No animateContentSize on list items

- **WHEN** a home list item shows transcription progress
- **THEN** the list item root does not use `animateContentSize`
- **AND** progress visibility changes do not trigger full-list layout remeasurement beyond the affected item

#### Scenario: Stable list item keys unchanged

- **WHEN** a recording's status transitions from processing to completed
- **THEN** the LazyColumn item retains its recording ID key
- **AND** only the progress section and metadata update — the item is not recreated with a new key

#### Scenario: Progress composable disposal off-screen

- **WHEN** a processing list item scrolls off-screen
- **THEN** its progress composable is disposed with normal LazyColumn item lifecycle
- **AND** no global animation continues for off-screen items
