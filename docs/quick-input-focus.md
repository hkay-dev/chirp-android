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
| The transcript is absent or arrives late | The text becomes visible after blur and refocus | Content reached the editor path; visible invalidation or composing state is stale |

These results make another window-flag permutation a poor next move. The remaining high-value work
is result-contract completeness and direct observation of the caller's chosen result channel.

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

## Most likely remaining failure chain

The evidence supports this chain, though the live request log is needed to distinguish its two
middle branches.

1. SwiftKey launches Chirp's activity, which necessarily pauses X's foreground activity.
2. Chirp returns the transcript through either the activity result or result token.
3. SwiftKey updates X through its `InputConnection` after X resumes.
4. SwiftKey may use Android composing text for that update. X may accept the editor-state change
   yet fail to redraw it until the editor restarts or focus changes.

FUTO Keyboard independently removed composing-text use from normal typing and voice input because
many apps and web editors mishandle compositions. That is strong evidence for the last branch, not
proof of SwiftKey's private implementation. Chirp cannot control whether SwiftKey uses
`setComposingText`, `commitText`, or a private edit command after receiving an activity result.

## Structural options if the caller uses only activity results

1. Keep the implicit recognition activity for SwiftKey compatibility. This remains required because
   SwiftKey launches `ACTION_RECOGNIZE_SPEECH` rather than an auxiliary voice IME.
2. Add a separate auxiliary IME service with subtype mode `voice` for keyboards that support it.
   That service can finish any old composition and commit final text directly. It would improve
   HeliBoard, FlorisBoard, AnySoftKeyboard, and similar integrations, but it would not replace the
   SwiftKey path.
3. Keep `ChirpRecognitionService` for apps using `SpeechRecognizer`. It does not solve a caller that
   explicitly launches the activity.
4. Do not add accessibility injection, clipboard paste, or a draw-over-apps window. Those routes add
   sensitive permissions, duplicate-insertion races, and no reliable ownership of the target field.

## Live diagnostic procedure

1. Clear logcat and start one quick-input dictation from X.
2. Read `VoiceRecognitionActivity` logs for `pendingResult` and the final channel.
3. Capture `dumpsys input_method` and `dumpsys window` immediately after the sheet closes.
4. Take a UI hierarchy dump before touching the editor. If the hierarchy contains the transcript
   while the pixels do not, X has a redraw bug. If neither contains it until focus changes, the
   insertion is still held in the IME or composing session.
5. Repeat twice in the same composer. The second result catches stale `InputConnection` reuse that a
   one-shot test misses.

## Permanent behavior

- Request unchanged soft-input visibility.
- Keep the screen awake for the entire quick-input window.
- Preserve the established Chirp bottom-sheet appearance.
- Deliver through the caller-selected Android result channel exactly once.
- Finish successful recognition immediately.
- Keep the existing capture-persistence and transcription-rescue paths unchanged.
