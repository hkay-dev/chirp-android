# Output Contract

## `audit/FINDINGS.md`

Use this structure:

```markdown
# Findings

## Confirmed Findings

### <SEVERITY>: <title>

- Files: `<path>:<line>`
- Failure mode: <concrete bad outcome>
- Trigger path: <user/system path>
- Why possible: <code reason>
- Fix direction: <smallest reliable direction>
- Simplification angle: <delete/consolidate/single source of truth/guard/fallback>
- Fallback rationale: <only if a fallback is proposed>
- OpenSpec change: `<change-name>`
- Verification: <tests/manual checks>
- APK build: passed | failed | not-run
- APK path: `app/build/outputs/apk/debug/app-debug.apk` when passed
- Implementation status: fixed | blocked | budget-exhausted
- Blocker: <only when not fixed>

## Suspicions

### <title>

- Evidence:
- Missing confirmation:
- Proposed OpenSpec coverage:

## Rejected Leads

### <title>

- Why rejected:
```

## `audit/OPEN_SPEC_PLAN.md`

Use this structure:

```markdown
# OpenSpec Plan

## Change Summary

### `<change-name>`

- Owns findings:
- Why grouped:
- Files likely touched:
- Specs changed:
- Verification:

## Index Updates

- `openspec/changes/AUDIT_INDEX.md`: <summary>

## Implementation Order

1. <change>
2. <change>
```

## `audit/SUBAGENT_SUMMARIES.md`

Use this structure:

```markdown
# Sub-Agent Summaries

## <domain>

- Agent used:
- Thinking effort requested:
- Thinking effort honored: yes | no | unknown
- Files scanned:
- Confirmed findings:
- Suspicions:
- Rejected leads:
- Proposed OpenSpec changes:
- Simplification opportunities:
- Fallbacks proposed:
- Worktree used:
- APK build status:
```

## Final Response

Keep the final response short and include:

- Sub-agents used.
- Files scanned.
- Confirmed issue count.
- Suspicion count.
- OpenSpec changes created or updated.
- Fixes implemented.
- Tests run.
- APK build status and APK path.
- Worktrees used and merged.
- Anything left unimplemented, with exact blockers.
