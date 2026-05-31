## Context

`RecordingActiveStopCommands.stopActiveRecording()` is synchronous. When keyboard stop bridge is unavailable, it launches a new IO coroutine to persist `KeyboardPendingStopStore.enqueue()` and immediately calls the queued callback. The widget receiver has no ownership of that coroutine.

## Decision

Expose a suspending command for callers that need durability and have the widget receiver call it inside `goAsync()`. Keep a non-suspending wrapper only for existing call sites that do not need to await, implemented by launching the suspending path from an owned caller scope where possible.

## Alternatives Considered

- Keep the fire-and-forget coroutine and rely on DataStore speed: rejected because the failure is precisely process death before persistence.
- Move the pending-stop store into the widget module: rejected because origin-aware routing already belongs in shared recording control.

## Risks

- Widget stop now performs a coroutine before finishing the broadcast. The operation is a single DataStore write and should stay within receiver time limits.

## Fallback Policy

- Fallback added: no
- Trigger:
- Owner:
- Exit condition:
- Observability:
- Tests:

## Rollout

1. Add a suspending durable stop path.
2. Update widget receiver to use `goAsync()`.
3. Add targeted tests.
