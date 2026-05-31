# Android 16 And Modern Android Best Practices

This app is intended to target Android 16 only. Use Android 16 / API 36 as the product baseline for audit decisions.

The live repo may still declare older SDK values. If so, treat that as a configuration drift finding and propose/implement the Android 16 alignment if local SDK/tooling supports it. Do not preserve backwards-compatibility code merely because the current Gradle files still say `minSdk = 26` or `targetSdk = 35`.

## Required Documentation Checks

Use the Android CLI docs tool for current platform guidance when a finding depends on platform behavior:

```bash
android docs search "Android 16 behavior changes targetSdk 36"
android docs search "Android app architecture best practices coroutines lifecycle WorkManager"
android docs search "foreground service timeout Android 15 Android 16 WorkManager"
android docs search "Android input method editor lifecycle InputConnection"
```

Docs already identified as relevant:

- `kb://android/about/versions/16/behavior-changes-16`
- `kb://android/topic/architecture/recommendations`
- `kb://android/develop/background-work/services/fgs/timeout`
- `kb://android/develop/ui/views/touch-and-input/creating-input-method`

## Android 16 Baseline Rules

- Assume Android 16 / API 36 as the only supported runtime target.
- Prefer deleting old SDK guards and compatibility branches over preserving them.
- Treat `Build.VERSION.SDK_INT` checks as suspicious unless they guard Android 16 feature flags, test-only behavior, or library constraints.
- Treat old `minSdk`-driven alternate implementations as over-engineering candidates.
- If the repo still compiles or targets API 35, create an OpenSpec change for SDK alignment and implement it if API 36 is installed and dependencies support it.
- Do not add temporary Android 16 opt-outs as a default. If an opt-out is necessary, it needs a finding, proposal, owner, and removal condition.
- Do not add compatibility fallbacks for old Android behavior. Android 16 is the baseline unless a test runner or dependency forces a local shim.

## Android 16 Behavior Pitfalls To Audit

- Edge-to-edge opt-out is disabled for Android 16 target apps. Audit insets, keyboard window overlap, system bars, record screen controls, Processing Studio, settings screens, and IME UI.
- Predictive back is enabled by default. Audit custom back handling, record-screen save/discard dialogs, recovery dialogs, navigation barriers, Processing Studio, and any `onBackPressed` or `KEYCODE_BACK` assumptions.
- Orientation, resizability, and aspect-ratio restrictions are ignored on large screens. Audit any portrait-only assumptions, fixed heights, animation offsets, record/keyboard layouts, and state loss during activity recreation.
- `ScheduledExecutorService.scheduleAtFixedRate` runs at most one missed execution after lifecycle return when targeting Android 16. Audit timer, waveform, rotation, timeout, retry, polling, and cleanup code for assumptions about catching up missed ticks.
- Safer intent matching is relevant for exported components and inter-app entry points. Audit manifests, recognition service, IME service, widget receiver, share/import flows, and deep links for explicit action/filter correctness.
- Local network permission changes may matter only if the app touches LAN resources. If no LAN access exists, record it as a rejected lead.

## Foreground Service And Background Work Pitfalls

- Foreground service timeout restrictions exist for Android 15+ and matter for long-running background service types. Audit service types, `Service.onTimeout`, stop behavior, and user-initiated start paths.
- Long-running transcription/finalization should use WorkManager correctly when it must survive process death.
- Unique work names and policies must match lifecycle semantics. Look for accidental duplicate enqueue, accidental work replacement, stale workers, and missing recovery after process death.
- Foreground notifications must accurately reflect capture, finalization, transcription, and failure states.
- Service stop paths must be idempotent. Duplicate stop, timeout, cancellation, and late result paths need generation or ownership guards.
- Prefer one idempotent service path over several fallback stop paths.

## Architecture And Lifecycle Best Practices

Audit against current Android architecture guidance:

- Clear data layer with repositories as the single source of truth.
- UI state flows from data/domain layers to ViewModels to Compose.
- Lifecycle-aware collection in Compose, usually `collectAsStateWithLifecycle`.
- ViewModels should not hold `Activity`, lifecycle-bound `Context`, `Resources`, services, or mutable platform objects unless there is a specific justified boundary.
- Prefer fakes to relaxed mocks in tests for lifecycle-sensitive behavior.
- Use `StateFlow`/`Flow` carefully. Audit eager collection, `stateIn` scope, cancellation, replay, and stale emissions.
- Avoid event channels for durable facts. Persist durable state in Room/DataStore/journals, not one-shot UI events.
- Keep expensive singleton objects explicitly scoped. Model/recognizer objects need clear lifetime, cancellation, and release behavior.

## IME And Keyboard Pitfalls

Audit against Android IME lifecycle guidance:

- IME UI should appear quickly. Heavy model loading must not block input view creation.
- Use the current `InputConnection` defensively. It can become invalid when focus, app, field, or IME binding changes.
- Do not commit dictated text into a stale field after transcription returns.
- Respect input types, especially password fields. Do not store, display, or log sensitive text from password contexts.
- Release large resources after the IME window is hidden, with a deliberate delayed-release policy if reuse is needed.
- Pending stops must survive IME unbind/rebind when keyboard recording is active.
- Keyboard origin capture must not desync the global recording state used by app/widget surfaces.
- Backspace, enter, cursor movement, delete surrounding text, and composed text operations must be tested against real `InputConnection` behavior or high-fidelity fakes.

## Compose And UI State Pitfalls

- Use stable state ownership. Avoid duplicate local state that can diverge from ViewModel or repository state.
- Audit `LaunchedEffect` keys and coroutine lifetimes for repeated starts, stale captures, and lost cancellation.
- Audit dialogs and navigation effects for duplicate firing after rotation or process recreation.
- Android 16 large-screen behavior makes state restoration more important. Record, recovery, transcription, and keyboard flows should survive configuration changes without losing user work.

## Testing Expectations

Add or update tests when fixing platform issues:

- Unit tests for ViewModel state and Flow ordering.
- WorkManager tests for unique work and recovery semantics.
- Service tests for timeout and duplicate stop behavior where feasible.
- IME tests with fakes that model invalid/stale `InputConnection`.
- Manual checks for Android 16 device/emulator behavior when platform behavior cannot be represented in JVM tests.

Do not mark Android 16-only cleanup complete if the old compatibility path remains reachable without a documented reason.
