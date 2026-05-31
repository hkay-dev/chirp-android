## ADDED Requirements

### Requirement: Pending Enhancement Manual Recovery

The processing recovery contract SHALL support manual recovery for pending enhancement work separately from pending transcription work.

#### Scenario: Contract supports pending enhancement recovery

- **GIVEN** a recording is pending enhancement
- **WHEN** recovery actions are derived
- **THEN** an enhancement recovery action is available.

#### Scenario: Active work is blocked

- **GIVEN** active transcription or enhancement work owns the recording
- **WHEN** manual recovery is requested
- **THEN** the request is rejected without enqueueing duplicate work.

#### Scenario: Wrong status is rejected

- **GIVEN** a recording is not in a recoverable processing status
- **WHEN** manual recovery is requested
- **THEN** the request is rejected with a non-destructive result.

### Requirement: Recovery Diagnostics Are Fresh For Same-Status Changes

Recovery diagnostics SHALL refresh when failure or ownership details change even if the high-level status remains unchanged.

#### Scenario: Failure reason changes

- **GIVEN** a recording stays in a failed status
- **WHEN** the failure reason changes
- **THEN** recovery diagnostics reflect the new reason.

#### Scenario: Enhancement diagnostics inspect enhancement work

- **GIVEN** a recording is pending or failed during enhancement
- **WHEN** recovery diagnostics run
- **THEN** diagnostics inspect enhancement queue metadata and report the correct recovery action.
