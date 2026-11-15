# ORACLE STUDIO

**Your intelligence workspace.** A native Android application combining a Gemini operator, local document intelligence, and a manually triggered workflow runner in an original Windows XP Luna-inspired shell.

## Product principles
- Native Android, offline-first local workspace, Gemini API key supplied by the user.
- API credentials are encrypted at rest with AES-GCM; the encryption key is held by Android Keystore.
- Agent actions are transparent, permission-gated, auditable, and explicitly confirmed before external or destructive effects.
- Source-grounded document answers; workflow runs are local and inspectable.
- Original ORACLE identity and visual system; Cursor is a design-quality reference, not an asset or UI source.

## Build
Open this repository in Android Studio or run:

```bash
gradle --no-daemon testDebugUnitTest assembleDebug
```

Use JDK 17 and Android SDK 36. The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions runs unit tests, builds and verifies the debug APK, then uploads it on every push to `main`.

## Implemented
- Native Jetpack Compose workspace with a handcrafted Luna-style title/menu treatment, chat-first layout, session drawer, and responsive classic controls.
- Document Library and Workflow Studio are reachable from the chat toolbar and navigation drawer.
- Gemini API key validation and AES-GCM encryption backed by Android Keystore. No API key is embedded in source or APK.
- Live Gemini Agent console using `generateContent`, with visible request status and API/network errors.
- Persistent agent threads and message history in Room; thread titles are generated locally from the first user prompt.
- Selected Gemini model stored in DataStore. The Agent can refresh the models exposed to the connected key using Google's models endpoint and choose a model that supports `generateContent`.
- Cancellable OkHttp requests: an explicit **Cancel** control cancels the in-flight network call. Failed/cancelled prompts remain in the local thread and retry without duplicating the saved user turn.
- Bounded conversation context (last 12 messages) with a send-point disclosure in the chat composer; Library Q&A displays the exact retrieved excerpts and requires confirmation before sending them.
- Document library imports selectable PDF, TXT, and Markdown files from Android's document picker; originals are copied into app-private storage.
- PDF text extraction preserves page numbers. TXT/Markdown are segmented into stable section citations; extracted text is chunked and indexed in Room.
- Offline keyword retrieval across imported passages, document reader, and a Gemini library Q&A flow that shows the exact matched source passages before sending them.
- **Workflow Studio:** create, edit, reorder, save, and delete workflows with up to 20 ordered steps.
- **Local runner:** three explicit actions are implemented: create a local note, append to a local note by exact title, and search the on-device document index. Each run is started manually after a confirmation dialog.
- **Execution history:** Room persists run status and per-step logs. A failed step stops the run; deleting a workflow explicitly confirms removal of its steps and run history while preserving notes created by previous runs.
- Workflow execution has no arbitrary scripting, shell access, app launching, network calls, background triggers, or automatic Gemini requests.
- Room migration 2→3 adds workflow definitions, steps, local notes, run history, and logs without replacing existing agent/document tables.
- Unit tests cover conversation context, session naming, document chunk boundaries, PDF page citations, stable text sections, English/Persian keyword ranking, and workflow plan validation.

## In progress / not yet available
- Office document extraction, image OCR, scanned-PDF OCR, document summaries, flashcards, quizzes, semantic embeddings, and knowledge graph.
- Multi-project workspace management beyond persistent Agent threads.
- Scheduled, event-based, and background workflow triggers; only manual runs are available.
- Additional workflow actions such as export, file operations, notifications, or integrations; only the three documented local actions are executable.
- Audio recording, transcription, searchable archive, and task/decision extraction.
- Agent tool execution. The current Agent is a Gemini chat/planning interface; it does not silently access files, apps, or perform device actions.

Unavailable features are not presented as working controls in the app.

## Privacy and data handling
- The API key is stored locally as AES-GCM ciphertext; its non-exportable encryption key is managed by Android Keystore.
- The key is sent in an HTTPS header only to Google's Gemini API when a user invokes validation, model discovery, or generation.
- Agent threads, workflow definitions, local notes, run history, and document data are stored in the app-private Room database. They are not uploaded automatically.
- The Room database (agent conversations, workflow definitions, notes, run logs, document metadata, and extracted document text) is app-private but is **not encrypted at the database layer**. Imported originals are also app-private files, not encrypted files. Do not store highly sensitive content until encrypted local-data storage is implemented.
- Workflow runs are local-only. A manual confirmation explains the ordered actions before execution. Document search steps query the local index; they do not send excerpts to Gemini.
- Microphone permission is not requested because audio recording and transcription are not implemented yet.
- Library Q&A is opt-in: tapping **ASK GEMINI** first displays the matched passages and asks for confirmation. Only the question and up to six retrieved excerpts are sent to the selected Gemini model; the original file and remaining library are not uploaded.

## Document library scope and limits
- Supported imports: PDF with selectable text, UTF-8 plain text, and Markdown. Maximum file size is 20 MB; PDFs are limited to 400 pages; extracted text is capped at 1.5 million characters per file.
- PDFs with no selectable text are rejected; image-only pages in mixed PDFs are skipped. OCR, standalone images, DOC/DOCX, spreadsheets, and presentations are not supported yet.
- The reader displays extracted text and citation labels; it is not yet a rendered PDF page viewer.
- Retrieval currently uses on-device lexical keyword matching, not embeddings or semantic vector search. PDF answers cite original page numbers; text and Markdown answers cite indexed sections.
- User-facing thread deletion and full workspace export are not available yet. Uninstalling the app removes its app-private data.
