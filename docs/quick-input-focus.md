# Quick-input focus contract

Chirp's quick speech sheet is an Android `ACTION_RECOGNIZE_SPEECH` activity. It is not the full
Chirp IME and does not own the host app's `InputConnection`. The calling keyboard receives the final
text through the activity-result contract and commits it into its own current editor.

## What Chirp can preserve

- The recognition window requests `stateUnchanged`, never `stateAlwaysHidden`.
- The non-editing sheet uses `FLAG_ALT_FOCUSABLE_IM`, which keeps it outside the IME target
  relationship and lets the caller's keyboard stay bound behind the sheet.
- Successful recognition skips the 250 ms sheet-exit delay and finishes as soon as the result
  bundle is ready.
- The window keeps the screen on for the whole visible capture.
- Results contain one `EXTRA_RESULTS` hypothesis and one matching confidence value.

## Platform boundary

Launching any activity temporarily gives that activity the foreground window. Chirp cannot force a
Telegram or Twitter editor to remain internally focused, and it cannot call methods on another
app's view. Trying to bypass that boundary with an accessibility service, clipboard injection, or a
draw-over-apps window would add permission, privacy, and duplicate-insertion risks while breaking
the standard speech-recognition result contract.

The practical fix is to keep the caller's IME visible and bound so its existing editor connection
survives the foreground-window handoff. A host may still change its own visible status while paused;
that host-controlled lifecycle behavior is not evidence that Chirp's offline recognizer is using
the network.

## Device checks

1. In Telegram, dictate twice into the same composer through the quick-input trigger.
2. In Twitter or X, dictate once into an empty composer and once after the returned text.
3. Confirm each result appears without tapping the editor.
4. Confirm the original keyboard is immediately usable after each result.
5. Repeat with raw output and AI post-processing enabled.
6. Cancel one capture and confirm no text is inserted.
