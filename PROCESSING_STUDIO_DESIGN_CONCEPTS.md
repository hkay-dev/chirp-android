# Chirpboard Processing Studio: UI Design Concepts

The user explicitly requested a unified, non-split-screen interface for the new Processing Studio. We tasked three expert Android UX subagents to analyze macOS paradigms (like MacWhisper) and translate them into three distinct, highly-polished Jetpack Compose architectures. 

Please review the three concepts below and let me know which direction you prefer!

---

# Concept A: The Tabbed Studio

## Design Philosophy
Drawing heavy inspiration from the clean, text-first elegance of MacWhisper, this concept abandons the traditional, cluttered split-screen paradigm. Instead, it prioritizes absolute focus. By utilizing a full-screen tabbed canvas and minimizing the media controls into a persistent, floating bottom pill, the interface breathes. The text becomes the primary interface for the audio, not just a byproduct of it.

## Visual Hierarchy & Layout

1. **Top App Bar**: Minimal. Contains the file name, an "Export" action, and an overflow menu for settings. 
2. **Primary Navigation (TabRow)**: Snug beneath the app bar. Clean, text-only tabs indicating the current context: *Transcript*, *Summary*, and *Ask AI*.
3. **Main Canvas (HorizontalPager)**: The hero of the screen. A full-bleed, edge-to-edge scrolling area dedicated entirely to the selected tab's content.
4. **The Audio Pill (Floating Bottom Bar)**: A highly elevated, compact capsule resting just above the system navigation bar. It overlays the main canvas content (which requires bottom padding to prevent occlusion). It houses a play/pause toggle, a minimalist scrubber, a timestamp, and speed controls.

## Jetpack Compose (Material 3) Implementation Details

To build this fluid, modern experience, we will leverage the following Material 3 Compose components:

### 1. The Foundation
* **`Scaffold`**: The root container. We will NOT use the standard `bottomBar` slot for the audio pill, as we want it to float *over* the content. Instead, the pill will be placed inside the `Scaffold`'s `content` lambda using a `Box` with `Alignment.BottomCenter`.
* **`TopAppBar`**: `CenterAlignedTopAppBar` configured with a transparent background that transitions to a solid color upon scrolling.

### 2. The Navigation Canvas
* **`PrimaryTabRow` & `Tab`**: Used for the top-level navigation. Animated indicators will slide beneath the selected tab.
* **`HorizontalPager`**: From the Compose Foundation library. Binds to the `TabRow`'s selected index to allow fluid swipe gestures between the Transcript, Summary, and Chat contexts.

### 3. The Transcript Tab (Text-First Interaction)
* **`LazyColumn`**: To render the transcript efficiently.
* **`ClickableText` / `AnnotatedString`**: The transcript isn't just text; it's a remote control. By parsing the transcript into an `AnnotatedString` with custom annotations for timestamps, tapping any word triggers a callback to the `AudioPlayer` state, instantly seeking the audio and updating the floating pill. The currently spoken word is highlighted using a dynamic `SpanStyle` (e.g., pulling the `MaterialTheme.colorScheme.primary`).

### 4. The Floating Audio Pill
* **`Surface`**: A `Surface` with `RoundedCornerShape(Percent(50))` (a true pill shape), highly elevated (`shadowElevation = 8.dp`), and tinted with `MaterialTheme.colorScheme.surfaceContainerHigh`.
* **`Row`**: Inside the pill, aligned `CenterVertically`.
* **`IconButton` / `Icon`**: For Play/Pause and 1x/1.5x/2x speed controls.
* **`Slider`**: A customized, track-only minimalist slider or a custom drawn `Canvas` for a micro-waveform that acts as the scrubber.

## User Flow

1. **Entry & Focus**: The user imports audio. Once processed, they land directly on the **Transcript** tab. The audio pill sits quietly at the bottom.
2. **Reading & Listening**: The user reads the transcript. They tap a specific sentence. The audio immediately begins playing from that exact millisecond. The Play icon in the floating pill morphs to a Pause icon. The current word highlights as the audio progresses.
3. **Synthesis**: The user swipes left (or taps "Summary"). The `HorizontalPager` glides over to the **Summary** tab. The audio pill remains persistently docked at the bottom, uninterrupted. They can pause or adjust speed while reading the bulleted summary.
4. **Deep Dive**: The user swipes left again to **Ask AI**. They now have a full-screen, focused chat interface to query the document ("What were the main action items?"). Because we avoided a split-screen layout, the keyboard can comfortably open without squashing a persistent audio waveform view. 

