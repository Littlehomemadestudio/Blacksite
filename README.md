# ORACLE STUDIO

**Your intelligence workspace.** A native Android application combining a Gemini chat operator, workspace-aware document intelligence, and a local workflow studio in a handcrafted Jetpack Compose interface.

## Product principles
- Native Android, offline-first local workspace, Gemini API key supplied by the user.
- API credentials are encrypted at rest with AES-GCM; the encryption key is held by Android Keystore.
- Agent actions are transparent, permission-gated, auditable, and explicitly confirmed before external or destructive effects.
- Original ORACLE identity: graphite surfaces, warm oxide accents, editorial typography, precise spacing, and progressive disclosure.

## Build
Open this repository in Android Studio or run:

```bash
gradle --no-daemon testDebugUnitTest assembleDebug
```

Use JDK 17 and Android SDK 36. The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. The agentic runtime bundles CPython 3.13 (arm64-v8a and x86_64 builds). GitHub Actions runs unit tests, builds the debug APK, verifies alignment/signature, and uploads the artifact.

## Implemented

### Gemini chat
- Chat-first Jetpack Compose workbench with animated navigation, thread drawer, message history, and expandable model controls.
- Gemini API key validation and AES-GCM encryption backed by Android Keystore. No API key is embedded in source or APK.
- Stateful Gemini Agent using the Interactions API, with model-directed Google Search, URL context, hosted Python execution, and custom Android tool calls. Tool requests are executed by the app and returned to Gemini in bounded multi-step loops.
- Model controls in the composer: thinking level and summaries, tool-choice mode, output-token limit, model-reported input/output limits, Google Search, URL context, hosted code execution, and local tools.
- A live context ledger reports input, output, thinking, cached, and tool-use token counts when the API returns usage metadata.
- The assistant response supports rich Markdown blocks: headings, inline emphasis, numbered/bulleted lists, tables, quotations, code blocks, clickable links, embedded remote images, and simple rendered process diagrams.
- The model may emit a single user-facing plan summary per tool run; a compact colored plan/work rail sits above the assistant answer. Expanding it reveals the user-facing execution summary and actual tool/code/result log; private model reasoning traces are not exposed.
- Context/token usage is a small circular control with a detailed breakdown on tap, not a large metadata card.
- Tool outputs and generated artifacts are rendered inline: Python source/output, generated image/audio/video/document results, and editable file cards.
- The model/tool composer uses a translucent glass-style surface and expands a quick-controls panel. Explore Models opens a full-screen model atlas with model metadata, limits, descriptions and artwork.
- The assistant can request inline text+image output from Gemini Flash Image models selected from the live model list; other models return their supported output modalities.
- Persistent agent threads and messages in Room; thread titles are generated locally from the first user prompt.
- Cancellable OkHttp requests. Failed/cancelled prompts remain available for retry without duplicating the saved user turn.
- Bounded conversation context (last 12 messages) with a send-point disclosure in the composer.

### Workspaces
- A Personal default workspace plus local create/switch controls in the conversation drawer.
- Active workspace selection persists in DataStore.
- Agent threads and imported documents are scoped to the active workspace.

### Document Library
- Imports PDF with selectable text, UTF-8 plain text, and Markdown from Android's document picker; originals are copied into app-private storage.
- PDF extraction preserves page numbers. TXT/Markdown are segmented into stable section citations; extracted text is chunked and indexed in Room.
- Offline keyword retrieval, document reader, and Gemini library Q&A.
- Library Q&A displays the exact retrieved excerpts and requires confirmation before sending them to Gemini.

### Workflow Studio
- Create, edit, reorder, save, and delete workflows with up to 20 ordered steps.
- Three explicit local actions: create a note, append to a note by exact title, and search the on-device document index.
- Runs require a manual confirmation. Each run records a persistent status and per-step execution log.
- A failed step stops the run. Deleting a workflow removes its steps and run history but preserves notes created by previous runs.
- Workflow execution remains local-only and manually confirmed. It is separate from the autonomous Gemini tool loop.


### Agentic tools and execution
- Tavily web search, Gemini URL Context, hosted Python, local Python and scoped phone-file tools can be enabled independently in the composer. Web search uses a separately encrypted Tavily API key; it does not depend on Gemini's Google Search grounding entitlement.
- The app executes custom function calls and returns results to Gemini, allowing the model to continue through multiple tool rounds. The loop has a 12-round safety cap.
- Phone file access uses Android's system folder picker and persisted Storage Access Framework permission. The agent can list and read files and create or append UTF-8 text files only beneath that granted folder. Paths are relative, traversal is rejected, reads and writes are capped at 10 MB per operation.
- Reading cloud-bound phone file contents, creating/appending files, and running local Python require an approval. The user can allow an action once or grant tool access for the active chat session. The folder grant can be changed through the composer controls.
- A bundled CPython 3.13 runtime is included through Chaquopy. Local Python execution is displayed in the conversation and can be approved once for the active chat session. Normal Python built-ins, the supported standard library, network access and the app-private Python workspace are available. Android still limits the interpreter to the app UID; this is not an OS-level Termux shell, and some platform-dependent Python modules and binary wheels are unavailable.
- Hosted Gemini code execution and local on-device Python are separate tools: the first runs in Google's hosted environment; the second runs on the device.
- The response UI displays an expandable user-facing plan summary and executed action log, not private hidden chain-of-thought.
- The Interactions API uses model-managed implicit context caching. The UI reports cached-token usage when returned by Google; explicit cache-object management is not available through this API.
- Model capability and context limits vary by model. Unsupported model/API combinations return a visible error instead of silently falling back to a tool-less chat.

### Verification
- Unit tests cover conversation context, session naming, workspace-name normalization (including Persian text), document chunk boundaries, PDF citations, stable text sections, keyword ranking, and workflow plan validation.

## In progress / not yet available
- Office document extraction, image OCR, scanned-PDF OCR, document summaries, flashcards, quizzes, semantic embeddings, and knowledge graph.
- Scheduled, event-based, and background workflow triggers. Workflow runs are manual only.
- Additional workflow actions such as export, file operations, notifications, and external integrations.
- Audio recording, transcription, searchable archive, and task/decision extraction.
- Arbitrary shell/Termux access, app launching, UI automation, scheduled triggers, and background agents are not implemented. The local Python runtime is a constrained embedded interpreter, not a general-purpose terminal.

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
Room schema v4 retains both the workspace and workflow data models. Its 3→4 migration is idempotent across the two earlier v3 layouts: it adds any missing workspace columns and creates workflow tables only when they do not already exist.
