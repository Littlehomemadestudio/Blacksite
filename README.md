# ORACLE STUDIO

**Your intelligence workspace.** A native Android application combining a Gemini chat operator, workspace-aware document intelligence, and a local workflow studio in a handcrafted Jetpack Compose interface.

## Product principles
- Native Android, offline-first local workspace, Gemini API key supplied by the user.
- API credentials are encrypted at rest with AES-GCM; the encryption key is held by Android Keystore.
- Agent actions are transparent, permission-gated, auditable, and explicitly confirmed before external or destructive effects.
- Original ORACLE identity: a dark-by-default graphite workbench, warm oxide accents, editorial typography, precise spacing, and progressive disclosure. A semantic palette keeps contrast consistent across every workspace.

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
- Imports PDF with selectable text and scanned pages, JPEG/PNG/WebP images, UTF-8 plain text, Markdown, and Office Open XML DOCX/PPTX/XLSX from Android's document picker; originals are copied into app-private storage.
- A bundled ML Kit Latin-script recognizer performs image OCR on-device. Image-only PDF pages are rendered locally and OCR-processed (up to 100 blank-text pages per import); successful results retain the original PDF page number. OCR never uploads source files or extracted text.
- DOCX citations retain the document-body label; PPTX citations retain slide numbers; XLSX citations retain worksheet names. TXT/Markdown use stable section citations. Extracted text is chunked and indexed in Room.
- Office extraction is offline and bounded: it reads only required XML parts from the archive, never executes macros, and does not extract embedded images, charts, comments, or tracked-change metadata.
- Offline keyword retrieval, document reader, and Gemini library Q&A.
- Library Q&A displays the exact retrieved excerpts and requires confirmation before sending them to Gemini.
- Document Study generates summaries with cited key points, tap-to-reveal flashcards, and interactive multiple-choice quizzes. It samples up to 12 source chunks across the document, caps the approved excerpt payload at 20,000 characters, validates every returned source ID against the selected excerpts, and stores the result in Room for offline revision.
- Knowledge Map builds a workspace-scoped local term co-occurrence graph from indexed passages. Concepts retain source document, page/slide/section labels, and bounded evidence excerpts; connected terms can be explored visually and opened in the document reader. Rebuilding is explicit, offline, deterministic, and does not call Gemini or claim semantic similarity.
- Study generation is online and opt-in: the user reviews the exact excerpt set and selected Gemini model before sending. The original document and unselected passages remain local; generated study sets are workspace-scoped and cascade-delete with their source document.

### Agentic Operator
- Gemini Interactions loop: model → tool call → execution → function result → model, with a hard limit of 16 model rounds and 32 combined tool calls per run; transient network failures retry with backoff and cancellation stops in-flight OkHttp calls.
- Built-in Gemini capabilities: URL Context and hosted Python. Both are disabled by default and require explicit opt-in in the composer quick switches. Gemini Google Search grounding is not enabled.
- Independent live web research via Tavily and a separately encrypted API key stored through Android Keystore.
- CrossMemory is off by default. When enabled, model-proposed memories stay in local SharedPreferences; only keyword-relevant entries (up to six) are included with a later request. This store is not encrypted at rest and can be cleared from Settings.
- Composer quick switches independently enable web search, phone files, local Python, hosted Python, URL Context, image generation and all custom tools. Hosted Python and URL Context start off until the user enables them.
- The main agent exposes a DRAW tool that calls Gemini 3.1 Flash Image as a separate service. Generated PNGs are saved in the app workspace and displayed inline. The direct image model can also be selected in the Model Atlas; direct image mode omits tool payloads because the model does not support function calling.
- Phone files are accessed through a user-selected Storage Access Framework folder. Paths are scoped, traversal is rejected, and text read/write operations have size limits.
- Reading file contents sent to Gemini, modifying files, running local Python, web searches sent to Tavily, and generating images require explicit permission. Session approvals are capability-scoped and limited to the current conversation: Python and phone-file writes always require a fresh per-action approval; file reads do not authorize file writes, and web-search approval does not authorize other tools. Each Tavily query is shown before sending unless web-search access was explicitly approved for that conversation.
- Bundled CPython 3.13 via Chaquopy runs under ORACLE's Android app UID, can access app-private ORACLE files and has network access; it is not an OS security sandbox. Every run requires fresh approval, shows the exact source (maximum 16 KB), and returns printed output to Gemini. The runner applies a 30-second line-execution guard and a 128 KiB output cap; these are reliability guardrails, not a hard security boundary. Do not run untrusted code.
- Phone-file reads use bounded streaming with strict UTF-8 decoding and a 10 MB cap; phone-file writes are capped at 10 MB and require a fresh approval for each action.
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
- Workflow execution is local-only: no Gemini request, network calls, shell access, arbitrary scripts, or app launching. Optional periodic WorkManager triggers are described above and remain subject to OS deferral.

