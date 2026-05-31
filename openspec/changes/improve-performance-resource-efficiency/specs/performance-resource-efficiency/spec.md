## ADDED Requirements

### Requirement: Backpressure-Safe Audio Decode Streaming

Audio decode streams SHALL respect downstream backpressure and SHALL NOT silently drop decoded chunks.

#### Scenario: Slow collector receives all chunks

- **GIVEN** a long audio file is decoded
- **WHEN** the downstream collector processes chunks slowly
- **THEN** every decoded chunk is delivered in order or the decode fails with an explicit error.

#### Scenario: Decode failure is observable

- **WHEN** the decoder cannot deliver a chunk
- **THEN** the caller receives a failure result and telemetry records the decode failure reason.

### Requirement: Candidate-Gated Speech Model Warmup

Speech model warmup SHALL run only when a likely speech workload candidate exists.

#### Scenario: Idle startup skips warmup

- **GIVEN** no transcription, dictation, readiness, or recovery candidate exists
- **WHEN** the app starts
- **THEN** speech model warmup is not started.

#### Scenario: Queued transcription triggers warmup

- **GIVEN** transcription work is queued
- **WHEN** warmup evaluation runs
- **THEN** speech model warmup may start for that candidate.

#### Scenario: Keyboard dictation triggers warmup

- **GIVEN** keyboard dictation is starting
- **WHEN** warmup evaluation runs
- **THEN** speech model warmup may start for the dictation candidate.

### Requirement: Bounded Keyboard Dictation Audio Memory

Keyboard dictation SHALL keep audio memory bounded by chunk and codec buffer sizes rather than total dictation duration.

#### Scenario: Long dictation completes

- **GIVEN** a long keyboard dictation session
- **WHEN** audio is captured and processed
- **THEN** the handoff avoids full-buffer multi-copy memory growth.

#### Scenario: Capture failure preserves recoverability

- **WHEN** bounded handoff fails during dictation processing
- **THEN** partial artifacts are handled consistently and the user receives an explicit failure state.

### Requirement: Home List Transcript Preview Loading

Home list rows SHALL load transcript previews and summaries through bounded projection data rather than full transcript bodies.

#### Scenario: List row needs preview

- **WHEN** Home renders a recording row
- **THEN** the repository provides only the preview, summary, and row metadata needed for that row.

#### Scenario: Detail opens full transcript

- **WHEN** the user opens a detail or editor view
- **THEN** the full transcript body loads for that screen.

### Requirement: Playback Tick Recomposition Isolation

Playback progress ticks SHALL update only the UI state that displays active playback progress.

#### Scenario: Tick updates active control

- **WHEN** playback progress advances
- **THEN** the active playback timer or progress control updates.

#### Scenario: Non-playback row stays stable

- **GIVEN** a list row is not displaying active playback progress
- **WHEN** playback ticks occur elsewhere
- **THEN** that row is not recomposed because of the tick.

### Requirement: Efficient Transcript LLM Context

LLM enhancement SHALL reuse or batch per-recording transcript context where multiple enhancement phases run for the same transcript and attempt.

#### Scenario: Multiple phases share context

- **GIVEN** title, summary, cleanup, and transform phases run for the same recording attempt
- **WHEN** transcript context is assembled
- **THEN** common transcript context is reused and only phase-specific instructions vary.

#### Scenario: Context changes invalidate reuse

- **GIVEN** transcript content or profile processing settings change
- **WHEN** another enhancement phase runs
- **THEN** the shared context is rebuilt for the new input.
