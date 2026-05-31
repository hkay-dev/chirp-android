# Build And Worktree Gate

This file defines when an implemented issue is actually resolved.

## Resolution Rule

After every meaningful implementation change, build a debug APK:

```bash
./gradlew :app:assembleDebug
```

Expected output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

An implemented finding is not resolved until that build succeeds after the fix. Passing unit tests is not enough.

## Meaningful Change

Meaningful implementation changes include:

- Kotlin or Java production code.
- Tests that compile against changed production code.
- Gradle files.
- Android manifests.
- Resources.
- Room schema, DAO, repository, entity, or migration changes.
- DI/module wiring.
- WorkManager, service, IME, or model-readiness behavior.
- OpenSpec tasks marked as implemented.

Pure audit report text, OpenSpec proposal drafting, and `audit/` instruction edits do not require an APK build. If an OpenSpec proposal is marked implemented, the APK build is required.

## Staged Multi-Change Exception

A temporary build failure is acceptable only for a staged sequence where all of these are true:

- The sequence is documented before or during the first breaking edit.
- The expected failure is named.
- The owner is named.
- The next edits are known and bounded.
- The APK build is run immediately after the sequence completes.

Record the exception in:

- `audit/FINDINGS.md`
- The owning OpenSpec `tasks.md`
- `audit/SUBAGENT_SUMMARIES.md` if a sub-agent owns the sequence

Do not leave a staged sequence open overnight without marking the finding blocked or budget-exhausted.

## Worktree Rule

Parallel implementation should use worktrees. The coordinator owns the main checkout. Sub-agents that edit code should work in isolated worktrees, then return a patch or branch for synthesis.

Suggested worktree root:

```bash
mkdir -p ../2026-01-29-parakeeboard-audit-worktrees
```

Create a worktree per implementation batch:

```bash
rtk git worktree add ../2026-01-29-parakeeboard-audit-worktrees/<change-name> -b audit/<change-name>
```

Use short, descriptive branch names:

```text
audit/recording-stop-generation
audit/model-readiness-singleflight
audit/keyboard-input-connection-safety
```

In each worktree:

1. Read the same `audit/` instructions from the main checkout.
2. Implement only the assigned OpenSpec change.
3. Run targeted tests.
4. Run `./gradlew :app:assembleDebug`.
5. Return the branch name, changed files, tests run, APK build result, and any blockers.

The coordinator should merge or cherry-pick one implementation batch at a time into the main checkout, resolve conflicts, rerun targeted verification, and rerun:

```bash
./gradlew :app:assembleDebug
```

Do not merge multiple worktrees at once and then try to diagnose a combined failure. Keep the failure surface small.

## Final Reporting

The final response must include:

- Worktrees created.
- Worktrees merged or left open.
- Targeted tests run per implementation batch.
- APK build command result.
- APK path when successful.
- Any build failures and exact blockers.

