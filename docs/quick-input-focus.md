# Quick-input delivery and editor visibility

Chirp exposes three different Android speech-input surfaces. Their result paths are not
interchangeable.

| Surface | Typical caller | Text handoff |
|---|---|---|
| `VoiceRecognitionActivity` | SwiftKey and apps launching `ACTION_RECOGNIZE_SPEECH` | Activity result or caller-supplied result `PendingIntent` |
| `ChirpRecognitionService` | Apps using `SpeechRecognizer` | `RecognitionListener.onResults` |
| `ChirpKeyboardService` | Editors using Chirp as the active keyboard | Direct `InputConnection.commitText` |

The quick speech sheet is the first surface. It does not own X's `InputConnection`. SwiftKey owns
the final insertion after Chirp returns the recognized text.

## What the device reports have ruled out

| Hypothesis | Controlled change | Result |
|---|---|---|
| Hiding the caller IME breaks the editor session | Replaced `stateAlwaysHidden` with `stateUnchanged` | Telegram stopped showing `Connecting`; keep this fix |
| Keeping the activity outside the IME target is enough | Added `FLAG_ALT_FOCUSABLE_IM` | X still needed a focus cycle |
| X needs the host window focused before result delivery | Made the sheet non-focusable and waited for WindowManager focus loss | No X behavior change; removed |
| A dedicated task and Google-shaped floating theme preserve the host editor | Added a voice-input task affinity and reference-style floating translucent theme | No X behavior change and the sheet looked worse; removed |
| The transcript is absent or arrives late | The text becomes visible after tapping the reply field | SwiftKey held the result until X created a new input session |
| Chirp's window or result payload causes the failure | Temporarily removed Chirp as the `RECOGNIZE_SPEECH` handler and repeated the test with Google's stock transcription activity | The same X inline-reply failure occurred |
| Hiding and showing SwiftKey can force a safe commit | Hid the IME at Chirp's terminal boundary | X returned with the keyboard hidden, the field unfocused, and no active `InputConnection`; removed |

These results rule out Chirp's transcription, result payload, task shape, and window theme as the
cause of the X inline-reply failure. Another recognition-activity flag permutation cannot repair an
editor connection owned by another process.

## Contract gap found in Chirp

`RecognizerIntent` supports two result channels. Chirp previously implemented only
`Activity.setResult`, even when the launch request supplied
`EXTRA_RESULTS_PENDINGINTENT`. Android's built-in Google voice-input activity checks that extra,
merges `EXTRA_RESULTS_PENDINGINTENT_BUNDLE`, and sends the result through the supplied token.

Chirp now follows the same split.

- A request without a result `PendingIntent` receives the normal activity result.
- A request with a result `PendingIntent` receives exactly one token delivery, with its caller
  bundle merged into the result.
- A cancelled token falls back to the activity result, since the token could not have delivered
  and silently dropping dictated text is unacceptable.
- The payload carries `EXTRA_RESULTS`, `EXTRA_CONFIDENCE_SCORES`, and the first result under
  `SearchManager.QUERY` for compatibility with older consumers.
- The activity logs the caller package, request flags, whether a result token was supplied, and the
  selected delivery channel. It never logs transcript content.

## Proven X and SwiftKey failure chain

Device traces and the installed SwiftKey implementation establish this sequence.

1. SwiftKey launches the selected `ACTION_RECOGNIZE_SPEECH` activity.
2. The recognizer returns a valid `RESULT_OK` payload. SwiftKey stores its first result in a
   one-item pending voice-result slot and finishes its helper activity.
3. SwiftKey does not commit inside the activity-result callback. Its
   `VoiceIntentApiTrigger #onStartInputView` path performs the later `InputConnection.commitText`.
4. X's inline **Post your reply** field returns unfocused. Android reports X as the focused window,
   but `InputMethodManager` has no served input connection and its `EditorInfo` has `inputType=0`.
5. SwiftKey therefore has no valid editor session in which to run its pending commit. The reply
   field remains semantically empty, not merely visually stale.
6. Tapping the reply field creates a new active input session. SwiftKey logs
   `VoiceIntentApiTrigger #onStartInputView`, commits the saved result, and the field immediately
   contains the transcript.

The controlled Google run produced the same sequence. The bug is the interaction between X's
inline Compose editor lifecycle and SwiftKey's deferred voice-result bridge. Chirp cannot restart
or commit through another app's private `InputConnection` under the standard recognition-activity
contract.

## Opt-in X reply compatibility mode

Chirp now offers a separately enabled accessibility service for this one proven failure. It keeps
the activity result authoritative and never creates a second text-delivery path. Its only job is to
recreate the input session that SwiftKey is waiting for.

The service follows this contract.

1. Android leaves it disabled until the user enables **Chirp X Reply Compatibility** in system
   accessibility settings. Chirp's Keyboard Settings page links there and reports the live state.
2. The service receives events only from `com.twitter.android` and remembers metadata only for the
   resource ID ending in `:id/post-detail-reply-text-field`.
3. The recognition activity snapshots that fresh target only when its caller is stable or beta
   SwiftKey.
4. A recovery is armed only after an immediate `RESULT_OK` activity result, including fallback
   from a cancelled result token. A live result `PendingIntent`, an error, a cancellation, or an
   animated dismissal cannot arm it.
5. The service waits up to five seconds for the same X window and the same visible editable node.
   It performs at most one clear-focus, input-focus, and click cycle, re-resolving the node between
   focus changes because Android accessibility nodes can become stale.
6. A real user tap ends pending recovery. A new quick-input session supersedes any older request.
7. The service never reads node text, calls `ACTION_SET_TEXT`, uses the clipboard, dispatches a
   gesture, filters keys, or logs transcript content.

If any guard fails, the service does nothing. SwiftKey keeps its one pending voice result, so the
existing manual tap and Chirp's durable result fallback remain available.

Other structural options remain possible. A voice IME subtype could directly serve keyboards that
support one, and using Chirp as the active IME continues to give Chirp direct `InputConnection`
ownership. Neither changes SwiftKey's current `ACTION_RECOGNIZE_SPEECH` path.

## Live diagnostic procedure

1. Clear logcat and start one quick-input dictation from X.
2. Read `VoiceRecognitionActivity` logs for `pendingResult` and the final channel.
3. Capture `dumpsys input_method` and `dumpsys window` immediately after the sheet closes.
4. Take a UI hierarchy dump before touching the editor. For the proven X inline-reply case, the
   field is unfocused and empty, and `InputMethodManager` has no served connection.
5. Repeat twice in the same composer. The second result catches stale `InputConnection` reuse that a
   one-shot test misses.
6. Enable the compatibility service and repeat. Confirm the field refocuses, SwiftKey stays visible,
   and the transcript appears once without a tap.
7. Repeat a cancellation, transcription error, non-X quick input, X tweet composer, and manual-tap
   race. Confirm the service performs no automated focus cycle in each excluded case.

## Permanent behavior

- Request unchanged soft-input visibility.
- Keep the screen awake for the entire quick-input window.
- Preserve the established Chirp bottom-sheet appearance.
- Deliver through the caller-selected Android result channel exactly once.
- Finish successful recognition immediately.
- Keep X focus recovery separately opt-in, metadata-only, target-specific, and bounded.
- Keep the existing capture-persistence and transcription-rescue paths unchanged.
