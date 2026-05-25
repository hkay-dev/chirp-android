> **Archive note:** Historical paths below reference the pre-split `:core` module. See [`PATH_REMAPPING.md`](PATH_REMAPPING.md) for current locations.
>

# Chirpboard: Future Features v2 (Product Roadmap)

As we refine Chirpboard from a raw voice recording utility into a premium intelligence tool, our roadmap shifts away from system-wide floating bubbles and fully offline LLMs toward high-value post-processing and seamless daily workflows.

Here are the top 5 high-impact features prioritized for the next major iterations.

## 1. The Processing Studio (The Anchor Feature)
**The Vision**
Transform Chirpboard from a mere capture tool into a dedicated audio intelligence workspace. Users can use their Chirp recordings or import external audio (meetings, lectures, voice memos), transcribe them with local Whisper, and then interact with the text. The UI offers a split-pane experience: an audio player on top, and an interactive LLM chat session below (e.g., "What were the action items in this meeting?", "Summarize the second speaker's point").

**The Value**
Turns passive audio into an active querying surface. By linking text to audio, users can verify AI summaries instantly. It becomes the ultimate study or meeting companion.

**The Architecture (Android/Compose)**
- **Intents:** Register `<intent-filter>` for `ACTION_SEND` and `ACTION_VIEW` targeting `audio/*` MIME types to allow importing from other apps. Provide an in-app file picker via `ActivityResultContracts.GetContent()`.
- **UI:** A Compose `Scaffold` where the top half is a sticky `ExoPlayer`-backed audio visualizer, and the bottom half is a `LazyColumn` containing the transcript and chat interface.
- **Sync:** The transcript items will hold timestamp metadata. Clicking a transcript segment fires an event to `ExoPlayer.seekTo(timestamp)`.
- **LLM Integration:** Pass the entire transcript as context to the LLM alongside the user's prompt to generate answers based purely on the audio content.

## 2. Live Audio Bookmarks ("Moment Pins")
**The Vision**
While recording a long session, users hear something important. Instead of scrubbing through a 45-minute file later, they tap a "Pin" button. When transcription completes, the LLM explicitly extracts and highlights these timestamped pinned moments in the summary.

**The Value**
Reduces the cognitive load of reviewing long recordings. It bridges the gap between passive listening and active note-taking.

**The Architecture (Android/Compose)**
- **State:** The Recording Service maintains a `List<Long>` of pinned timestamps.
- **UI:** Add a prominent "Drop Pin" `IconButton` in the active recording Compose screen and as an action in the foreground `Notification`.
- **Processing:** During the LLM summarization phase, inject a prompt directive: *"The user explicitly bookmarked timestamps X, Y, and Z. Give special attention to the context around these moments and feature them prominently in the summary."*
- **Visuals:** In the transcript view, highlight the segments that correspond to the pinned timestamps.

## 3. Retroactive Capture (Rolling Audio Buffer)
**The Vision**
For the "I wish I recorded that" moments. A background service maintains a rolling 3-5 minute audio buffer. If the user hears something brilliant or a sudden spontaneous conversation happens, they open the app, hit "Save last 5 minutes," and the app flushes the buffer to disk and transcribes it.

**The Value**
Captures fleeting thoughts and sudden interactions without the pressure of always actively hitting record. A true "always-ready" dictation tool.

**The Architecture (Android/Compose)**
- **Service:** A persistent foreground service running an `AudioRecord` instance that writes to a circular buffer (e.g., using a fixed-size byte array or a specialized RingBuffer implementation) in memory.
- **Persistence:** When triggered, dump the buffer to a PCM/WAV file on disk, then trigger the standard Whisper transcription pipeline.
- **Privacy/Battery:** Requires clear user consent, a persistent notification indicating the microphone is hot, and optimized low-level audio reading to minimize battery drain.

## 4. The "Daily Thread" (Continuous Journaling)
**The Vision**
Instead of cluttering the app with 10 separate audio files a day, users can opt into a "Daily Journal" mode. Every time they dictate, it appends the audio and the transcript to today's master file. The LLM continuously updates a rolling daily summary.

**The Value**
Perfect for Obsidian integration. It mimics how people actually think—in bursts throughout the day—while outputting a single, clean "Daily Note" that syncs seamlessly to their PKM (Personal Knowledge Management) system.

**The Architecture (Android/Compose)**
- **Data Model:** Update the Room Database schema to group `Recording` entities by a `LocalDate`. 
- **Processing:** Instead of summarizing a single file, the LLM receives the aggregated transcript of the day. As new segments are appended, trigger an LLM worker to regenerate the `DailySummary` field.
- **Sync:** The Obsidian sync worker pushes a single `YYYY-MM-DD.md` file, appending new transcripts and overwriting the summary block at the top.

## 5. Semantic Actions (Calendar & Todo Integrations)
**The Vision**
Move beyond passive text summaries into active task generation. The LLM is instructed to output structured JSON of detected tasks, events, or reminders. The Compose UI parses this JSON and presents 1-tap buttons to push these items directly to Google Calendar or Todoist.

**The Value**
Saves users the step of manually copying and pasting dates and tasks. It turns voice directly into structured productivity.

**The Architecture (Android/Compose)**
- **LLM Prompting:** Enforce structured output (JSON schema) from the LLM model to reliably parse tasks (title, due date) and events (title, start time, end time).
- **UI:** A Compose `LazyRow` or card layout below the summary displaying actionable items.
- **Integration:** 
  - *Calendar:* Use `Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)` to pre-fill the system calendar app.
  - *Todoist:* Use an HTTP client (like Ktor or Retrofit) to push tasks to the Todoist REST API (requires user OAuth/API token setup in settings).