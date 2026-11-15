# ORACLE STUDIO

**Your intelligence workspace.** A native Android application combining a Gemini operator and local document intelligence in a focused, chat-first technical workbench. Its visual language pairs Codex-like tool clarity with Linear-level typographic restraint and interaction polish. Automation and audio-memory modules remain planned rather than simulated.

## Product principles
- Native Android, offline-first local workspace, Gemini API key supplied by the user.
- API credentials are encrypted at rest with AES-GCM; the encryption key is held by Android Keystore.
- Planned agent actions must be transparent, permission-gated, auditable, and explicitly confirmed before external or destructive effects.
- Source-grounded document answers; future automation runs and audio recordings are intended to remain local and inspectable.
- Original ORACLE identity: graphite surfaces, warm oxide accents, editorial typography, precise spacing, and progressive disclosure. Codex and Linear are quality references, never templates to copy.

## Build
Open this repository in Android Studio or run:

```bash
gradle --no-daemon testDebugUnitTest assembleDebug
```

Use JDK 17 and Android SDK 36. The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions runs the unit tests and builds/uploads the debug APK on every push to `main`.

## Implemented
- Native Jetpack Compose workspace with a handcrafted Codex × Linear-inspired chat workbench, persistent thread drawer, compact navigation, and expandable model controls beneath the composer.
- Document Library is reachable directly from the chat toolbar and returns to the agent with Android back navigation.
- Gemini API key validation and AES-GCM encryption backed by Android Keystore. No API key is embedded in source or APK.
- Live Gemini Agent console using `generateContent`, with visible request status and API/network errors.
- Persistent agent threads and message history in Room; thread titles are generated locally from the first user prompt.
- First-class local workspaces with a Personal default, create/switch controls in the conversation drawer, and DataStore-backed active-workspace selection.
- Agent threads and imported documents are scoped to the active workspace; a Room 2→3 migration assigns existing content to Personal without losing history.
- Selected Gemini model stored in DataStore. The Agent can refresh the models exposed to the connected key using Google's models endpoint and choose a model that supports `generateContent`.
- Cancellable OkHttp requests: an explicit **Cancel** control cancels the in-flight network call. Failed/cancelled prompts remain in the local thread and retry without duplicating the saved user turn.
- Bounded conversation context (last 12 messages) with a send-point disclosure in the chat composer; Library Q&A displays the exact retrieved excerpts and requires confirmation before sending them.
- Unit tests for conversation context, session naming, workspace-name normalization (including Persian text), document chunk boundaries, PDF page citations, stable text sections, and English/Persian keyword ranking.
- Document library imports selectable PDF, TXT, and Markdown files from Android's document picker; originals are copied into app-private storage.
- PDF text extraction preserves page numbers. TXT/Markdown are segmented into stable section citations; extracted text is chunked and indexed in Room.
- Offline keyword retrieval across imported passages, document reader, and a Gemini library Q&A flow that shows the exact matched source passages before sending them.

## In progress / not yet available
- Office document extraction, image OCR, scanned-PDF OCR, document summaries, flashcards, quizzes, semantic embeddings, and knowledge graph.
- Workspace rename and deletion controls, workspace-level export, and project-specific settings are not available yet.
- Visual workflow authoring, triggers, local action execution, approval gates, and run history.
- Audio recording, transcription, searchable archive, and task/decision extraction.
- Agent tool execution. The current Agent is a Gemini chat/planning interface; it does not silently access files, apps, or perform device actions.

These planned modules are not presented as working features in the app.

## Privacy and data handling
- The API key is stored locally as AES-GCM ciphertext; its non-exportable encryption key is managed by Android Keystore.
- The key is sent in an HTTPS header only to Google's Gemini API when a user invokes validation, model discovery, or generation.
- Agent threads and messages are stored in the app-private Room database. They are not uploaded automatically. They are included in a Gemini request only when the user submits a prompt.
- The Room database (agent conversations, document metadata, and extracted document text) is app-private but is **not encrypted at the database layer**. Imported originals are also app-private files, not encrypted files. Do not store highly sensitive content until encrypted local-data storage is implemented.
- Microphone permission is not requested because audio recording and transcription are not implemented yet.
- Library Q&A is opt-in: tapping **ASK GEMINI** first displays the matched passages and asks for confirmation. Only the question and up to six retrieved excerpts are sent to the selected Gemini model; the original file and remaining library are not uploaded.

## Document library scope and limits
- Supported imports: PDF with selectable text, UTF-8 plain text, and Markdown. Maximum file size is 20 MB; PDFs are limited to 400 pages; extracted text is capped at 1.5 million characters per file.
- PDFs with no selectable text are rejected; image-only pages in mixed PDFs are skipped. OCR, standalone images, DOC/DOCX, spreadsheets, and presentations are not supported yet.
- The reader displays extracted text and citation labels; it is not yet a rendered PDF page viewer.
- Retrieval currently uses on-device lexical keyword matching, not embeddings or semantic vector search. PDF answers cite original page numbers; text and Markdown answers cite indexed sections.
- User-facing thread deletion, workspace deletion, and full workspace export are not available yet. Uninstalling the app removes its app-private data.
