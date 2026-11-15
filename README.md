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

Use JDK 17, Android SDK 36, and Python 3.13 for the embedded Chaquopy runtime. The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions runs unit tests, builds the debug APK, verifies alignment/signature, and uploads the artifact.

## Implemented

### Gemini chat
- Chat-first Jetpack Compose workbench with animated navigation, thread drawer, message history, and expandable model controls.
- Gemini API key validation and AES-GCM encryption backed by Android Keystore. No API key is embedded in source or APK.
- Live Gemini Agent using Gemini `generateContent`, dynamic model discovery, visible request errors, and persistent per-thread model selection.
- Bounded multi-step agent loop (up to eight local function-call rounds): Gemini can request a tool, receive its result, and continue reasoning before returning the final answer.
- Built-in Google Search grounding, URL Context, and Google-managed Python execution can be enabled from the composer tool panel.
- Gemini function-call parts, response parts, IDs, and thought signatures are retained with the assistant turn so later messages can continue with the required tool context.
- Tool activity is surfaced in the chat; on-device Python code and stdout/stderr are rendered in separate code/result blocks.
- Persistent agent threads and messages in Room; thread titles are generated locally from the first user prompt.
- Cancellable OkHttp requests. Failed/cancelled prompts remain available for retry without duplicating the saved user turn.
- Configurable conversation history (12, 20, 32, or 48 messages), thinking depth, and output-token budget from the composer. The selected model's published input/output token limits are shown when Gemini returns them.

### Agent tools and Python
- Google Search and URL Context are managed by Gemini; Google can search the web and retrieve user-supplied web pages when the model chooses to use them.
- Google-managed code execution lets Gemini generate and run Python on Google's side. This is separate from the embedded local interpreter.
- Chaquopy bundles Python 3.13 in the APK for on-device execution. The local runner accepts bounded scripts, displays generated code and stdout/stderr in the conversation, and exposes an allowlist of common standard-library modules.
- The local Python environment is intentionally not a Termux replacement: it does not expose a general shell, package installation, subprocess, arbitrary filesystem access, or network access. Script size, AST complexity, and cooperative execution time are bounded.
- Phone access uses Android's Storage Access Framework. The user chooses one folder, can change or revoke that grant, and ORACLE can list/read text files only within that folder. File creation is limited to that folder's root and always opens an approval dialog before writing.
- Gemini 3 models can combine Google's managed tools with ORACLE's custom local tools. For older model families, the composer explains that local tools and built-in Google tools cannot be combined in a single request; turn off the built-in tools or choose an available Gemini 3 model.
- Tool permissions default to user-controlled settings. No broad storage permission is requested, and no phone-file tool is exposed until a folder grant is stored.

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
- Workflow execution is local-only: no Gemini request, network calls, shell access, arbitrary scripts, app launching, scheduled triggers, or background execution.

### Verification
- Unit tests cover conversation context, session naming, workspace-name normalization (including Persian text), document chunk boundaries, PDF citations, stable text sections, keyword ranking, and workflow plan validation.

## In progress / not yet available
- Office document extraction, image OCR, scanned-PDF OCR, document summaries, flashcards, quizzes, semantic embeddings, and knowledge graph.
- Scheduled, event-based, and background workflow triggers. Workflow runs are manual only.
- Additional workflow actions such as export, file operations, notifications, and external integrations.
- Audio recording, transcription, searchable archive, and task/decision extraction.
- General Android UI automation, arbitrary app launching, background agents, and unrestricted shell execution. The available tools are limited to the documented Gemini-managed tools and the permission-scoped local tools above.

## Privacy and data handling
- The API key is stored locally as AES-GCM ciphertext; its non-exportable encryption key is managed by Android Keystore.
- The key is sent over HTTPS only to Google's Gemini API when a user invokes validation, model discovery, or generation.
- Agent threads (including tool-call context), workflows, notes, run history, and document data are stored in the app-private Room database; they are not uploaded automatically. Tool outputs are sent back to Gemini only when a Gemini tool call is part of the active conversation.
- Workflow actions run locally. A document-search step queries the on-device index; it does not send excerpts to Gemini. Local Python is also executed on-device. Google Search, URL Context, and Google Python execute through Google's Gemini services when enabled.
- Phone-file access is scoped to a user-selected Storage Access Framework folder. New files cannot be created without a visible in-app approval.
- The Room database is app-private but is **not encrypted at the database layer**. Imported originals are also app-private files, not encrypted files. Do not store highly sensitive content until encrypted local-data storage is implemented.
- Library Q&A is opt-in: only the question and up to six retrieved excerpts are sent to the selected Gemini model after explicit confirmation.

## Document library scope and limits
- Supported imports: PDF with selectable text, UTF-8 plain text, and Markdown. Maximum file size is 20 MB; PDFs are limited to 400 pages; extracted text is capped at 1.5 million characters per file.
- PDFs with no selectable text are rejected; image-only pages in mixed PDFs are skipped. OCR, standalone images, DOC/DOCX, spreadsheets, and presentations are not supported yet.
- Retrieval uses on-device lexical keyword matching, not embeddings or semantic vector search. PDF answers cite original page numbers; text and Markdown answers cite indexed sections.
- User-facing thread deletion, workspace deletion, and full workspace export are not available yet. Uninstalling the app removes its app-private data.

## Database compatibility
Room schema v4 retains both the workspace and workflow data models. Its 3→4 migration is idempotent across the two earlier v3 layouts: it adds any missing workspace columns and creates workflow tables only when they do not already exist.
