# OpenSpec Writing Rules

## Folder Naming

Use kebab-case names under `openspec/changes/`.

Examples:

- `recording-stop-state-generation-hardening`
- `keyboard-input-connection-safety`
- `model-readiness-singleflight`
- `long-recording-finalize-timeout-scaling`
- `recording-recovery-journal-db-parity`

## Required Files

Each change must include:

- `proposal.md`
- `tasks.md`
- `design.md` when the fix has sequencing, lifecycle, concurrency, migration, or tradeoff complexity

Add spec deltas when behavior changes:

- `openspec/changes/<change>/specs/recording/spec.md`
- `openspec/changes/<change>/specs/recording-stability/spec.md`
- `openspec/changes/<change>/specs/keyboard-recording/spec.md`
- `openspec/changes/<change>/specs/keyboard-service/spec.md`
- `openspec/changes/<change>/specs/queue-management/spec.md`
- `openspec/changes/<change>/specs/transcription/spec.md`
- `openspec/changes/<change>/specs/data-persistence/spec.md`
- `openspec/changes/<change>/specs/security/spec.md`

Use the capability that best owns the behavior. Do not duplicate the same requirement across many specs unless each capability needs a distinct invariant.

OpenSpec is the required planning artifact, not the finish line. After creating or updating the proposal, implement every unblocked confirmed issue owned by the change, update `tasks.md`, and run targeted verification.

Proposals should bias toward simpler ownership and fewer paths. If a proposal adds a fallback, it must explain why deletion, consolidation, singleflight, a stricter invariant, or a focused guard is insufficient.

## `proposal.md` Template

```markdown
## Why

<Concrete issue and user risk.>

## What Changes

- <Behavior or implementation change>
- <Test or validation change>

## Capabilities

### New Capabilities

- `<capability>`: <new behavior>

### Modified Capabilities

- `<capability>`: <changed behavior>

## Impact

- Modules:
- Depends on:
- Verification:
```

## `design.md` Template

```markdown
## Context

<Current behavior and ownership.>

## Decision

<Chosen design.>

## Alternatives Considered

- <alternative>: <why not chosen>

## Risks

- <risk>

## Fallback Policy

- Fallback added: yes | no
- Trigger:
- Owner:
- Exit condition:
- Observability:
- Tests:

## Rollout

1. <step>
2. <step>
```

## `tasks.md` Template

```markdown
## 1. Implementation

- [ ] 1.1 <task>
- [ ] 1.2 <task>

## 2. Tests

- [ ] 2.1 <test>
- [ ] 2.2 <test>

## 3. Verification

- [ ] 3.1 <command or manual check>
- [ ] 3.2 `./gradlew :app:assembleDebug`
```

## Spec Delta Template

```markdown
## ADDED Requirements

### Requirement: <name>

The system SHALL <behavior>.

#### Scenario: <scenario>

- **WHEN** <trigger>
- **THEN** <outcome>
```

Use `MODIFIED Requirements` when changing an existing requirement. Use `REMOVED Requirements` only when deleting behavior.

## Index

Update `openspec/changes/AUDIT_INDEX.md` with every confirmed issue:

```markdown
| Priority | Gap summary | Change folder | Status |
|----------|-------------|---------------|--------|
| P1 | <summary> | [change-name](./change-name/) | proposed |
```
