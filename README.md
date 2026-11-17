# ORACLE STUDIO

**Your intelligence workspace.** A native Android application combining a Gemini chat operator, workspace-aware document intelligence, and a local workflow studio in a handcrafted Jetpack Compose interface.

## Product principles
- Native Android, offline-first local workspace, Gemini API key supplied by the user.
- API credentials are encrypted at rest with AES-GCM; the encryption key is held by Android Keystore.
- Agent actions are transparent, permission-gated, auditable, and explicitly confirmed before external or destructive effects.
- Original ORACLE identity: a light-by-default porcelain workbench, near-black editorial typography, restrained blue accents, precise spacing, and progressive disclosure. A semantic palette keeps contrast consistent across every workspace.

## Build
Open this repository in Android Studio or run:

```bash
gradle --no-daemon lintDebug testDebugUnitTest assembleDebug
```

Use JDK 17 and Android SDK 36. The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions runs Android lint and unit tests, builds the debug APK, verifies alignment/signature, and uploads the APK plus verification reports.

## Implemented

### Gemini chat
- Chat-first Jetpack Compose workbench with animated navigation, thread drawer, message history, and expandable model controls.
- Animated branded startup splash and an animated empty-conversation stage with tappable mission starters.
- Default model for new installations: `gemini-3.5-flash-lite` (Gemini 3.5 Flash-Lite); users can still select any model exposed by their API key.
- Multiple named Gemini API-key profiles can be stored with independent Android Keystore AES-GCM encryption and switched without re-pasting credentials.
- Push-to-talk voice input uses Android speech recognition; an optional spoken-reply mode reads completed Gemini responses with Android TextToSpeech. This is turn-based voice interaction, not a persistent full-duplex Gemini Live session.
- Assistant replies provide one-tap copy and Android share-sheet actions.
- Gemini API key validation and AES-GCM encryption backed by Android Keystore. No API key is embedded in source or APK.
- Live Gemini Agent using `generateContent`, visible request errors, model discovery, and model selection.
- Persistent agent threads and messages in Room; thread titles are generated locally from the first user prompt.
- Cancellable OkHttp requests. Failed/cancelled prompts remain available for retry without duplicating the saved user turn.
- Bounded conversation context (last 12 messages) with a send-point disclosure in the composer.

### Workspaces
- A Personal default workspace plus local create/switch controls in the conversation drawer.
- Active workspace selection persists in DataStore.
- Agent threads, imported documents, workflows, notes, workflow run history, and execution logs are scoped to the active workspace.
- Identically named workflows and notes can exist independently in separate workspaces; a workspace cannot update or delete another workspace's workflows through repository operations.

### Document Library
- Imports PDF with selectable text, UTF-8 plain text, Markdown, and Office Open XML DOCX/PPTX/XLSX from Android's document picker; originals are copied into app-private storage.
- PDF extraction preserves page numbers. DOCX citations retain the document-body label; PPTX citations retain slide numbers; XLSX citations retain worksheet names. TXT/Markdown use stable section citations. Extracted text is chunked and indexed in Room.
- Office extraction is offline and bounded: it reads only required XML parts from the archive, never executes macros, and does not extract embedded images, charts, comments, or tracked-change metadata.
- Offline keyword retrieval, document reader, and Gemini library Q&A.
- Library Q&A displays the exact retrieved excerpts and requires confirmation before sending them to Gemini.

