## Context

The repo declares API 35 compile/target values and API 26 minimum SDK values while the audit scope requires Android 16-only targeting. The local SDK has `android-36` installed.

## Decision

Change SDK declarations to API 36 across the project. Keep removal of now-unreachable runtime guards as follow-up tasks inside the same change if the build passes, because SDK alignment is the first proof that the platform baseline is available.

## Alternatives Considered

- Raise only `targetSdk`: rejected because library modules and old `minSdk` still keep compatibility branches live.
- Leave `minSdk = 26` for tests: rejected unless build/test output proves the runner needs it.

## Risks

- AGP 8.7.3 may warn that compile SDK 36 is newer than the tested SDK. That is a build-tool warning, not a runtime baseline reason to stay on API 35.

## Fallback Policy

- Fallback added: no
- Trigger:
- Owner:
- Exit condition:
- Observability:
- Tests:

## Rollout

1. Update SDK declarations.
2. Build the debug APK.
3. Remove unreachable SDK guards in focused follow-up edits where low-risk.
