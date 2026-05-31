# Design

## Recovery Action Matrix

Processing Studio should derive its recovery actions from a shared queue-state contract:

- pending transcription: show recover or cancel if no active owner exists
- pending enhancement: show recover enhancement action if no active owner exists
- active transcription or enhancement: show owner-aware in-progress state and disable manual retry
- terminal failed state: show retry banner with the failure reason

The contract should be shared with transcription recovery code so UI and worker recovery accept the same states.

## Fresh Diagnostics

Diagnostic refresh keys should include:

- recording ID
- recording status
- failure reason or error message
- active work owner
- enhancement attempt metadata
- queue attempt metadata

Same-status error changes should refresh diagnostics.

## Stale Result Guard

Diagnostics should return a versioned result keyed to the recording snapshot that produced it. The state holder applies the result only when the current selected recording still matches the key. Applied diagnostics should update recovery fields without replacing transcript text, selected tab, edit state, playback state, or other user-visible content that may have advanced.

## Playback Reveal Dedupe

Reveal scheduling should track the last scheduled `(recordingId, audioPath)` pair. Repeated emissions for the same pair should not create another reveal job. Navigation away cancels outstanding reveal work. A changed audio path for the same recording schedules a fresh reveal.

## Reactive Home Enrichment

Home list enrichment should observe metadata that affects rows:

- transcript preview and summary
- tag assignments
- profile or title metadata
- processing status

The list should preserve stable row identity and batch enrichment rather than polling per row.
