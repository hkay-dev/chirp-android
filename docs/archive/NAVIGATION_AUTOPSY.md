> **Archive note:** Historical paths below reference the pre-split `:core` module. See [`PATH_REMAPPING.md`](PATH_REMAPPING.md) for current locations.
>

# NAVIGATION AUTOPSY

## The Bug
When a user finishes a recording and clicks the "Done" checkmark, the app successfully saves the recording but fails to navigate away from the `RecordScreen`. The user is permanently trapped on the "Saved" screen and the new Processing Studio is never shown.

## The Root Cause
In `RecordScreen.kt`, the transition to the next screen is driven by observing `lastCompletedRecordingId`:

```kotlin
    LaunchedEffect(lastCompletedRecordingId) {
        val recordingId = lastCompletedRecordingId
        if (recordingId != null) {
            viewModel.clearLastCompletedRecordingId() // <-- CAUSES CANCELLATION
            delay(200) // <-- COROUTINE CANCELLED HERE
            onRecordingComplete(recordingId.toString()) // <-- NEVER EXECUTED
        }
    }
```

The bug lies in the exact order of operations inside this `LaunchedEffect`:
1. `lastCompletedRecordingId` is the key to the `LaunchedEffect`. When a recording finishes successfully, it changes from `null` to a valid `UUID`.
2. The `LaunchedEffect` block runs.
3. The block immediately calls `viewModel.clearLastCompletedRecordingId()`, which sets the underlying state flow back to `null`.
4. Compose receives the new state (`null`) and triggers a recomposition of `RecordScreen`.
5. Compose notices that the `LaunchedEffect` key (`lastCompletedRecordingId`) has changed from `UUID` to `null`. It immediately **cancels** the currently running `LaunchedEffect` coroutine.
6. The coroutine is suspended at `delay(200)`. When cancelled, it throws a `CancellationException` and aborts.
7. `onRecordingComplete` is never reached.

## The Fix
We must `delay()` *before* modifying the state that the `LaunchedEffect` depends on.

Change `RecordScreen.kt` lines 175-182 to:

```kotlin
    LaunchedEffect(lastCompletedRecordingId) {
        val recordingId = lastCompletedRecordingId
        if (recordingId != null) {
            delay(200) // Brief delay for state to settle and show "Saved"
            viewModel.clearLastCompletedRecordingId()
            onRecordingComplete(recordingId.toString())
        }
    }
```

By delaying *before* clearing the ID, the suspension finishes undisturbed. When `clearLastCompletedRecordingId()` is finally called, it triggers the cancellation flow, but `onRecordingComplete` runs immediately (synchronously) on the very next line, successfully executing the navigation before the coroutine's cancellation can take effect.
