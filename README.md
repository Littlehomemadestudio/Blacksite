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

## Status
Initial project scaffold. Features are being implemented incrementally; see commits and GitHub Actions for verified progress. Do not treat planned modules as implemented until they appear in the app and pass validation.

## Privacy
API keys remain local and encrypted at rest. Audio recording requires explicit user action and Android microphone permission. Documents and recordings are not uploaded unless the user explicitly invokes a Gemini-powered action.
