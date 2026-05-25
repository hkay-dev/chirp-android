# Change: UI polish — nav shell stability

## Why

The app navigation shell and several high-traffic surfaces still swap content abruptly or fire duplicate navigation, producing visible jank and occasional routing instability. The global mini player pops in/out without height animation, shared-audio import replaces the entire nav host with a loading screen, Record Done can navigate twice, the voice-recognition sheet flashes blank on entry and tears down instantly on cancel, recovery prompts use unanimated dialogs, and the mini-player seek track appears/disappears without transition. These are polish gaps left after broader UI-smoothness work and directly affect first-run share-intake, recording completion, and keyboard dictation flows.

## What Changes

- **AppNavigation shell**: Wrap `RecordingMiniPlayerBar` in `AnimatedVisibility` (slide + fade) and apply `animateContentSize()` on the nav column so bottom inset changes animate when playback starts/stops.
- **Shared-audio import overlay**: Replace abrupt `LoadingState` swap with a scrim + centered loading card using `AnimatedVisibility`; keep underlying `NavHost` mounted so tab content does not unmount during intake.
- **Record Done navigation singularity**: Remove the duplicate navigation path — either `onStopRecording` or `LaunchedEffect(lastCompletedRecordingId)` drives `onRecordingComplete`, not both. Prefer the state-driven `lastCompletedRecordingId` path so navigation fires only after persistence completes.
- **VoiceRecognitionDialog lifecycle**: Remove the artificial 50ms entry delay; start visible immediately (or drive visibility from first frame without blank flash). Cancel path mirrors dismiss — animate exit, then invoke `onDismissComplete`. Wrap glow background and waveform in `AnimatedVisibility` for enter/exit.
- **Recovery dialogs**: Migrate session-recovery `AlertDialog` on `HomeScreen` and `RecordScreen` to `AnimatedAlertDialog` (already used elsewhere in the app).
- **Mini player seek track**: Wrap `MiniPlayerSeekTrack` in `AnimatedVisibility` with vertical expand/fade so the scrubber animates in when duration becomes available and out on stop/error.

## Capabilities

### New Capabilities

_None — all changes extend existing capability specs._

### Modified Capabilities

- `app-structure`: Global nav shell layout behavior (mini player inset, shared-audio overlay, single-path record completion routing).
- `ui-animations`: Overlay/scrim animation patterns, voice-recognition sheet transitions, animated alert dialog standard for recovery prompts.
- `recording-ui`: Record completion navigation contract, mini player seek-track visibility, session recovery dialog presentation.

## Impact

| Area | Files |
|------|-------|
| Nav shell | `app/src/main/java/dev/chirpboard/app/navigation/AppNavigation.kt` |
| Record routing | `app/src/main/java/dev/chirpboard/app/navigation/AppRecordingNavigation.kt` |
| Record screen | `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/ui/RecordScreen.kt` |
| Home screen | `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/ui/HomeScreen.kt` |
| Keyboard dictation sheet | `app/src/main/java/dev/chirpboard/app/VoiceRecognitionDialog.kt` |
| Mini player | `core-playback/src/main/java/dev/chirpboard/app/core/ui/playback/RecordingMiniPlayerBar.kt` |

**Dependencies**: `ChirpMotion` timing tokens (`core-ui`), existing `AnimatedAlertDialog` (`core-ui`). No database, API, or module graph changes.

**Risk surface**: Navigation timing (must not regress record→studio handoff); shared-audio overlay must remain modal and block interaction without breaking startup prompt gating.
