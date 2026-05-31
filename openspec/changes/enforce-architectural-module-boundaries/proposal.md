# Enforce Architectural Module Boundaries

## Why

The module graph has drifted toward a monolith:

- Keyboard code imports transcription, LLM, and Obsidian implementation details.
- `core-ui` imports data-layer types.
- `core-contracts` contains runtime state, Hilt wiring, and DataStore-backed behavior.
- `RecordingService` owns too much orchestration beyond Android service adaptation.

This increases build coupling, makes reliability changes harder to isolate, and raises the chance that capture, transcription, enhancement, and export changes regress each other.

## What Changes

- Define strict module ownership rules and enforce them with dependency guard tests.
- Replace feature-to-feature implementation imports with small ports.
- Remove data-layer imports from `core-ui`.
- Move runtime state, DataStore, and Hilt bindings out of `core-contracts`.
- Shrink `RecordingService` into an Android service adapter that delegates orchestration.

## Impact

- Affected spec: `module-boundary-architecture`
- Affected modules: `keyboard`, `transcription`, `llm`, `obsidian`, `recording`, `core-ui`, `core-contracts`, app DI
- Risk: Medium. The work touches module dependencies and DI wiring but can be staged behind ports.
