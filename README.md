# ORACLE STUDIO

**Your intelligence workspace.** A native Android application combining an agentic Gemini operator, document intelligence, visual automations, and searchable audio memory in a Cursor-inspired workbench.

## Product principles
- Native Android, offline-first local workspace, Gemini API key supplied by the user.
- Secure local secret storage; never commit, log, or transmit keys anywhere except Google's Gemini API.
- Transparent, permission-gated agent tools with run history and explicit confirmation for destructive/external actions.
- Source-grounded document answers, searchable recordings, and inspectable automation runs.
- Original ORACLE identity and visual system; Cursor is a design-quality reference, not an asset/UI source.

## Build
Open this repository in Android Studio or run `gradle assembleDebug` with JDK 17 and Android SDK 36 installed. The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

## Implemented
- Cursor-inspired native Compose workspace shell with navigation and status surfaces.
- Gemini API key validation and AES-GCM encryption backed by Android Keystore.
- Live Gemini chat in the Agent console using `generateContent` (default `gemini-2.5-flash`), bounded conversation context, request state, visible failures, prompt restoration for retry, and session clearing.
- The agent explicitly states its actual capabilities and does not claim to execute device actions. No hidden device tools are exposed.

## In progress
Document indexing, source citations, persistent workspaces, workflow execution, audio capture/transcription, and richer agent tool permissions remain under development. Planned modules are not represented as completed features.

## Privacy
API keys remain local and encrypted at rest. Audio recording requires explicit user action and Android microphone permission. Documents and recordings are not uploaded unless the user explicitly invokes a Gemini-powered action.
