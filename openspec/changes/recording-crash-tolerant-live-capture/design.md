## Context

M4A and MP3 live capture segments are not equally recoverable while still open. A process kill can leave the active encoded segment without the container metadata needed for playback, even though the session journal and DB row survive. WAV/PCM segments have a fixed header that can be maintained while streaming and are already accepted by recovery validation.

## Decision

Use WAV as the internal live-capture segment format for service-owned app/widget recordings. Keep the final export filename based on `AudioSettingsStore.currentOutputFormat()`. At finalize time, concatenate WAV segments when needed and encode the resulting WAV source to the configured export format. If the configured format is WAV, the export is a WAV copy or WAV concat.

## Alternatives Considered

- Rotate M4A segments more frequently: rejected because it reduces but does not remove the crash window.
- Keep M4A capture and copy checkpoints: rejected because a copied unfinalized M4A remains unplayable.
- Add a custom raw PCM journal: rejected for this change because WAV segments give the same crash-tolerant behavior while preserving existing validators and recovery paths.

## Risks

- Finalization for M4A/MP3 now performs an encode pass from WAV. This shifts some work to background finalize, but avoids unrecoverable active container loss.
- Existing old sessions with M4A/MP3 segment files must remain supported. The concatenator keeps the legacy mux/copy paths for matching encoded segment formats.

## Leak Review

LeakCanary database entries on the Samsung showed stale `RecordingService` retention through:

- `AudioInputDeviceSelector.onActiveDeviceLost`: fixed by storing a weak service reference in the singleton callback and clearing the listener on destroy.
- `RecordingStateManager.stoppingTimeoutHandlers`: the current handler owner is `KeyboardSessionCoordinator`, not `RecordingService`; no current app recording service capture remains.
- `InputMethodManager.mNextServedView`: keyboard popup retention, unrelated to app recording persistence.

## Rollout

1. Add the OpenSpec proposal.
2. Switch service-owned live segment creation, resume, and rotation to WAV.
3. Add WAV segment export encoding for M4A/MP3 final files.
4. Add tests and run targeted unit/build verification.
