# Overnight Audit Goal Entrypoint

This is the first file for the overnight Codex goal.

Objective: deeply audit this repo for over-engineering, over-complication, reliability risks, race conditions, long-recording failure modes, model-readiness timing problems, and keyboard-input risks. For every confirmed issue, create or update an OpenSpec proposal that resolves it, then implement the fix unless it is genuinely blocked. Do not stop at proposals.

Repository: `/Users/developer/Development/sandbox/2026-01-29-parakeeboard`

## Required Setup

1. Read `AGENTS.md`.
2. Read `README.md`.
3. Read `docs/recording-lifecycle-spec.md`.
4. Read `docs/reliability-test-matrix.md`.
5. Read `openspec/project.md`, `openspec/config.yaml`, `openspec/changes/README.md`, and `openspec/changes/AUDIT_INDEX.md`.
6. Read every file in this `audit/` directory before starting the scan.
7. Treat `audit/08_ANDROID_BEST_PRACTICES.md` as a required platform checklist.
8. Treat `audit/09_IMPLEMENTATION_PRINCIPLES.md` as the quality bar for fixes.
9. Treat `audit/10_BUILD_AND_WORKTREE_GATE.md` as the resolution gate for every implemented change.
10. Use `rg` for discovery.
11. Use `rtk git` for git inspection if git state is needed.

## Sub-Agent Requirement

Use as many sub-agents as the runtime allows. The goal is context breadth and independent review pressure.

Every sub-agent must be configured with thinking effort `5.5 xhigh` when the runtime supports per-agent effort settings. If the runtime uses another effort schema, choose the closest available setting at or above xhigh. If the runtime can't set thinking effort per sub-agent, record that limitation in `audit/SUBAGENT_SUMMARIES.md` and continue with the strongest available reasoning mode.

If multi-agent tools are available, spawn separate sub-agents before doing the main synthesis:

1. Recording lifecycle and stop handoff agent.
2. Recovery, journal, orphan cleanup, and DB consistency agent.
3. Long-recording, segment capture, pause/resume, and finalization agent.
4. Model download, readiness, recognizer loading, and transcription timing agent.
5. Keyboard IME, quick capture, input connection, and text insertion agent.
6. Queue recovery, WorkManager, retries, and result semantics agent.
7. Over-engineering and module-boundary simplification agent.
8. Test-fidelity and coverage-gap agent.
9. Android 16 platform and modern Android pitfalls agent.
10. OpenSpec proposal writer agent.

Give each sub-agent:

- The relevant files from `audit/`.
- The canonical docs listed above.
- Its domain from `audit/02_DOMAIN_BRIEFS.md`.
- The output contract from `audit/05_OUTPUT_CONTRACT.md`.
- The implementation principles from `audit/09_IMPLEMENTATION_PRINCIPLES.md`.
- The build and worktree gate from `audit/10_BUILD_AND_WORKTREE_GATE.md`.
- Thinking effort: `5.5 xhigh`.

Each domain sub-agent should spawn narrower sub-agents if its own runtime supports that and the target area is large enough. Examples: split recording into state manager, service stop, WorkManager finalize, and tests; split keyboard into IME lifecycle, input connection, quick capture, and pending stop routing.

If the runtime has a sub-agent limit, fill the slots in the order above. If no sub-agent tool exists, simulate the same domains serially and clearly label each pass. Do not skip a domain because it seems likely to be fine.

Before implementation, run at least one synthesis pass that compares sub-agent findings against each other and rejects duplicates, weak claims, and fixes that add unnecessary fallback paths.

If multiple sub-agents will implement code, use worktrees as described in `audit/10_BUILD_AND_WORKTREE_GATE.md`. Do not let parallel agents edit the same checkout at the same time.

## Work Phases

### Phase 1: Repo Orientation

Build a current map of modules, source files, tests, OpenSpec changes, and known reliability docs. Do not rely on memory or stale summaries when the live repo can answer.

### Phase 2: Parallel Domain Audits

Each domain pass must produce evidence-backed findings with exact file and line references. Findings without a concrete failure mode are suspicions, not confirmed issues.

### Phase 3: Synthesis

Merge duplicate findings. Reject weak findings. Rank remaining issues by risk:

- Critical: plausible user data loss, audio loss, transcript loss, stuck capture lock, corrupt persistence, or keyboard text loss.
- High: plausible race, unrecoverable stuck state, false model readiness, duplicate enqueue, stale recovery, or broken long-session behavior.
- Medium: confusing lifecycle, fragile sequencing, missing timeout guard, weak test coverage for risky paths, or unnecessary abstraction that hides state ownership.
- Low: cleanup, stale docs, naming friction, minor simplification, or non-critical test hygiene.

### Phase 4: OpenSpec Proposal Generation

For every confirmed issue, create or update an OpenSpec change under `openspec/changes/`.

Rules:

- Prefer grouping tightly related findings into one coherent change.
- Do not create one tiny proposal per line-level issue if one lifecycle proposal covers the invariant cleanly.
- Do not bury unrelated issues in a broad bucket.
- Use existing active changes when they fit.
- Create new change folders when no active change cleanly owns the issue.
- Update `openspec/changes/AUDIT_INDEX.md` so every confirmed finding maps to a proposal.
- Each proposal must include `proposal.md`, `design.md` when tradeoffs or sequencing matter, and `tasks.md`.
- Add spec deltas under `openspec/changes/<change>/specs/<capability>/spec.md` when behavior requirements change.

### Phase 5: Required Implementation

Implement code only after the relevant proposal exists. Then fix every unblocked confirmed issue in priority order:

1. Critical
2. High
3. Medium
4. Low

For each implemented fix:

- Keep changes narrow.
- Prefer deleting, simplifying, or consolidating code over adding new layers.
- Update or add targeted tests.
- Run the narrowest relevant verification command from `audit/04_VALIDATION.md`.
- Build a debug APK with `./gradlew :app:assembleDebug` after every meaningful implementation change, unless the change is an explicitly staged multi-change sequence where temporary build failure is expected.
- Update the matching OpenSpec `tasks.md` checkboxes.
- Do not add fallback methods, alternate code paths, compatibility shims, or broad recovery branches unless the failure mode proves they are necessary.
- If a fallback is necessary, document the exact trigger, owner, exit condition, and test coverage in the OpenSpec design.

Only leave an issue unimplemented when there is a concrete blocker:

- Missing external information.
- Device-only validation is required before choosing the fix.
- The fix requires an unsafe architectural decision that needs Tester.
- The goal budget is exhausted.

For every unimplemented issue, record the exact blocker in `audit/FINDINGS.md` and the final response.

A confirmed issue is not resolved until its implementation has a successful APK build, producing `app/build/outputs/apk/debug/app-debug.apk`, or until a staged multi-change exception is documented and then cleared by a successful build at the end of the sequence.

## Deliverables

Create:

1. `audit/FINDINGS.md`
2. `audit/OPEN_SPEC_PLAN.md`
3. `audit/SUBAGENT_SUMMARIES.md`
4. OpenSpec change folders for every confirmed issue.
5. Updated `openspec/changes/AUDIT_INDEX.md`.
6. Code fixes for every unblocked confirmed issue.
7. Targeted tests for every implemented fix.
8. Successful APK build evidence for every resolved implementation batch.

The final response must state:

- How many files were scanned.
- How many sub-agents were used.
- How many confirmed issues were found.
- Which OpenSpec changes were created or updated.
- Which fixes were implemented.
- Which tests were run.
- Whether `./gradlew :app:assembleDebug` succeeded and where the APK was produced.
- Which issues remain unimplemented, with exact blockers.
