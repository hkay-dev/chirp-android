## ADDED Requirements

### Requirement: Atomic Recording Tag Replacement

Replacing a recording's tag set SHALL validate the requested tag IDs and persist the replacement atomically.

#### Scenario: Valid tag set

- **GIVEN** all requested tag IDs exist
- **WHEN** the recording tag set is replaced
- **THEN** the old assignments are removed and the new assignments are inserted in one transaction.

#### Scenario: Invalid tag

- **GIVEN** at least one requested tag ID does not exist
- **WHEN** the recording tag set is replaced
- **THEN** the repository rejects the update and leaves the existing assignments unchanged.

### Requirement: Tag Parent Updates Preserve Relationships

Editing tag metadata SHALL preserve existing recording and profile relationships.

#### Scenario: Edit assigned tag

- **GIVEN** a tag is assigned to recordings and configured as a profile default
- **WHEN** the tag metadata changes
- **THEN** the assignments and profile defaults remain present.
