# Record Screen Redesign & Notification Fix Plan

Based on your feedback, we are completely overhauling the in-app recording experience and fixing the broken foreground notification.

## Phase 1: Fixing the Notification "Spam"
**The Problem:** The current `RecordingService` creates a notification channel with `IMPORTANCE_HIGH` and manually updates the notification text every second with the new timer. Android interprets `IMPORTANCE_HIGH` as "heads-up" and drops it down over your screen on every single update.
**The Fix:**
1. We will rename the channel to `recording_channel_v2` (since Android caches importance permanently on older channels) and set it to `IMPORTANCE_LOW`. It will still show in the status bar and lock screen, but will never drop down and interrupt you.
2. We will add `.setOnlyAlertOnce(true)` to the builder so that even if it does update, it won't trigger any system alerts.

## Phase 2: Redesigning `RecordScreen.kt`
We will replace the alien edge-to-edge UI with a cohesive, standard Material 3 screen that fits the rest of Chirpboard.

**The Layout:**
1. **App Bar:** A standard `TopAppBar` reading "New Recording". It will have an `X` (Cancel) button on the top left, which stops and discards the recording, returning to Home.
2. **The Timer:** A massive, highly legible `displayLarge` or `displayMedium` timer centered near the top of the screen.
3. **The Waveform & Glow:** The middle of the screen will feature a large, beautifully rounded `Surface` card. Inside this card, we will embed the live 60fps high-framerate `AudioWaveform` component. The background of this card will be the red pulsing gradient, constrained cleanly inside the shape rather than bleeding over the whole screen.
4. **The Controls (Bottom):** 
   - **Center (Primary):** A massive "Done" button (Extended FAB or large filled button) to stop the recording and instantly hand off to the Processing Studio.
   - **Left (Secondary):** A "Pause" button that toggles to "Resume".
   - **Right (Secondary):** A "Start Over" button that instantly flushes the current audio and restarts the timer.

## Phase 3: Wiring and Cleanup
1. Ensure the `viewModel.pauseRecording()`, `resumeRecording()`, and `forceCancel()` states are perfectly wired to the new buttons.
2. Delete the old `MainActionButton`, `SecondaryButtonsRow`, and `RecordingGlowBackground` components that made up the old disjointed UI, treating the new screen as a pristine, standalone module.

I will spawn `gemini-deep` subagents to execute this exact plan now.