## Why This Works
By decoupling the audio playback from a rigid spatial container (like a top-half block) and reducing it to a floating pill, we free up maximum vertical real estate. This is crucial for text-heavy tasks like reading transcripts and chatting with an LLM on a mobile device. The interface feels weightless, modern, and entirely focused on the content.
---

# Concept B: The Modal Assistant
## UX Design Proposal — Audio Processing Studio

**Prepared by:** Principal Android UX Designer  
**Inspiration:** MacWhisper  
**Core Directive:** Immersion first. Zero split-screen friction. 

---

### 1. The Core Philosophy: Transcript is King
In this concept, we deliberately move away from the traditional "dashboard" or "split-pane" approach. When a user opens a processed audio file, they are there to *read and understand*. 

The interface should feel closer to a beautifully typeset Medium article than a complex audio editing tool. The AI assistant and playback controls are subservient to the text—available instantly when needed, but invisible when the user is deep in reading.

### 2. Visual Hierarchy & Layout Strategy

The screen is divided into three functional layers:

1.  **The Canvas (Primary):** A full-bleed, beautifully spaced transcript.
2.  **The Header (Contextual):** Playback controls that gracefully exit the stage as the user reads.
3.  **The Action Layer (On-Demand):** A singular, highly prominent entry point to the AI LLM.

---

### 3. Jetpack Compose (Material 3) Component Architecture

To achieve this fluid, modern Android experience, we will utilize the following Material 3 Compose components:

#### A. `Scaffold` & `LargeTopAppBar` (The Header)
*   **Component:** `LargeTopAppBar`
*   **Behavior:** We will use `TopAppBarDefaults.exitUntilCollapsedScrollBehavior()`. 
*   **Implementation Details:** 
    *   **Expanded State:** Displays the file title, audio waveform (or simple scrubber), play/pause, and skip controls.
    *   **Collapsed State:** As the user scrolls down the transcript, the app bar shrinks to a standard `TopAppBar`, hiding the timeline and secondary controls, leaving only the Title and a miniature Play/Pause toggle.
    *   This ensures the audio controls never permanently eat up valuable vertical screen real estate.

#### B. `LazyColumn` & `SelectionContainer` (The Canvas)
*   **Component:** `LazyColumn` containing text blocks wrapped in a `SelectionContainer`.
*   **Typography:** `MaterialTheme.typography.bodyLarge` with a generous `lineHeight` (e.g., `28.sp` or `32.sp`) and optimal paragraph spacing.
*   **Implementation Details:**
    *   Active speaker segments are highlighted subtly using `MaterialTheme.colorScheme.primaryContainer`.
    *   Timestamps are pushed to the margins or displayed inline with `MaterialTheme.colorScheme.onSurfaceVariant` to maintain reading flow.

#### C. `FloatingActionButton` (The Action Layer)
*   **Component:** `FloatingActionButton` (or `ExtendedFloatingActionButton`).
*   **Iconography:** AI "Sparkle" (`Icons.Rounded.AutoAwesome`).
*   **Placement:** Bottom-End alignment.
*   **Behavior:** Anchored to the bottom right. As the user scrolls down, we can optionally shrink it from an Extended FAB ("Ask AI") to a standard FAB (just the Sparkle icon) to maximize the reading area.

#### D. `ModalBottomSheet` (The AI Assistant)
*   **Component:** `ModalBottomSheet` paired with `rememberModalBottomSheetState()`.
*   **State Configuration:** `skipPartiallyExpanded = false`.
*   **Implementation Details:**
    *   **Half-Expanded (50%):** Tapping the FAB slides the sheet up halfway. The sheet contains the chat interface. Crucially, the top half of the screen still displays the transcript, allowing the user to cross-reference the text while asking the AI to "summarize the last three paragraphs" or "extract action items."
    *   **Fully Expanded (100%):** The user can drag the bottom sheet's drag handle to the top of the screen to enter a fully immersive chat mode.
    *   **Dismissal:** A simple downward swipe dismisses the sheet entirely, instantly returning the user to the undisturbed transcript.

---

### 4. The User Flow: A Narrative Journey

1.  **Immersion:** The user taps a processed audio file. The screen opens with the `LargeTopAppBar` showing the audio file name and a play button. The rest of the screen is pure text.
2.  **Engagement:** The user hits play and begins scrolling. The app bar elegantly collapses upward. The focus is 100% on the transcript. 
3.  **Inquiry:** The user spots an interesting segment and wants to extract the key takeaways. They tap the glowing Sparkle FAB in the bottom right corner.
4.  **Assistance:** The `ModalBottomSheet` slides up to the halfway mark. An AI chat interface greets them: *"What would you like to do with this transcript?"* 
5.  **Multitasking:** The user types, *"Extract the action items from the visible text."* Because the sheet is only half-expanded, they can still read the transcript behind it.
6.  **Deep Work:** The AI generates a long list of action items. The user grabs the sheet's top handle and drags it to full screen to review the list comfortably, copy it, or export it.
7.  **Return:** Once finished, a quick swipe down dismisses the AI, leaving the user exactly where they left off in the transcript.

