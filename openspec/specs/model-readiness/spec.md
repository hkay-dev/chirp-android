# model-readiness Specification

## Purpose
Defines authoritative speech-model readiness behavior for local transcription startup and model lifecycle changes.

## Requirements

### Requirement: Readiness Invalidation

The shared speech-model readiness gate SHALL expose an invalidation path for destructive model changes.

#### Scenario: Model files are deleted

- **WHEN** model deletion succeeds
- **THEN** cached Ready state and stale in-flight verification results are cleared before readiness is evaluated again.

#### Scenario: Model invalidation races readiness verification

- **WHEN** invalidation occurs while readiness verification is in flight
- **THEN** the in-flight result SHALL NOT report stale Ready to callers
- **AND** callers receive a fresh readiness result from the post-invalidation generation.

### Requirement: Complete Directory Readiness

Model readiness SHALL be based on one complete model directory.

#### Scenario: Required files are split across directories

- **WHEN** persistent storage has some required model files and legacy storage has the rest
- **THEN** readiness remains not-ready because no single directory can be loaded.
