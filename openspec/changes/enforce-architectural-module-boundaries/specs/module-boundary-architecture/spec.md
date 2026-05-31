## ADDED Requirements

### Requirement: Feature Modules Communicate Through Ports

Feature modules SHALL depend on ports or contracts for cross-feature behavior rather than importing sibling feature implementations.

#### Scenario: Keyboard classpath is constrained

- **WHEN** module boundary checks run
- **THEN** the keyboard module has no direct dependency on transcription, LLM, or Obsidian implementation modules.

#### Scenario: Transcription enhancement classpath is constrained

- **WHEN** module boundary checks run
- **THEN** the transcription module depends on an enhancement port rather than an LLM implementation module.

#### Scenario: App binds implementations

- **GIVEN** a feature port has a concrete implementation
- **WHEN** the app graph is built
- **THEN** the app module provides the binding between the port and implementation.

### Requirement: Core UI Is Data Independent

The `core-ui` module SHALL NOT import data-layer entities, DAOs, repositories, or Room types.

#### Scenario: Shared UI compiles without data module

- **WHEN** `core-ui` is built
- **THEN** it uses UI display models or contracts and does not require the data module on its classpath.

### Requirement: Core Contracts Contain Contracts Only

The `core-contracts` module SHALL contain interfaces, commands, and simple value types, and SHALL NOT contain DataStore, Hilt, Android service runtime, or persistence implementations.

#### Scenario: Runtime state moves out

- **WHEN** module boundary checks inspect `core-contracts`
- **THEN** no DataStore-backed runtime state or Hilt binding implementation is present.

### Requirement: RecordingService Is A Service Adapter

`RecordingService` SHALL adapt Android lifecycle and intent events to injected recording collaborators and SHALL NOT own capture finalization, queue recovery, enhancement, export, or persistence orchestration directly.

#### Scenario: Service receives stop intent

- **WHEN** `RecordingService` receives a stop intent
- **THEN** it delegates stop handling to the recording orchestration collaborator and reports the collaborator outcome.

#### Scenario: Service is destroyed

- **WHEN** Android destroys the service
- **THEN** service cleanup delegates to collaborators without running long blocking persistence or queue work inside the lifecycle callback.

### Requirement: Dependency Guard Tests Enforce Boundaries

The build SHALL include module boundary checks that fail on forbidden dependency edges or package imports.

#### Scenario: Forbidden dependency is added

- **WHEN** a module adds a forbidden implementation dependency
- **THEN** `checkModuleBoundaries` fails with the violating module and dependency.

#### Scenario: Allowed port dependency is used

- **WHEN** a module depends on an allowed contract or port module
- **THEN** `checkModuleBoundaries` passes that dependency.