### Agentic Operator
- Gemini Interactions loop: model → tool call → execution → function result → model, with a hard limit of 16 model rounds and 32 combined tool calls per run; transient network failures retry with backoff and cancellation stops in-flight OkHttp calls.
- Built-in Gemini capabilities: URL Context and hosted Python. Both are disabled by default and require explicit opt-in in the composer quick switches. Gemini Google Search grounding is not enabled.
- Independent live web research via Tavily and a separately encrypted API key stored through Android Keystore.
- Composer quick switches independently enable web search, phone files, local Python, hosted Python, URL Context, image generation and all custom tools. Hosted Python and URL Context start off until the user enables them.
- The main agent exposes a DRAW tool that calls Gemini 3.1 Flash Image as a separate service. Generated PNGs are saved in the app workspace and displayed inline. The direct image model can also be selected in the Model Atlas; direct image mode omits tool payloads because the model does not support function calling.
- Phone files are accessed through a user-selected Storage Access Framework folder. Paths are scoped, traversal is rejected, and text read/write operations have size limits.
- Reading file contents sent to Gemini, modifying files, running local Python, web searches sent to Tavily, and generating images require explicit permission. Session approvals are capability-scoped and limited to the current conversation: Python approval does not authorize phone-file access, file reads do not authorize file writes, and web-search approval does not authorize other tools. Each Tavily query is shown before sending unless web-search access was explicitly approved for that conversation.
- Bundled CPython 3.13 via Chaquopy exposes standard Python built-ins, supported standard-library modules, network and an app-private working directory. It runs under Android's app UID; it is not an unrestricted OS-level Termux shell.
- Python-created or modified workspace files are surfaced as in-chat artifacts and text/code files can be opened and edited in the in-app editor.

### Rich Agent Interface
- Assistant responses render headings, bold/italic text, lists, tables, quotes, fenced code with copy, Markdown links, images and simple process-map diagrams.
- A compact colored Plan & Work Log rail sits above the assistant answer; expand it to inspect actual tool calls, code and results. Created files also appear as extension-aware artifact cards that open the exact file in ORACLE. Private model thought steps are not exposed.
- Token usage is a small circular control with a detailed ledger on tap.
- Generated images/audio/video/documents and saved files are surfaced inline where supported.
- Model / Tools opens practical quick controls; Explore Models opens a full-screen model atlas with API metadata, descriptions, context limits, tags and artwork.
- The composer and model controls use translucent glass surfaces and animated content transitions.

### Workflow Studio
- Create, edit, reorder, save, and delete workflows with up to 20 ordered steps.
- Four explicit local actions: create a note, append to a note by exact title, search the on-device document index, and search the active workspace's audio titles/transcripts/summaries/tasks/decisions. Audio search never uploads recordings or transcripts.
- Manual runs require an explicit confirmation. An optional recurring schedule can be enabled per workflow and workspace; enabling or changing it saves the current plan and requires a separate confirmation. Scheduled runs use Android WorkManager and execute the same allowlisted local actions without Gemini or network access.
- Scheduled runs are labeled in the execution log. Invalid saved plans are recorded as skipped/invalid runs instead of being silently ignored. Schedules can be disabled at any time and are cancelled when their workflow is deleted.
- WorkManager periodic schedules have a 15-minute minimum and are deferrable: Android may run them later to respect battery and system constraints. They are not exact alarms or event-based triggers. Repeated create/append steps can create duplicate notes or repeated text; the schedule confirmation calls this out before opt-in.
- Successful runs keep their execution trace on the assistant response; failed and cancelled runs attach permission decisions and completed tool events to the originating user turn, so retries retain the audit history.
- A failed step stops the run. Deleting a workflow removes its steps and run history but preserves notes created by previous runs.
- Workflow execution is local-only: no Gemini request, network calls, shell access, arbitrary scripts, app launching, scheduled triggers, or background execution.

### Audio Memory Studio
- Records microphone audio locally with Android AudioRecord as 16 kHz mono PCM, then wraps it in a standard WAV file for Gemini-compatible audio input.
- Recording requires the Android microphone permission, has a 10-minute per-recording limit, is automatically finalized when the app enters the background, and is never uploaded automatically. ORACLE does not run a background microphone service.
- Audio files, titles, transcripts, summaries, extracted tasks, and decisions are stored in the active workspace and indexed for offline search.
- Recordings can be played back locally. Transcription requires a separate per-recording confirmation that explains the Google Gemini upload.
- The approved WAV is uploaded through the Gemini Files API and transcribed with Google's dedicated Gemini 3.5 Transcribe model in VERBATIM mode with automatic language detection. ORACLE then sends the resulting transcript—not the audio again—to the selected Gemini model for a concise summary, explicit tasks, and decisions. It attempts to delete the temporary remote audio file immediately after transcription; Google documents automatic Files API deletion after 48 hours if manual cleanup does not occur.
- The UI reports when remote cleanup cannot be confirmed. Local recordings remain available; audio and the Room database are app-private but not encrypted at rest.
- This is file-based, user-initiated transcription—not live transcription, background capture, or Gemini Live streaming.

