# LONGKAKA AI PLATFORM — Architecture

## Purpose

A reusable Android-first platform for a family of media-production apps. The first product is **LONGKAKA SELLER AI**. Future products may reuse the platform core without copying or rewriting media, storage, background-work, and update infrastructure.

This repository is the public source repository. Do not commit credentials, signing keys, private user media, API tokens, or production secrets.

## Current repository baseline

- Default branch: `main`
- The repository currently contains only the original README.
- No Android source code, Gradle project, CI workflow, release assets, or app signing configuration exists yet.
- Work is being prepared on `foundation/architecture`; no APK is being built in this phase.

## Architecture decision

Start with a **modular monolith Android application**, not microservices and not a plugin system that downloads executable code.

- Native Android: Kotlin, Jetpack Compose, Material 3.
- Build: Gradle Kotlin DSL, version catalog, convention plugins when useful.
- Dependency injection: Hilt (or a small manual graph until complexity justifies Hilt).
- Local persistence: Room for project/job metadata; app-private files for working media; Storage Access Framework for user-selected import/export.
- Background work: WorkManager for deferrable/retryable stages; a foreground service only for user-visible long-running media work where Android policy requires it.
- Media pipeline: FFmpeg integration behind a platform interface, with codec/device capability checks.
- Networking: Retrofit/OkHttp behind repository interfaces; explicit timeouts, retries, cancellation, and offline handling.
- Tests: unit tests for pipeline orchestration and migrations; instrumentation tests for import/export and background behavior.

## Proposed repository layout

```text
.
├── app/
│   └── seller/                 # First Android application shell
├── core/
│   ├── common/                 # Result, errors, dispatchers, logging
│   ├── model/                  # Shared domain models
│   ├── data/                   # Room, repositories, file references
│   ├── media/                  # Media probing, FFmpeg facade, codecs
│   ├── ai-contracts/           # Provider-neutral AI interfaces
│   ├── network/                # HTTP client and connectivity
│   ├── background/             # WorkManager/service orchestration
│   ├── design-system/          # Colors, typography, reusable Compose UI
│   └── updates/                # Version metadata and release checks
├── feature/
│   ├── import-media/
│   ├── text-cleanup/
│   ├── speech-transcription/
│   ├── translation/
│   ├── voiceover/
│   ├── subtitle-editor/
│   ├── video-editor/
│   ├── export/
│   ├── projects/
│   └── settings/
├── services/
│   └── api-contracts/          # Backend API schemas; no secrets
├── backend/
│   └── README.md               # Optional deployable services, added only when needed
├── config/
│   ├── app-defaults/
│   └── update-channel/
├── docs/
│   ├── ARCHITECTURE.md
│   ├── ROADMAP.md
│   └── RELEASES_AND_UPDATES.md
├── gradle/
│   └── libs.versions.toml
├── build-logic/
├── .github/workflows/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── .gitignore
└── README.md
```

The tree is a target layout, not a claim that these files already exist. Add modules only when their first implementation is ready; avoid empty modules that create build overhead.

## Dependency direction

- `app/seller` assembles feature modules.
- Feature modules depend on core contracts and design-system, not on one another wherever practical.
- Core modules must not depend on app or feature modules.
- AI implementations are adapters behind interfaces in `core/ai-contracts`.
- Backend providers are replaceable adapters. A feature must have a local/fallback path where feasible.
- Shared platform code stays generic; seller-specific copy, workflows, and defaults stay under the seller app/features.

## AI processing strategy

Use a provider-neutral pipeline with explicit capability levels:

1. On-device processing for lightweight/private operations where performance is acceptable.
2. Optional remote inference through a backend adapter for compute-heavy OCR, speech, translation, or video inpainting.
3. A manual/import fallback when a platform blocks URL extraction or a model/service is unavailable.

Do not promise that a third-party free server is permanent or unlimited. Keep provider endpoints configurable, apply quotas and file-size limits, and never embed private API keys in the public APK or repository. A free tier may change or disappear; the app must report that clearly and allow another provider or local fallback.

## Update strategy

Separate updates into three categories:

1. **Remote configuration/content**: prompts, feature flags, limits, and non-executable templates can be fetched with schema/version validation and rollback.
2. **Backend services/models**: deploy independently; clients use versioned API contracts and capability negotiation.
3. **Android application code/native libraries**: distribute a signed APK/AAB release. To update over an existing installation, preserve the same application ID and signing certificate. Android will show its normal installation confirmation unless an eligible official in-app update flow is used.

Never download and execute arbitrary DEX, JAR, native libraries, or scripts as a substitute for app releases. Keep user projects in app data and use Room/file migrations so compatible updates preserve projects. Test upgrades from the previous released version before publishing.

## Privacy, rights, and safety

- Ask the user to confirm they have permission to download, edit, and reuse source videos.
- Respect platform access controls; URL import must fall back to selecting a video saved on the phone.
- Do not remove creator watermarks or rights-management marks as a way to misrepresent ownership. Text cleanup is intended for editable overlays/captions the user is authorized to modify.
- Disclose cloud processing and obtain consent before uploading source media.
- Provide deletion controls for source files, temporary files, transcripts, and exported files.
- Avoid sending product/customer data to third-party services unless needed and consented.

## Non-goals for the foundation phase

- No APK build or release.
- No paid AI provider commitment.
- No guaranteed unlimited free cloud compute.
- No automatic downloading of executable feature code.
- No migration or deletion of the separate LongVideo AI repository.
