# UI Recovery and Display Consistency

## Why

Processing and recovery UI state can drift from the actual queue state:

- `PENDING_ENHANCEMENT` is not consistently visible as a recoverable state.
- Processing Studio diagnostics can overwrite newer transcript or recording state after a suspended refresh.
- Diagnostics can remain stale when the status is unchanged but the error reason or ownership changed.
- Playback reveal scheduling can repeat for the same recording and audio path.
- Home list enrichment is primarily recording-list driven, so transcript or tag-only updates can appear late.

These are reliability and trust issues because users can lose retry affordances, see stale diagnostics, or watch the UI churn unnecessarily.

## What Changes

- Treat `PENDING_ENHANCEMENT` as a first-class recoverable state in UI and recovery contracts.
- Refresh diagnostics from recording ID, status, error reason, ownership, and enhancement metadata.
- Apply diagnostics only when they still match the current recording and patch only recovery fields.
- Dedupe playback reveal scheduling by recording ID and audio path.
- Make Home list enrichment reactive to transcript, tag, and metadata-only updates.

## Impact

- Affected specs: `recording-ui`, `transcription`, `llm-processing`, `list-performance`
- Affected modules: Processing Studio UI, transcription recovery contracts, Home list enrichment, playback state
- Risk: Low to medium. UI state contracts change, but the behavior is additive and testable with state-holder tests.
