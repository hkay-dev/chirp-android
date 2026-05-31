## 1. Implementation

- [x] 1.1 Add a network-constrained enhancement work request.
- [x] 1.2 Add a dedicated enhancement worker.
- [x] 1.3 Remove LLM execution from the transcription worker.
- [x] 1.4 Queue enhancement after transcript persistence when requested and available.
- [x] 1.5 Route pending enhancement reconciliation and manual recovery through enhancement work.
- [x] 1.6 Cancel both transcription and enhancement work for a recording.
- [x] 1.7 Persist an enhancement intent snapshot atomically with transcript commit.
- [x] 1.8 Add a database migration for persisted enhancement intents.
- [x] 1.9 Add profiles spec delta for profile LLM settings as enhancement requests.

## 2. Tests

- [x] 2.1 Cover enhancement work request naming, tags, input data, and network constraint.
- [x] 2.2 Cover transcription work still has no network constraint.
- [x] 2.3 Cover queue manager cancellation of both work units.
- [x] 2.4 Cover pending enhancement recovery enqueueing enhancement work.
- [x] 2.5 Cover enhancement policy behavior remains profile-first with global fallback.
- [x] 2.6 Cover requested enhancement with LLM disabled or missing API key completing without queued work.
- [x] 2.7 Cover all requested LLM operations failing while preserving `COMPLETED` status and transcript text.
- [x] 2.8 Cover `PENDING_ENHANCEMENT` recovery using enhancement work without re-entering transcription.
- [x] 2.9 Cover persisted enhancement intent repository transactions.
- [x] 2.10 Cover migration creation and backfill for persisted enhancement intents.

## 3. Verification

- [x] 3.1 `openspec validate split-transcription-enhancement-work --strict`
- [x] 3.2 `./gradlew :feature-transcription:testDebugUnitTest`
- [x] 3.3 `./gradlew :app:assembleDebug`
- [x] 3.4 `./gradlew :data:compileDebugAndroidTestKotlin`
