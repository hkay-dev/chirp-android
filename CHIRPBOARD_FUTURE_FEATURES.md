# Chirpboard: Future Feature Architecture & Product Strategy

As a Principal Android Architect and Lead Product Manager, I've analyzed macOS-native audio/transcription tools like **Spokenly, MacWhisper, and Wispr Note**. These tools excel because they blend deeply into the OS, remove friction from capture, and offer powerful post-processing. 

To elevate **Chirpboard** from a great dictation app to a pro-tier productivity engine on Android, we should adopt these 6 high-impact features. 

---

## 1. System-Wide Floating "Quick Dictate" Overlay (Wispr Note style)
**The Feature:** A floating microphone widget or a Quick Settings Tile that users can tap from anywhere in the OS to start recording. Once finished, the text is processed by Gemini and automatically injected into the active text field.
**The "Why":** Wispr Note is famous for its floating menu bar and auto-paste capabilities. While Chirpboard has an IME, sometimes users don't want to switch their default keyboard. A floating overlay or Quick Settings tile offers a zero-friction alternative for spontaneous ideas or replies.
**The "How" (Architecture & Compose):**
*   **System UI:** Implement a `TileService` for the Quick Settings panel, and an `AccessibilityService` (or `SYSTEM_ALERT_WINDOW`) for the floating overlay. 
*   **Auto-Paste:** Use the `AccessibilityService` to find the currently focused `AccessibilityNodeInfo` (specifically `ACTION_SET_TEXT` or `ACTION_PASTE`) to inject the LLM-processed text.
*   **Compose:** Use Compose Window interop (`ViewTreeLifecycleOwner`, `ViewTreeComposeStateRegistry`) to render the floating UI using Compose inside a `WindowManager` layout. Animate the recording state using `updateTransition` and `animateDpAsState` for a pulsing microphone effect, ensuring recomposition is tightly scoped to the mic icon modifier.

## 2. "Share to Chirpboard" Audio Import (MacWhisper style)
**The Feature:** Users can share external audio files (WhatsApp voice notes, lecture recordings, podcast snippets) directly into Chirpboard for transcription and summarization.
**The "Why":** MacWhisper's core loop is drag-and-drop audio file processing. Android users frequently receive audio messages or record voice memos in standard apps but lack a way to extract formatted text from them. 
**The "How" (Architecture & Compose):**
*   **Android Intent:** Add an `<intent-filter>` in the `AndroidManifest.xml` for `android.intent.action.SEND` with `mimeType="audio/*"`.
*   **Processing:** Offload audio extraction and downsampling to an `androidx.media3` or FFmpeg process, managed by `WorkManager` for guaranteed background execution.
*   **Compose:** Use `StateFlow` exposed from a `ViewModel` to track the `WorkInfo.State`. Build a `ModalBottomSheet` in Compose to handle the incoming intent visually, displaying a `LinearProgressIndicator` bound to the `WorkManager` progress data.

## 3. Custom AI Prompt Templates & Contexts (Spokenly style)
**The Feature:** Let users define how they want their voice processed. E.g., "Draft an Email", "Clinical SOAP Note", "Tweet Thread", or "Bulleted To-Do List".
**The "Why":** Spokenly shines by not just transcribing, but formatting. If a user dictates unstructured thoughts, the app should know exactly how to structure the output based on a pre-selected context.
**The "How" (Architecture & Compose):**
*   **Data Layer:** Use `Room` to store user-defined templates (Title, System Instruction, Temperature). Use `DataStore` to persist the "active" template.
*   **LLM Integration:** Pass the selected template as the `systemInstruction` in the Gemini API call.
*   **Compose:** Implement a horizontal `LazyRow` of selectable filter chips above the keyboard or recording UI. Use `Modifier.selectableGroup()` for accessibility. For the management screen, use a `LazyColumn` with a `SwipeToDismissBox` for deleting templates.

## 4. Advanced Subtitle Export: SRT & VTT (MacWhisper style)
**The Feature:** Export transcriptions as standard `.srt` or `.vtt` files with accurate timestamps, alongside the Obsidian Markdown sync.
**The "Why":** Video creators and podcasters rely on MacWhisper to generate subtitles. Bringing this to Chirpboard taps into the creator economy, making the app indispensable for mobile-first content creators.
**The "How" (Architecture & Compose):**
*   **Data Parsing:** Since local Whisper provides segment-level timestamps, map this data layer to an SRT/VTT formatter utility function.
*   **File System:** Use the Storage Access Framework (`ActivityResultContracts.CreateDocument()`) so users can save the file anywhere on their device.
*   **Compose:** In the transcription detail screen, add an `ExportBottomSheet`. Use `LaunchedEffect` to observe the activity result and trigger a `SnackbarHostState` upon successful export. 

## 5. Speaker Diarization [Who Said What] (MacWhisper Pro style)
**The Feature:** Automatically detect different speakers in a multi-person conversation and label them (Speaker A, Speaker B) in the transcript.
**The "Why":** Meeting notes and interviews are incredibly difficult to read as a single wall of text. Diarization is a premium feature on desktop that would make Chirpboard a killer app for journalists and professionals.
**The "How" (Architecture & Compose):**
*   **ML Architecture:** Local diarization on Android is computationally heavy. We can implement a lightweight PyTorch Mobile / ONNX model for voice embedding clustering, or offload this specific feature to Gemini 1.5 Pro's native multimodal audio understanding (which inherently supports speaker separation).
*   **Compose:** Represent the transcript as a chat UI. Use a `LazyColumn` where each `item` checks the speaker ID. Use `Modifier.background(MaterialTheme.colorScheme.surfaceVariant)` and align `Arrangement.End` or `Arrangement.Start` depending on the speaker, ensuring `key` parameters are used in the `LazyColumn` to prevent unnecessary recompositions.

## 6. Offline LLM Support via AICore (Future-Proofing)
**The Feature:** Use Google's Gemini Nano (via Android AICore) to do the summarization and formatting entirely offline, matching the local Whisper transcription.
**The "Why":** The biggest selling point of MacWhisper and Wispr is privacy. Since Chirpboard already has local Whisper, moving the LLM layer on-device guarantees 100% data privacy for sensitive recordings (e.g., medical, legal).
**The "How" (Architecture & Compose):**
*   **Android AICore:** Integrate the `com.google.android.gms:play-services-aicore` SDK. Implement logic to request the model download and manage the generation session.
*   **Compose:** Model downloading requires robust UI state handling. Create a sealed interface (`ModelDownloadState: Idle, Downloading(progress), Ready, Error`). Use `AnimatedContent` in Compose to smoothly transition between a "Download local AI" button, a progress ring, and the final generation UI.