### Examination Studio
- Dedicated, user-initiated assessment authoring for practice, diagnostic, chapter, midterm, final, and mock examinations. Ordinary tutoring does not invoke exam generation.
- Configurable subject, grade, topics, assessment format, difficulty, question count, duration, total marks, question-type mix, English/Persian language, answer-space density, school/class/teacher metadata, candidate instructions, source material, and answer-key generation.
- Gemini returns a structured paper and a separate answer key/rubric. ORACLE validates supported question types, unique question IDs/numbers, positive marks, complete marking-scheme coverage when requested, and exact mark totals before saving.
- The paper is rendered as a document-style examination sheet with institutional metadata, candidate fields, numbered sections, question marks, taxonomy tags, options, and written-answer space. Persian papers use right-to-left layout.
- Export a paginated A4 PDF through Android's system document picker. Paper-only export is the default; including the marking scheme is a separate, explicit action. Persian text direction and answer-space lines are retained.
- Papers and attempts are stored locally in Room and isolated by workspace. Candidates can begin, resume, save progress, submit, and review the marking scheme. Objective answers can be scored locally; constructed responses remain explicitly pending manual marking.
- Chat supports a fenced `oracle-exam` structured output rendered as an examination document with an interactive attempt mode. The agent is instructed to use it only when the user explicitly asks for an exam or test paper; `oracle-choice` is reserved for explicitly requested short practice checks.

### Audio Memory Studio
- Records microphone audio locally with Android AudioRecord as 16 kHz mono PCM, then wraps it in a standard WAV file for Gemini-compatible audio input. Capture files are created in a dedicated cache directory and encrypted with streaming AES-GCM before they enter persistent app storage.
- Recording requires the Android microphone permission, has a 10-minute per-recording limit, is automatically finalized when the app enters the background, and is never uploaded automatically. ORACLE does not run a background microphone service.
- Audio files, titles, transcripts, summaries, extracted tasks, and decisions are stored in the active workspace and indexed for offline search.
- Recordings can be played back locally. Transcription requires a separate per-recording confirmation that explains the Google Gemini upload.
- The approved WAV is uploaded through the Gemini Files API and transcribed with Google's dedicated Gemini 3.5 Transcribe model through the Gemini Interactions API, using automatic language detection. ORACLE then sends the resulting transcript—not the audio again—to the selected Gemini model for a concise summary, explicit tasks, and decisions. Transcription and analysis retry transient network, rate-limit, and server failures with bounded backoff; cancellation stops in-flight requests. It attempts to delete the temporary remote audio file immediately after transcription; Google documents automatic Files API deletion after 48 hours if manual cleanup does not occur.
- The UI reports when remote cleanup cannot be confirmed. New persistent WAV payloads use AES-GCM with a non-exportable Android Keystore key. Playback and approved transcription create a temporary plaintext cache copy that is deleted on completion, playback stop, or app backgrounding; abandoned cache files are cleaned when the archive is reopened. Existing pre-encryption WAVs are migrated when their workspace's Audio Memory archive is opened; migration failures are surfaced and the affected legacy file is not materialized for playback or transcription. Audio titles, transcripts, summaries, tasks, decisions, and the Room database remain app-private but are not encrypted at rest.
- This is file-based, user-initiated transcription—not live transcription, background capture, or Gemini Live streaming.

### Verification
- Unit tests cover conversation context, session naming, workspace-name normalization (including Persian text), document chunk boundaries, PDF citations, stable text sections, keyword ranking, DOCX/PPTX/XLSX extraction and source labels, XLSX shared/inline cell values and unsafe relationship rejection, cited study-kit parsing/round-tripping, bounded source sampling, quiz answer-index validation, study-kit workspace/source isolation and cascade deletion, knowledge graph term normalization, stable IDs, co-occurrence edges, source mentions, output limits, and workspace isolation/cascade behavior, workflow plan validation, OCR page-selection and image/render-size limits, audio-memory workflow execution and workspace isolation, AES-GCM audio file round-tripping, record-ID authentication, ciphertext tamper rejection, legacy audio migration and temporary-file cleanup, examination JSON validation, exact mark totals, objective grading, answer persistence codecs, PDF export content planning and filename safety, and Room workspace-isolation queries/mutations under Robolectric.

## In progress / not yet available
- semantic embeddings and vector retrieval. The Knowledge Map is lexical co-occurrence, not semantic inference; legacy binary DOC/XLS/PPT formats remain unsupported. Study aids require a Gemini connection and explicit excerpt approval; they cannot be generated offline. Exam Studio does not directly invoke Android's print service or automatically grade constructed responses; its source-material field is manually supplied text rather than a live Library retrieval picker.
- Event-based triggers, exact-time alarms, and arbitrary external integrations. Periodic schedules are available through Android WorkManager and remain subject to OS deferral.
- Additional workflow actions such as export, file operations, notifications, and external integrations.
- Persistent full-duplex Gemini Live audio streaming and interruption handling. Audio Memory provides saved recordings and file-based Gemini transcription, not live streaming.
- Arbitrary OS-level shell/Termux access, app launching, UI automation, scheduled triggers, and background agents are not implemented. The Agent can use the scoped tools described above, subject to user-selected capabilities and per-session/action approval.

