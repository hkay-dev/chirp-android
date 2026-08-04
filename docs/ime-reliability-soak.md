# IME reliability soak

The developer menu includes an on-device 25-session IME soak and one-shot commit-refusal fault injection.

## Run a soak

1. Open **Developer Menu** and press **Start 25-session soak**.
2. Use Chirp from the keyboard for a mix of short and long dictations.
3. Include app switches, screen-off attempts, low-memory pressure, USB mic insertion and removal, and process restarts.
4. Use **Fault next commit** once. The next raw transcript commit is refused deliberately and must appear as a durable rescue recording or notification.
5. Return to the developer menu and inspect every percentile row. A red `REGRESSION` row exceeded its p95 budget or recorded a failed boundary.

The readout stores only elapsed milliseconds, counts, and success bits. It never stores transcript text, audio, target package names, field contents, or prompts. Starting a soak clears the prior readout. Each metric keeps the newest 200 value-and-outcome samples in app-private preferences across process restarts. Percentiles use the nearest-rank definition, so small samples do not hide their slowest result.

The one-shot commit refusal works only during an active soak. Stopping or completing a soak disarms it, and consumption is committed synchronously before the refusal is injected so a process crash cannot repeat the fault. Streaming transcript checkpoints are limited to one write per three seconds and are disabled for no-learning fields.

## Budgets

| Metric | p95 budget |
| --- | ---: |
| Press to first durable audio | 250 ms |
| Stop to raw transcript | 2,500 ms |
| Press to first streaming preview | 1,500 ms |
| AI processing | 10,000 ms |
| Raw-ready to input commit | 150 ms |
| Estimated audio gap | 20 ms |
| Recorder restarts | 0 |

The soak fails a session on a refused commit, a gap over budget, or any recorder restart. A fault-injected refusal is expected to count as a failed session because the harness is checking that the recovery route becomes visible rather than hiding the boundary failure.

## Model repair checks

Both Parakeet and the streaming preview validate exact artifact size and SHA-256 before activation. A downloaded candidate keeps the previous artifact as `.last-working`. Native recognizer initialization confirms the candidate and removes the backup. Initialization failure copies each backup into an atomic replacement and keeps every original backup until the set is restored. A durable rollback marker finishes an interrupted promotion or restore, and native initialization immediately retries once against the restored model.
