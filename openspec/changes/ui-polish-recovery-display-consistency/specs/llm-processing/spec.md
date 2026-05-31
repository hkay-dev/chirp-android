## ADDED Requirements

### Requirement: Enhancement Queue Failures Remain Visible And Recoverable

LLM enhancement queue states SHALL remain visible to recovery UI and SHALL provide recoverable outcomes for pending, stale active, and terminal failure states.

#### Scenario: Enqueue failure is shown

- **GIVEN** enhancement work fails before it starts
- **WHEN** the user opens Processing Studio
- **THEN** the UI shows the enhancement failure and exposes retry when safe.

#### Scenario: Stale enhancing returns recovery

- **GIVEN** a recording is marked as enhancing but no active worker owns it
- **WHEN** recovery diagnostics run
- **THEN** the result offers enhancement recovery.

#### Scenario: Terminal failure uses retry

- **GIVEN** enhancement work reaches a terminal failed state
- **WHEN** recovery actions are derived
- **THEN** the user gets a retry action tied to the enhancement phase.
