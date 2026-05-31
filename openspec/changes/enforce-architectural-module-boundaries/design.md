# Design

## Target Ownership

- Feature modules own their UI and feature-specific orchestration.
- Shared contracts define interfaces, commands, and simple value objects only.
- Runtime implementations live in implementation modules or app-level wiring.
- `core-ui` owns reusable UI primitives and display models, not repositories or Room entities.
- The app module binds concrete implementations to ports.

## Ports

Introduce narrow ports where modules currently import implementation details:

- `InlineTranscriptionPort`
- `ProcessingModePort`
- `TranscriptExportPort`
- `InlineCapturePersistence`
- `RecordingTextEnhancementPort`

Ports should expose feature behavior, not implementation classes.

## Core UI Decoupling

Move display values needed by shared UI to contracts or UI display models. `core-ui` must not import Room entities, repositories, DAOs, or data module models.

## Core Contracts Cleanup

Move DataStore-backed state, Hilt modules, and runtime implementation code out of `core-contracts` into an implementation module such as `core-recording-runtime` or app wiring. `core-contracts` keeps value types, command contracts, and interfaces.

## RecordingService Boundary

`RecordingService` should:

- receive Android intents and lifecycle callbacks
- delegate capture/session orchestration to injected collaborators
- publish notifications through a collaborator
- avoid owning queue, export, recovery, or enhancement details directly

## Dependency Guard

Add a deterministic boundary check task that fails when forbidden module edges or packages appear. Wire it into `check`.