### 5. Why This Wins

*   **Zero Cognitive Overload:** Split screens force users to divide their attention permanently. This modal approach gives them exactly what they need, exactly *when* they need it.
*   **Familiarity:** Bottom sheets are a deeply native Android paradigm. Users intrinsically know how to drag them, peek at them, and swipe them away.
*   **Readability:** By sacrificing persistent AI controls, we optimize for the task the user will spend 90% of their time doing: reading the text.
---

# Concept C: The Contextual Thread
**UX Proposal for the Audio Processing Studio**

This proposal outlines a document-driven approach to audio transcription and analysis. It abandons the traditional split-screen layout—which forces users to divide their attention between a transcript and a chat window—in favor of a single, living workspace. The transcript itself becomes the canvas for AI interaction.

## 1. Visual Hierarchy and Layout

The entire screen operates as a unified scrolling experience, anchored by fixed controls at the top and bottom.

*   **Top: The Pinned Audio Player.** A compact, persistent control center. It stays glued to the top of the screen, ensuring play/pause and timeline scrubbing are always accessible regardless of scroll depth.
*   **Middle: The Living Document.** A continuous `LazyColumn`. It starts as a pure transcript. As the user interacts, it evolves into an annotated workspace mixing raw text with generated AI insights.
*   **Bottom: The Fixed Chat Input.** A persistent input field for global queries. It anchors the bottom of the screen, ready for broad questions about the entire recording.

## 2. Material 3 Jetpack Compose Architecture

To implement this cleanly, use a `Scaffold` to handle the fixed elements and a `LazyColumn` for the document surface.

*   **`Scaffold`**: The structural foundation.
    *   `topBar`: Houses the Audio Player. Use a `Surface` with `tonalElevation` so it visually separates from the scrolling text below.
    *   `bottomBar`: Houses the Chat Input. Use a `BottomAppBar` or an elevated `Surface` containing an `OutlinedTextField`.
*   **`LazyColumn`**: The main content area. It holds both the transcript paragraphs and the injected AI blocks as distinct list items.
*   **`Text` & custom selection logic**: For the transcript blocks. Instead of standard text selection, wrap paragraphs in custom gesture detectors (long-press) to highlight the entire block and trigger the action menu.
*   **`Popup` or customized `BasicTooltip`**: For the contextual floating toolbar. It anchors directly above the selected paragraph.
*   **`ElevatedCard` / `Card`**: For the injected AI insights. Use `MaterialTheme.colorScheme.secondaryContainer` or `tertiaryContainer` to visually distinguish AI-generated content from the transcript text (`surface`).
*   **`AnimatedVisibility`**: To smoothly expand and inject the AI cards into the list without jarring jumps.

## 3. Core Interactions & User Flow

### Flow 1: Contextual AI Actions (The "Inline Inject")
Users often want to summarize or extract action items from a specific part of the conversation.

1.  **Select**: The user long-presses a specific transcript paragraph. The paragraph highlights (using a muted `primaryContainer` background).
2.  **Act**: A floating toolbar appears directly above the selection with action chips: **Summarize**, **Extract Tasks**, **Explain**.
3.  **Inject**: The user taps "Extract Tasks". The toolbar dismisses. A loading state appears directly below the paragraph. Once ready, a distinct, colored Material `Card` expands inline, pushing the rest of the transcript down.
4.  **Result**: The user reads the extracted tasks exactly where the original context lives. They don't have to look away or scroll to a separate chat window.

### Flow 2: Global Chat (The "Appending Thread")
Users also need to ask broader questions, like "What was the final decision?"

1.  **Ask**: The user taps the bottom text field and types their question.
2.  **Append**: Upon sending, the screen scrolls to the very bottom of the `LazyColumn`.
3.  **Display**: The user's question and the AI's answer are appended as new items at the end of the document, styled similarly to a chat thread but existing purely as the final chapters of the transcript workspace.

## 4. UX Advantages

*   **Context Preservation**: AI insights live immediately adjacent to their source material. Users never lose their place.
*   **Reduced Cognitive Load**: There is no mental context-switching between a "transcript view" and a "chat view". It's just one document.
*   **Exportability**: Because the insights are injected directly into the document flow, exporting the `LazyColumn` content naturally generates a beautifully annotated set of meeting notes or study materials.
