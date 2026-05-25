## MODIFIED Requirements

### Requirement: Detail Screen Content Animation

Recording detail (Processing Studio) content changes SHALL animate smoothly without instant content swaps. Summary and Chat tab bodies SHALL follow the same outer padding and section spacing conventions as the Transcript tab.

#### Scenario: Transcript appears with animation
- **WHEN** transcription completes and text is available
- **THEN** transcript section fades in with slight scale (0.95x to 1.0x) over 300ms

#### Scenario: Summary section animates open
- **WHEN** AI summary is generated
- **THEN** summary card expands vertically with fade over 300ms
- **AND** content below shifts smoothly (animateContentSize)

#### Scenario: Loading state animates
- **WHEN** transcription is in progress
- **THEN** loading indicator fades in with slide over 200ms
- **AND** progress bar animates smoothly (not discrete jumps)

#### Scenario: Summary tab layout matches Transcript tab rhythm
- **WHEN** user views the Summary tab in Processing Studio
- **THEN** outer content padding is applied at the tab root (single padding owner)
- **AND** vertical spacing between major sections uses 12.dp consistent with Transcript tab
- **AND** scrollable summary content fills remaining tab height without double-applying horizontal padding

#### Scenario: Structured outcome generation shows animated progress
- **WHEN** structured outcomes are generating (`isGenerating` is true)
- **THEN** the Summary tab displays compact morphing progress UI (not static text-only info)
- **AND** progress enters and exits with studio reveal/hide transitions

## ADDED Requirements

### Requirement: Studio shared action buttons

Processing Studio action affordances SHALL use shared `core-ui` composables for icon-leading outlined actions, ensuring consistent sizing, spacing, and enabled states across tabs.

#### Scenario: CopyActionButton presents copy affordance
- **WHEN** a screen renders a copy action via `CopyActionButton`
- **THEN** the control displays an outlined button with `ContentCopy` icon (18.dp), 8.dp icon-label gap, and the provided localized label
- **AND** the button respects the `enabled` parameter

#### Scenario: StudioOutlinedAction supports arbitrary icons
- **WHEN** a screen renders share, ask-AI, or other studio actions via `StudioOutlinedAction`
- **THEN** the control displays the supplied icon and label in outlined button style matching Transcript copy actions

#### Scenario: Summary structured outcome item actions
- **WHEN** structured outcome items are displayed on the Summary tab
- **THEN** Copy, Share, and Ask AI actions use shared outlined action composables with icons `ContentCopy`, `Share`, and `Chat` respectively
- **AND** text-only action buttons are not used for these three actions

### Requirement: Chat typing indicator

Processing Studio Chat tab SHALL surface assistant typing state while an LLM response is in flight.

#### Scenario: Typing bubble visible during LLM wait
- **WHEN** user sends a chat message
- **AND** `ProcessingStudioState.isTyping` is true
- **THEN** Chat tab displays an assistant-aligned typing indicator bubble
- **AND** the indicator uses animated dots (or equivalent thinking animation)

#### Scenario: Typing bubble hides on response
- **WHEN** the LLM chat exchange completes
- **AND** `isTyping` becomes false
- **THEN** the typing indicator bubble is removed
- **AND** the assistant message appears in the message list

#### Scenario: isTyping wired from ViewModel
- **WHEN** Processing Studio screen composes the Chat tab
- **THEN** `state.isTyping` from `ProcessingStudioViewModel` is passed to `ChatTab`
- **AND** no duplicate typing state is maintained in the composable layer

### Requirement: Studio tab string externalization

User-visible strings in Processing Studio Transcript and Chat tabs SHALL be defined in string resources, not hardcoded in composables.

#### Scenario: Transcript manual correction banner localized
- **WHEN** manual correction banner is shown on Transcript tab
- **THEN** banner text comes from `rec_transcript_manual_correction_banner` string resource

#### Scenario: Word timing note localized
- **WHEN** untimed transcript displays word-timing unavailable note
- **THEN** note text comes from `rec_transcript_word_timing_unavailable` string resource

#### Scenario: Chat input placeholder localized
- **WHEN** Chat tab input field is empty
- **THEN** placeholder text comes from `rec_chat_placeholder` string resource

#### Scenario: Chat send button accessibility
- **WHEN** TalkBack or accessibility services focus the Chat send button
- **THEN** content description comes from `rec_chat_send` string resource

### Requirement: Processing Studio error banner accessibility

Processing Studio error surfaces SHALL expose localized accessibility labels for icon-only indicators.

#### Scenario: Error icon content description
- **WHEN** Processing Studio displays the inline error banner with error icon
- **THEN** the icon content description comes from `rec_processing_error` string resource
- **AND** is not hardcoded English in the composable

### Requirement: Copy action feedback

Copy actions in Processing Studio SHALL provide user feedback using localized messaging.

#### Scenario: Structured outcome copy snackbar
- **WHEN** user copies a structured outcome item
- **THEN** a snackbar (or equivalent message) displays `rec_copied_to_clipboard`
- **AND** the message is not hardcoded English in ViewModel code

#### Scenario: Transcript copy snackbar unchanged
- **WHEN** user copies transcript text via Transcript tab actions
- **THEN** existing localized copy confirmation behavior remains

#### Scenario: Optional inline copy confirmation
- **WHEN** optional inline copy micro-feedback is implemented
- **THEN** copy action icon MAY briefly crossfade to a check icon for up to 1.5 seconds
- **AND** snackbar confirmation still fires for accessibility
