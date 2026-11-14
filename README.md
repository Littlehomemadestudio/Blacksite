# ORACLE STUDIO

**Your intelligence workspace.** A native Android application combining a Gemini operator, document intelligence, visual automations, and searchable audio memory in an original Cursor-inspired workbench.

## Product principles
- Native Android, offline-first local workspace, Gemini API key supplied by the user.
- API credentials are encrypted at rest with AES-GCM; the encryption key is held by Android Keystore.
- Transparent, permission-gated agent tools with run history and explicit confirmation for destructive or external actions.
- Source-grounded document answers, searchable recordings, and inspectable automation runs.
- Original ORACLE identity and visual system; Cursor is a design-quality reference, not an asset or UI source.

## Build
Open this repository in Android Studio or run:

```bash
gradle --no-daemon testDebugUnitTest assembleDebug
```

Use JDK 17 and Android SDK 36. The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions runs the unit tests and builds/uploads the debug APK on every push to `main`.

## Implemented
- Native Compose dark workbench shell with navigation, workspace header, module surfaces, and status bar.
- Gemini API key validation and AES-GCM encryption backed by Android Keystore. No API key is embedded in source or APK.
- Live Gemini Agent console using `generateContent`, with visible request status and API/network errors.
- Persistent agent threads and message history in Room; thread titles are generated locally from the first user prompt.
- Selected Gemini model stored in DataStore. The Agent can refresh the models exposed to the connected key using Google's models endpoint and choose a model that supports `generateContent`.
- Cancellable OkHttp requests: pressing **CANCEL** cancels the in-flight network call. Failed/cancelled prompts remain in the local thread and can be retried.
- Bounded conversation context (last 12 messages) and explicit disclosure of what conversation text is sent to Gemini.
- Unit tests for conversation context bounds and local thread-title normalization.

## In progress / not yet available
- Document picker, PDF/office extraction, OCR, indexing, reader, citations, summaries, flashcards, quizzes, and knowledge graph.
- Multi-project workspace management beyond persistent Agent threads.
- Visual workflow authoring, triggers, local action execution, approval gates, and run history.
- Audio recording, transcription, searchable archive, and task/decision extraction.
- Agent tool execution. The current Agent is a Gemini chat/planning interface; it does not silently access files, apps, or perform device actions.

These planned modules are not presented as working features in the app.

## Privacy and data handling
- The API key is stored locally as AES-GCM ciphertext; its non-exportable encryption key is managed by Android Keystore.
- The key is sent in an HTTPS header only to Google's Gemini API when a user invokes validation, model discovery, or generation.
- Agent threads and messages are stored in the app-private Room database. They are not uploaded automatically. They are included in a Gemini request only when the user submits a prompt.
- The Room conversation database is currently app-private but is **not encrypted at the database layer**. Do not store highly sensitive content until encrypted local-data storage is implemented.
- Clearing a thread removes its messages locally. Uninstalling the app removes its app-private data.