### Verification
- Unit tests cover conversation context, session naming, workspace-name normalization (including Persian text), document chunk boundaries, PDF citations, stable text sections, keyword ranking, workflow plan validation, audio-memory workflow execution and workspace isolation, and Room workspace-isolation queries/mutations under Robolectric.

## In progress / not yet available
- Image OCR, scanned-PDF OCR, document summaries, flashcards, quizzes, semantic embeddings, and knowledge graph. Legacy binary DOC/XLS/PPT formats remain unsupported.
- Event-based triggers, exact-time alarms, and arbitrary external integrations. Periodic schedules are available through Android WorkManager and remain subject to OS deferral.
- Additional workflow actions such as export, file operations, notifications, and external integrations.
- Persistent full-duplex Gemini Live audio streaming and interruption handling. Audio Memory provides saved recordings and file-based Gemini transcription, not live streaming.
- Arbitrary OS-level shell/Termux access, app launching, UI automation, scheduled triggers, and background agents are not implemented. The Agent can use the scoped tools described above, subject to user-selected capabilities and per-session/action approval.

## Privacy and data handling
- The API key is stored locally as AES-GCM ciphertext; its non-exportable encryption key is managed by Android Keystore.
- The Gemini key is sent over HTTPS only to Google's Gemini API when a user invokes validation, model discovery, or generation. The separate Tavily key is sent only to Tavily when the user approves a web-search query.
- Agent threads, workflows, notes, run history, and document data are stored in the app-private Room database; they are not uploaded automatically.
- Workflow actions run locally. A document-search step queries the on-device index; it does not send excerpts to Gemini.
- The Room database, imported originals, and Audio Memory WAV files are app-private but are **not encrypted at the database/file layer**. Do not store highly sensitive content until encrypted local-data storage is implemented.
- Library Q&A is opt-in: only the question and up to six retrieved excerpts are sent to the selected Gemini model after explicit confirmation. Audio transcription is also opt-in per recording; only that selected WAV is uploaded, and ORACLE attempts remote file deletion immediately after transcription.

## Document library scope and limits
- Supported imports: PDF with selectable text, UTF-8 plain text, Markdown, DOCX, PPTX, and XLSX. Maximum source file size is 20 MB; PDFs and presentations are limited to 400 pages/slides; workbooks are limited to 100 worksheets; extracted text is capped at 1.5 million characters per file. Office XML parts are capped at 12 MB, archive expansion at 80 MB, and archives at 2,000 entries.
- PDFs with no selectable text are rejected; image-only pages in mixed PDFs are skipped. Office embedded images/charts and scanned content are not OCR-extracted. Standalone images and legacy binary DOC/XLS/PPT are not supported.
- Retrieval uses on-device lexical keyword matching, not embeddings or semantic vector search. PDF answers cite original page numbers; text and Markdown answers cite indexed sections.
- User-facing thread deletion, workspace deletion, and full workspace export are not available yet. Uninstalling the app removes its app-private data.

## Database compatibility
Room schema v8 retains existing agent, document, workflow, and workspace data. Migration 6→7 adds workspace-scoped Audio Memory records and search indexes; migration 7→8 adds workspace-scoped recurring workflow trigger records without modifying existing rows. Earlier 3→4 compatibility remains idempotent across the two v3 layouts.


### Release polish
- Startup splash and animated new-conversation idle scene.
- Turn-taking voice conversation: speech recognition captures a turn, Gemini responds, Android TTS speaks the answer, and the microphone resumes until voice mode is disabled.
- Generated files appear as extension-aware, tappable artifact cards that open the exact local file in the ORACLE editor.
- The agent loop is bounded to 16 model rounds and 32 combined hosted/local tool calls per run to control runaway loops and API cost. Tool-call batches are reserved atomically; when the budget is exhausted, ORACLE stops before executing additional local actions. Transient connection, response-stream, and retryable HTTP failures use exponential backoff; the user can cancel a run at any time. Session approvals are scoped to individual capability groups.
- The Gemini API key vault supports multiple encrypted named keys and switching the active key without re-entering credentials.

- Voice listener state is lifecycle-safe and microphone permission is declared once in the Android manifest.
