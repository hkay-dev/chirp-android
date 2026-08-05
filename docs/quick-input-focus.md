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
| Automatic accessibility focus recovery can repair the handoff | Added a narrow service that cycled focus after recognition | X still did not insert consistently, Gemini regressed, and the recognition window changed for the worse; removed and disabled on the test device |

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

## Structural options if the caller uses only activity results

1. Keep the implicit recognition activity for SwiftKey compatibility. This remains required because
   SwiftKey launches `ACTION_RECOGNIZE_SPEECH` rather than an auxiliary voice IME.
2. Add a separate auxiliary IME service with subtype mode `voice` for keyboards that support it.
   That service can finish any old composition and commit final text directly. It would improve
   HeliBoard, FlorisBoard, AnySoftKeyboard, and similar integrations, but it would not replace the
   SwiftKey path.
3. Keep `ChirpRecognitionService` for apps using `SpeechRecognizer`. It does not solve a caller that
   explicitly launches the activity.
4. Do not bring back automatic focus cycling or make accessibility the main result path. The
   controlled trial made normal insertion less reliable outside X as well.

For the proven X and SwiftKey failure, the standard contract is exhausted. Any automatic workaround
must cross that boundary explicitly. The least risky choices are:

1. Keep activity-result delivery authoritative. With the optional reliable-paste service disabled,
   show the latest non-secure result in a 30-second notification with copy actions.
2. With the optional service enabled, verify the standard result in the original editor. Show a
   tap-to-paste notification only when the text is still missing. A notification tap may focus the
   captured field once so SwiftKey can consume its pending result, then use `ACTION_SET_TEXT` only
   if the field is still unchanged. A changed, missing, sensitive, or expired target falls back to
   clipboard copy.
3. Use Chirp as the active IME, where it owns the `InputConnection` and can commit directly. This is
   the cleanest technical path, but it changes the user's keyboard workflow.

## Live diagnostic procedure

1. Clear logcat and start one quick-input dictation from X.
2. Read `VoiceRecognitionActivity` logs for `pendingResult` and the final channel.
3. Capture `dumpsys input_method` and `dumpsys window` immediately after the sheet closes.
4. Take a UI hierarchy dump before touching the editor. For the proven X inline-reply case, the
   field is unfocused and empty, and `InputMethodManager` has no served connection.
5. Repeat twice in the same composer. The second result catches stale `InputConnection` reuse that a
   one-shot test misses.

## Permanent behavior

- Request unchanged soft-input visibility.
- Keep the screen awake for the entire quick-input window.
- Preserve the established Chirp bottom-sheet appearance.
- Deliver through the caller-selected Android result channel exactly once.
- Finish successful recognition immediately.
- Keep the standard Android result path authoritative in every app.
- Never change another app's text automatically.
- When reliable paste is enabled, keep only an in-memory snapshot of the captured editor, its text,
  and its selection for the 30-second fallback window.
- Limit reliable paste to Gemini, X, and Telegram, and ignore password fields.
- Verify the returned text for 1.5 seconds. Suppress the notification when it arrived normally.
- Show `Tap to paste quick input` only when verification fails. The notification body pastes the
  same result sent through the Android result channel.
- Recheck the package, window, editor identity, and unchanged source text before a direct paste.
- Copy instead if the target is missing, changed, expired, or refuses `ACTION_SET_TEXT`.
- With reliable paste disabled or unavailable, post the normal copy notification only after the
  caller-selected result channel has completed.
- Replace the previous quick-input notification and expire the new one after 30 seconds.
- Offer `Copy original` and, when it differs, `Copy AI result` on both notification variants.
- Never put secure-session text in accessibility memory, a notification, or the clipboard fallback.
- Never clear focus, click the field, perform gestures, or change text from an automatic callback.
- Keep the existing capture-persistence and transcription-rescue paths unchanged.
