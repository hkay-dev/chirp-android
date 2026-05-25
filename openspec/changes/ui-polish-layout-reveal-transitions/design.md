# Design: Layout reveal transitions

## Context

Chirpboard uses Jetpack Compose with centralized motion in `ChirpMotion`. Two animation families exist:

| Family | Mechanism | Use when |
|--------|-----------|----------|
| **Push-down chrome** | `PushDownReveal` + parent `animatePushDownLayout()` | New vertical chrome pushes siblings (search, player, banners, filter rows) |
| **In-place content** | `AnimatedContent` + `studioContentCrossfade` or `layoutSizeSpring` | Same slot swaps (skeleton → transcript, empty → list, keyboard state) |
| **Bottom attach** | `miniPlayerRevealTransition` (slide from bottom) | Global mini player below NavHost |
| **Modal** | `AnimatedAlertDialog` scale/fade | Dialogs — not layout push |

Audit (May 2026) identified bare `if` visibility and slide-only `AnimatedVisibility` as root causes of jumps.

## Decisions

### D1: Shared `PushDownReveal` composable

**Choice:** `core-ui/motion/LayoutReveal.kt` exports `PushDownReveal` and `Modifier.animatePushDownLayout()`.

**Rationale:** Avoid copying four imports across 15+ call sites; guarantees consistent enter/exit.

### D2: LazyColumn header chrome

**Choice:** Wrap each conditional LazyColumn `item` body in `PushDownReveal` rather than one mega-header (items have independent keys/lifecycle).

**Alternative:** Single header item — rejected; complicates filter/search independent visibility.

### D3: List row growth

**Choice:** `RecordingListItem` root `Column` uses `animatePushDownLayout()`; each optional block (progress, summary, stuck, tags) wrapped in `PushDownReveal`.

**Rationale:** Rows resize during transcription while scrolling; spring size animation reduces snap.

### D4: Transcript tab body

**Choice:** Sealed display mode + `AnimatedContent` with `fadeIn/fadeOut` + `animatePushDownLayout()` on weighted `Box`.

**Rationale:** `when` swap caused largest studio content jump; crossfade with size spring handles skeleton ↔ transcript ↔ editor.

Copy-action rows use `PushDownReveal` (not slide `progressEnterTransition`) to match header.

### D5: Mini player tokens

**Choice:** Move inline transitions from `AppNavigation.kt` to `ChirpMotion.miniPlayerRevealTransition` / `miniPlayerHideTransition`; nav column uses `layoutSizeSpring`.

### D6: Settings screens

**Choice:** Replace `fadeIn() + expandVertically()` defaults with `PushDownReveal` for LLM sections, transcription download progress, Obsidian auto-export card, Audio manual devices.

### D7: Transition selection rule (document in spec)

```
Sibling chrome appears/disappears     → PushDownReveal + animatePushDownLayout on parent
Same region content swap             → AnimatedContent + layoutSizeSpring
Bottom bar                           → miniPlayerRevealTransition
Dialog                               → AnimatedAlertDialog (unchanged)
Phase text within visible progress   → studioContentCrossfade (unchanged)
```

## Risks

| Risk | Mitigation |
|------|------------|
| LazyColumn scroll jump on item animate | Accept minor offset shift; prefer push-down over instant pop |
| Double spring (AV + animateContentSize) | Use push-down on child, size spring on parent only |
| Over-animation on settings | Same 420/260ms tokens — subtle, not bouncy |

## Verification

- `./gradlew :app:assembleDebug` + module unit tests
- Device: `openspec/UI_POLISH_QA_CHECKLIST.md` layout-reveal section
- Manual: record→studio player reveal, home search, filter chip, transcribing list row
