# UI polish manual QA checklist

Manual verification items for motion and navigation polish. Run on a physical device when possible.

## Navigation and playback

| # | Area | Steps | Expected |
|---|------|-------|----------|
| N1 | Home → Studio single-top | Open a recording from Home; press back; tap the same recording again | One back returns to Home; no stacked Studio entries |
| N2 | Search → Studio single-top | Search for a recording; open Studio; back; open same result again | Same as N1 |
| N3 | Import → Studio | Import audio from Home | Studio opens once with `launchSingleTop`; back returns to Home |
| N4 | Mini player cross-studio | Play recording A from Home mini player; open Studio for recording B | Audio from A pauses; Studio shows B without A continuing in background |
| N5 | Search hides in-progress | Start a recording; search for its title on Home | In-progress `RECORDING` row does not appear in search results |

## Motion (P4)

| # | Area | Steps | Expected |
|---|------|-------|----------|
| M1 | Keyboard mode row expand vs crossfade | On keyboard, switch processing modes (e.g. transcribe ↔ correct) | Mode row expands/collapses smoothly; label crossfades without layout jump or clipped text |
| M2 | Player pushes content vs tab row | In Processing Studio, start playback so the inline player appears | Player reveal pushes transcript/content down; tab row stays pinned; no overlap or double-offset with global mini player |

## Notes

- Optional animation fixes from M1/M2 are non-blocking; file issues if motion regresses.
- See `docs/reliability-test-matrix.md` for automated coverage mapping.
