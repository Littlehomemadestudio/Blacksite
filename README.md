# ORACLE STUDIO

**Your intelligence workspace.** A native Android application combining a Gemini chat operator, workspace-aware document intelligence, and a local workflow studio in a handcrafted Jetpack Compose interface.

## Product principles
- Native Android, offline-first local workspace, Gemini API key supplied by the user.
- API credentials are encrypted at rest with AES-GCM; the encryption key is held by Android Keystore.
- Agent actions are transparent, permission-gated, auditable, and explicitly confirmed before external or destructive effects.
- Original ORACLE identity: a dark-by-default graphite workbench, warm oxide accents, editorial typography, precise spacing, and progressive disclosure. Semantic tertiary text and light-theme accent colors are tuned for stronger contrast.

## Build
Open this repository in Android Studio or run:

```bash
gradle --no-daemon lintDebug testDebugUnitTest assembleDebug
```

Use JDK 17 and Android SDK 36. The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions runs Android lint and unit tests, builds the debug APK, verifies alignment/signature, and uploads the APK plus verification reports.

## Implemented

### Gemini chat
- Chat-first Jetpack Compose workbench with animated navigation, thread drawer, message history, and expandable model controls.
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
- Imports PDF with selectable text, UTF-8 plain text, and Markdown from Android's document picker; originals are copied into app-private storage.
- PDF extraction preserves page numbers. TXT/Markdown are segmented into stable section citations; extracted text is chunked and indexed in Room.
- Offline keyword retrieval, document reader, and Gemini library Q&A.
- Library Q&A displays the exact retrieved excerpts and requires confirmation before sending them to Gemini.

### Agentic Operator
- Gemini Interactions loop: model → tool call → execution → function result → model, bounded to 12 tool rounds.
- Built-in Gemini capabilities: URL Context and hosted Python. Gemini Google Search grounding is not enabled.
- Independent live web research via Tavily and a separately encrypted API key stored through Android Keystore.
- Composer quick switches independently enable web search, phone files, local Python, hosted Python, URL Context, image generation and all custom tools.
- The main agent exposes a DRAW tool that calls Gemini 3.1 Flash Image as a separate service. Generated PNGs are saved in the app workspace and displayed inline. The direct image model can also be selected in the Model Atlas; direct image mode omits tool payloads because the model does not support function calling.
- Phone files are accessed through a user-selected Storage Access Framework folder. Paths are scoped, traversal is rejected, and text read/write operations have size limits.
- Reading file contents sent to Gemini, modifying files, running local Python and generating images require permission. Users can approve once or for the active chat thread.
- Bundled CPython 3.13 via Chaquopy exposes standard Python built-ins, supported standard-library modules, network and an app-private working directory. It runs under Android's app UID; it is not an unrestricted OS-level Termux shell.
- Python-created or modified workspace files are surfaced as in-chat artifacts and text/code files can be opened and edited in the in-app editor.

### Rich Agent Interface
- Assistant responses render headings, bold/italic text, lists, tables, quotes, fenced code with copy, Markdown links, images and simple process-map diagrams.
- A compact colored Plan & Work Log rail sits above the assistant answer; expand it to inspect actual tool calls, code and results. Private model thought steps are not exposed.
- Token usage is a small circular control with a detailed ledger on tap.
- Generated images/audio/video/documents and saved files are surfaced inline where supported.
- Model / Tools opens practical quick controls; Explore Models opens a full-screen model atlas with API metadata, descriptions, context limits, tags and artwork.
- The composer and model controls use translucent glass surfaces and animated content transitions.

### Workflow Studio
- Create, edit, reorder, save, and delete workflows with up to 20 ordered steps.
- Three explicit local actions: create a note, append to a note by exact title, and search the on-device document index.
- Runs require a manual confirmation. Each run records a persistent status and per-step execution log.
- A failed step stops the run. Deleting a workflow removes its steps and run history but preserves notes created by previous runs.
- Workflow execution is local-only: no Gemini request, network calls, shell access, arbitrary scripts, app launching, scheduled triggers, or background execution.

### Verification
- Unit tests cover conversation context, session naming, workspace-name normalization (including Persian text), document chunk boundaries, PDF citations, stable text sections, keyword ranking, workflow plan validation, and Room workspace-isolation queries/mutations under Robolectric.

## In progress / not yet available
- Office document extraction, image OCR, scanned-PDF OCR, document summaries, flashcards, quizzes, semantic embeddings, and knowledge graph.
- Scheduled, event-based, and background workflow triggers. Workflow runs are manual only.
- Additional workflow actions such as export, file operations, notifications, and external integrations.
- Audio recording, transcription, searchable archive, and task/decision extraction.
- Arbitrary OS-level shell/Termux access, app launching, UI automation, scheduled triggers, and background agents are not implemented. The Agent can use the scoped tools described above, subject to user-selected capabilities and per-session/action approval.

## Privacy and data handling
- The API key is stored locally as AES-GCM ciphertext; its non-exportable encryption key is managed by Android Keystore.
- The key is sent over HTTPS only to Google's Gemini API when a user invokes validation, model discovery, or generation.
- Agent threads, workflows, notes, run history, and document data are stored in the app-private Room database; they are not uploaded automatically.
- Workflow actions run locally. A document-search step queries the on-device index; it does not send excerpts to Gemini.
- The Room database is app-private but is **not encrypted at the database layer**. Imported originals are also app-private files, not encrypted files. Do not store highly sensitive content until encrypted local-data storage is implemented.
- Library Q&A is opt-in: only the question and up to six retrieved excerpts are sent to the selected Gemini model after explicit confirmation.

## Document library scope and limits
- Supported imports: PDF with selectable text, UTF-8 plain text, and Markdown. Maximum file size is 20 MB; PDFs are limited to 400 pages; extracted text is capped at 1.5 million characters per file.
- PDFs with no selectable text are rejected; image-only pages in mixed PDFs are skipped. OCR, standalone images, DOC/DOCX, spreadsheets, and presentations are not supported yet.
- Retrieval uses on-device lexical keyword matching, not embeddings or semantic vector search. PDF answers cite original page numbers; text and Markdown answers cite indexed sections.
- User-facing thread deletion, workspace deletion, and full workspace export are not available yet. Uninstalling the app removes its app-private data.

## Database compatibility
Room schema v5 retains the existing agent, document, and workflow data while adding workspace ownership to workflows and local notes. The 4→5 migration assigns existing workflows and notes to the Personal workspace, replaces global note-title uniqueness with per-workspace uniqueness, and adds workspace-aware indexes. Earlier 3→4 compatibility remains idempotent across the two v3 layouts.
