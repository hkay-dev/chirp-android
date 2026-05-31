# Audit Scope

## Primary Question

Find anything in this codebase that could be considered over-engineered, over-complicated, unreliable, race-prone, or risky around recording, recovery, long recordings, model loading timing, and keyboard input.

This goal includes execution. For every confirmed issue, write the OpenSpec proposal first, then implement the fix unless a concrete blocker prevents implementation.

Platform constraint: this app targets Android 16 only. Treat Android 16 as the platform baseline for audit and implementation decisions. Backward-compatibility branches, SDK guards, legacy fallbacks, compatibility shims, or abstractions that exist only for older Android versions are over-engineering candidates unless they are still required by a library, test environment, or explicit product constraint. The live Gradle files currently declare older SDK values, so verify and propose/implement the correct Android 16 alignment if the local SDK/tooling supports it.

Implementation constraint: fixes should make the code clearer, smaller, and easier to reason about whenever possible. Prefer removing duplicate state, collapsing unnecessary wrappers, and choosing one authoritative path over adding defensive branches. Add a fallback only when the user-visible failure mode requires it and the fallback has a bounded trigger, clear owner, and test coverage.

## Source Of Truth

Use live repo evidence first.

Read and respect:

- `AGENTS.md`
- `README.md`
- `docs/recording-lifecycle-spec.md`
- `docs/reliability-test-matrix.md`
- `openspec/specs/**/spec.md`
- `openspec/changes/**/proposal.md`
- `openspec/changes/**/tasks.md`

The current lifecycle docs say the app uses one capture lock, service-backed APP/WIDGET recording, keyboard quick capture without a journal, immediate capture stop handoff, background finalization, recovery scans, and WorkManager queues. Verify code against that. Treat doc/code mismatches as findings if they affect behavior or operator trust.

## In Scope

- User audio loss.
- Lost or duplicated transcripts.
- Stuck recording state.
- Stuck WorkManager queues.
- Duplicate stops or duplicate persistence.
- Late stop/finalize results changing state after a timeout or new recording.
- Recovery prompts that appear when they should not, or fail to appear when they should.
- Journals, DB rows, files, and protected paths drifting apart.
- Long recordings, timer rotation, pause/resume, screen-off capture, force-kill, low storage, and background finalization.
- Model readiness, readiness cache, download integrity, recognizer creation, warmup, and lazy loading.
- Keyboard IME lifecycle, quick capture, input connection validity, pending stop queue, text insertion, and app/widget/keyboard origin routing.
- Test coverage that claims reliability but misses the actual risk.
- Overly broad abstractions, duplicate state machines, redundant wrappers, stale facades, and unclear ownership boundaries.
- Modern Android platform pitfalls listed in `audit/08_ANDROID_BEST_PRACTICES.md`, especially Android 16 behavior changes, foreground-service timeouts, adaptive layout assumptions, predictive back, lifecycle-aware Flow collection, WorkManager reliability, and IME lifecycle/input-connection safety.
- Fallback paths, retry branches, compatibility shims, and "just in case" handlers that make behavior harder to prove.

## Out Of Scope Unless Directly Tied To Reliability

- Pure visual polish.
- Feature requests.
- Large rewrites without a concrete failure mode.
- New fallback mechanisms that are not tied to a confirmed failure mode.
- Style-only renames.
- Speculative performance claims without evidence.

## Issue Standard

A confirmed issue needs:

- Exact file and line reference.
- Concrete failure mode.
- Path from user action or system event to bad outcome.
- Why current code allows it.
- Proposed fix direction.
- Test or manual verification path.

If one of those is missing, label it as a suspicion.

For confirmed issues, proposal-only completion is not enough. The finding remains open until the fix is implemented and targeted verification has run, or until a concrete blocker is recorded.
