# Finding Rules

## Severity

Critical:

- Plausible audio loss.
- Plausible transcript or keyboard text loss.
- Corrupt or partial persistence accepted as complete.
- Capture lock can remain stuck.
- Recovery can destroy the only copy of user audio.

High:

- Race condition can leave stale `RECORDING`, `STOPPING`, `TRANSCRIBING`, or queued state.
- Duplicate stop, duplicate persist, duplicate enqueue, or duplicate recovery is plausible.
- Model readiness can report ready while files or recognizer are not usable.
- Long recording can fail under normal screen-off, pause/resume, or background finalization conditions.
- Keyboard quick capture can desync from global stop routing.

Medium:

- Lifecycle sequencing is fragile but needs a rarer trigger.
- Timeout or retry policy is poorly bounded.
- Tests miss a meaningful reliability invariant.
- Abstraction hides ownership enough to make future changes risky.

Low:

- Local simplification.
- Stale docs or comments.
- Minor redundant wrappers.
- Naming or structure friction that does not affect behavior.

## Evidence

Every confirmed finding must include:

- `Severity`
- `Title`
- `Files`
- `Exact lines`
- `Failure mode`
- `Trigger path`
- `Why current code permits it`
- `Fix direction`
- `OpenSpec change`
- `Verification`

Do not inflate severity. Do not claim a race unless two paths can actually interleave.

## Suspicion Handling

Use suspicion status when:

- The path looks risky but line evidence is incomplete.
- The failure needs device-only confirmation.
- The code path depends on Android lifecycle behavior that is not obvious from static code.

Suspicions still need an OpenSpec proposal if they identify a missing invariant or missing test that should be resolved.

## Over-Engineering Standard

An over-engineering finding must name the cost:

- More state owners than necessary.
- More dependency layers than necessary.
- More queue abstractions than necessary.
- More wrappers than behavior.
- Harder test setup.
- Higher chance that lifecycle state diverges.
- Fallback paths whose trigger, owner, and exit condition are unclear.

Do not call something over-engineered only because it is unfamiliar.

## Fix Quality Standard

Prefer fixes in this order when they fully address the failure mode:

1. Remove dead or duplicate code.
2. Make one existing source of truth authoritative.
3. Narrow an existing state transition or ownership boundary.
4. Add a focused guard with a test.
5. Add a fallback path only when no simpler fix preserves user data or system correctness.

Fallbacks are expensive. A fallback is acceptable only when it has:

- A concrete trigger.
- A bounded lifetime or exit condition.
- One owner.
- No silent data loss.
- Observability when it runs.
- Targeted tests.

Reject fixes that make every caller handle every possible state if one internal invariant can make the impossible state unreachable.

## Edge Case Discipline

Do not optimize for imaginary edge cases at the cost of the main lifecycle. A valid edge case needs at least one of:

- Existing code path evidence.
- Android platform behavior evidence.
- A testable user action.
- A documented production risk from the repo docs.
