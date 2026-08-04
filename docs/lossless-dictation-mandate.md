# Lossless dictation mandate

Chirp must prefer preserving captured speech over speed, polish, storage savings, or a clean failure state.

## Invariants

1. Microphone capture never waits for speech-model loading, AI processing, cloud setup, or recovery journaling.
2. The file-backed PCM capture is the source of truth until ownership moves to a durable recording row or the user explicitly cancels it.
3. Every successful `AudioRecord.read` block is written directly to the capture file. Live transcription receives a read-only copy and cannot block, change, or delete that file.
4. Recording UI becomes ready only after the first microphone block arrives. Capture may begin earlier than that UI signal.
5. Live partial text is disposable. Final text always comes from the complete saved audio.
6. Raw transcription and AI-processed text remain separate. AI output that appears to omit the opening cannot replace the raw text automatically.
7. Losing the input connection turns the capture into a recoverable recording with a result notification. It never turns into a silent discard.
8. Process death between microphone start and journal creation is recoverable by scanning old, valid cloud-capture files.
9. Explicit user cancellation is the only normal path allowed to discard captured speech before durable ownership moves.
10. Capture timing, first-sample latency, frame count, elapsed duration, and estimated gaps stay measurable without logging dictated text.

## Review rule

Any change touching microphone startup, audio routing, recorder teardown, model residency, transcription, AI processing, persistence, or IME lifecycle must show that these invariants still hold and must add a regression test for any changed boundary.
