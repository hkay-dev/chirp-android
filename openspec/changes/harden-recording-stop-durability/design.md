## Context

APP and WIDGET captures use `RecordingService`, session journals, hidden rotated segments, an in-progress `RECORDING` row, and background finalize work. KEYBOARD captures use inline PCM samples and optional persistence through `KeyboardInlineCapturePersistence`.

The in-progress database row is the only durable identity shared by recovery, Home stitching state, and finalize idempotency. Deleting it on finalize failure makes recovery weaker and can force duplicate-row or missing-linked-row behavior.

## Decisions

### Preserve Durable Handles

Recoverable finalize failures SHALL keep the in-progress row and journal. The failure is recorded through reliability events and journal metadata or equivalent recovery state. The row is deleted only for explicit cancel/discard/keep-files actions or after recovery determines there is no journal-referenced audio, checkpoint, or segment artifact.

### Single Segment Transition Owner

Stop, pause, resume, and timer rotation SHALL all enter one segment transition coordinator. Stop captures the final active segment path after native capture stops, commits that tail segment to the journal, then marks the journal `STOPPING` and enqueues finalize work.

### Hard Bounded Native Finalization

Capture engines SHALL expose a bounded stop result instead of relying on coroutine cancellation around a blocking native call. On timeout, the service fences the capture generation, attempts best-effort native release, preserves recovery handles, and ignores late native stop results.

### Non-Blocking Service Destroy

`RecordingService.onDestroy()` SHALL cancel periodic jobs and detach callbacks without `runBlocking`. If active capture still exists, it records enough durable state for startup recovery and schedules a bounded best-effort emergency stop through an owned collaborator.

### Awaitable Keyboard Persistence

`InlineCapturePersistence.persist()` SHALL not launch untracked durable work. Keyboard audio encoding and Room writes complete before `persist()` returns. Optional Obsidian export may run after the database handle exists and must not erase or roll back the saved keyboard recording.

## Risks

- Preserving `RECORDING` rows after finalize failure can leave Home showing stitching until recovery refreshes. Mitigate by surfacing recoverable sessions and by re-enqueueing or prompting deterministically.
- Hard timeout can leave native audio resources busy until Android releases them. Mitigate with generation fencing, best-effort release, and clear error state.
