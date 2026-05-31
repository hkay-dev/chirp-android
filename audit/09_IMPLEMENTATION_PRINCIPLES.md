# Implementation Principles

Use this file as the quality bar for every proposal and fix.

## Bias

The goal is clean, efficient, elegant code that makes recording, recovery, model readiness, transcription, and keyboard input easier to prove correct.

All sub-agents should run with thinking effort `5.5 xhigh` or the closest available runtime equivalent. If that setting isn't available, record the limitation and still require careful evidence-backed reasoning.

Prefer:

- One source of truth.
- One owner for each lifecycle transition.
- Small functions with direct names.
- Explicit state machines.
- Idempotent operations.
- Durable state for durable facts.
- Focused tests that prove behavior, not implementation trivia.
- Deleting stale code.
- Consolidating duplicate branches.

Avoid:

- New facades that only forward calls.
- Broad interfaces with one implementation and no real boundary.
- Duplicate state in UI, service, repository, and worker layers.
- "Just in case" retries.
- Silent fallbacks.
- Compatibility shims for old Android versions.
- Catch-all exception handling that marks work as successful.
- New queues when an existing serial owner can do the job.

## Fallback Policy

Be conservative about fallback methods. A fallback is a last resort, not a default hardening move.

Before adding a fallback, prove why these simpler options are insufficient:

1. Delete the alternate path.
2. Make one existing path authoritative.
3. Add idempotency to the existing path.
4. Add singleflight or a mutex around the existing path.
5. Persist the missing state.
6. Fail loudly with recovery UI instead of guessing.

A fallback is allowed only when all of these are true:

- It protects user audio, transcript text, keyboard text, or durable state.
- It has a concrete trigger.
- It has one owner.
- It has a bounded exit condition.
- It emits a reliability event or visible state when it runs.
- It has tests for primary path, fallback path, and fallback exit.

If a fallback cannot meet that bar, propose a simpler failure mode: block the action, show recoverable state, or leave a clear pending item.

## Edge Cases

Do not build a maze for edge cases. Handle edge cases by strengthening the main invariant when possible.

Good edge-case fixes:

- A stop operation becomes idempotent.
- A stale worker result is ignored by generation.
- A recognizer load is singleflight.
- A pending keyboard stop is durable and drains once.
- A recovery path checks journal, file, and DB state before touching audio.

Weak edge-case fixes:

- Multiple stop APIs that race each other.
- A fallback recognizer created somewhere else.
- A second cache that can disagree with the first.
- A "best effort" text insert into whatever field happens to be focused.
- A retry loop with no ownership or exit condition.

## OpenSpec Expectations

Every proposal should say how the fix reduces complexity or why complexity is unavoidable.

If the fix adds a new abstraction, the proposal must justify:

- Which duplication it removes.
- Which invariant it protects.
- Which caller complexity it reduces.
- Why an existing owner cannot absorb the behavior.

If the fix removes or consolidates code, call that out as a positive outcome.

## Implementation Expectations

For each confirmed issue:

1. Write or update the OpenSpec change.
2. Implement the smallest fix that proves the invariant.
3. Add or update targeted tests.
4. Run the narrowest relevant verification.
5. Run `./gradlew :app:assembleDebug` and confirm the debug APK exists.
6. Update `tasks.md`.
7. Record blockers only when implementation genuinely cannot proceed.

Do not leave a confirmed issue at "proposal only" unless blocked. Do not mark an implemented issue resolved without a successful APK build, except during a documented staged sequence where build failure is expected until the sequence is complete.