## Privacy and data handling
- The API key is stored locally as AES-GCM ciphertext; its non-exportable encryption key is managed by Android Keystore.
- The Gemini key is sent over HTTPS only to Google's Gemini API when a user invokes validation, model discovery, or generation. The separate Tavily key is sent only to Tavily when the user approves a web-search query.
- Agent threads, workflows, notes, run history, and document data are stored in the app-private Room database; they are not uploaded automatically.
- CrossMemory is opt-in/off by default. When enabled, only keyword-relevant local entries are sent with the current Gemini request; its SharedPreferences store is not encrypted at rest and can be cleared from Settings.
- Workflow actions run locally. A document-search step queries the on-device index; it does not send excerpts to Gemini.
- Image and scanned-page OCR use a bundled on-device model. Importing and indexing images does not send files or recognized text to Gemini.
- Audio Memory WAV payloads are encrypted at rest with streaming AES-GCM and an Android Keystore key. The Room database, imported document originals, note content, audio titles/transcripts, and CrossMemory SharedPreferences remain app-private but are **not encrypted at rest**. Temporary plaintext audio is limited to dedicated cache directories and is deleted after use or on the next archive open. Do not store highly sensitive metadata or documents until database and general file encryption are implemented.
- Library Q&A is opt-in: only the question and up to six retrieved excerpts are sent to the selected Gemini model after explicit confirmation. Audio transcription is also opt-in per recording; only that selected WAV is uploaded, and ORACLE attempts remote file deletion immediately after transcription.

## Document library scope and limits
- Supported imports: selectable-text and scanned-page PDF, JPEG/PNG/WebP images, UTF-8 plain text, Markdown, DOCX, PPTX, and XLSX. Maximum source file size is 20 MB; PDFs and presentations are limited to 400 pages/slides; OCR processes at most 100 image-only PDF pages per import; workbooks are limited to 100 worksheets; extracted text is capped at 1.5 million characters per file. Office XML parts are capped at 12 MB, archive expansion at 80 MB, and archives at 2,000 entries.
- Scanned PDF pages and standalone images use the bundled offline ML Kit Latin-script OCR model. Original PDF page numbers are retained. Persian/Arabic-only text, handwriting, and image-only content embedded in Office documents are not supported by this OCR model. DOCX indexes the main document body (not headers, footers, or footnotes); PPTX indexes slide text (not speaker notes); XLSX indexes cached/displayed cell values and does not evaluate formulas. Legacy binary DOC/XLS/PPT remain unsupported.
- Retrieval uses on-device lexical keyword matching, not embeddings or semantic vector search. PDF answers cite original page numbers; DOCX citations identify the document body; PPTX citations identify slide numbers; XLSX citations identify worksheet names; TXT/Markdown answers cite indexed sections.
- Knowledge Map extracts bounded unigrams and adjacent-token phrases from at most 20,000 indexed chunks, keeps up to 120 concepts, 240 co-occurrence links, and 6,000 source mentions per workspace. It excludes common English/Persian stop words, normalizes Arabic yeh/kaf to Persian forms, and records source excerpts for inspection. Connections mean terms appeared in the same indexed passage; they do not establish a factual, causal, or semantic relationship. Rebuild after importing or deleting documents to refresh the snapshot.
- User-facing thread deletion, workspace deletion, and full workspace export are not available yet. Uninstalling the app removes its app-private data.

## Database compatibility
Room schema v11 retains existing agent, document, workflow, audio, examination, and workspace data. Migration 10→11 adds workspace-scoped knowledge concepts, source mentions, and co-occurrence edges without rewriting existing documents. Migration 6→7 adds workspace-scoped Audio Memory records and search indexes; migration 7→8 adds workspace-scoped recurring workflow trigger records; migration 8→9 adds workspace-scoped examination papers and attempts; migration 9→10 adds workspace-scoped document study sets with source-document cascade deletion without modifying existing rows. Earlier 3→4 compatibility remains idempotent across the two v3 layouts.


### Release polish
- Startup splash and animated new-conversation idle scene.
- Turn-taking voice conversation: speech recognition captures a turn, Gemini responds, Android TTS speaks the answer, and the microphone resumes until voice mode is disabled.
- Generated files appear as extension-aware, tappable artifact cards that open the exact local file in the ORACLE editor.
- The agent loop is bounded to 16 model rounds and 32 combined hosted/local tool calls per run to control runaway loops and API cost. Tool-call batches are reserved atomically; when the budget is exhausted, ORACLE stops before executing additional local actions. Transient connection, response-stream, and retryable HTTP failures use exponential backoff; the user can cancel a run at any time. Session approvals are scoped to individual capability groups.
- The Gemini API key vault supports multiple encrypted named keys and switching the active key without re-entering credentials.

- Voice listener state is lifecycle-safe and microphone permission is declared once in the Android manifest.
