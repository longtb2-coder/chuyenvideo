# LONGKAKA AI PLATFORM — Roadmap

## Phase 0 — Foundation and decisions (current)

- [x] Confirm `chuyenvideo` as the public source repository.
- [x] Inspect baseline repository and default branch.
- [x] Create an isolated architecture branch.
- [ ] Agree on module boundaries and app identity.
- [ ] Decide Android minimum SDK after device/library compatibility review.
- [ ] Establish contribution, licensing, and secret-handling rules.

**Exit criteria:** architecture is agreed; no APK is produced.

## Phase 1 — Android shell and design system

- Create Gradle Kotlin DSL project and version catalog.
- Create native Compose application shell.
- Implement the selected blue AI visual system and single-screen Seller AI dashboard.
- Add navigation/state model, accessibility sizing, dark theme, and responsive layouts.
- Add a placeholder project workspace without AI processing.

**Exit criteria:** app shell compiles and launches; no video pipeline is claimed complete.

## Phase 2 — Reliable local media foundation

- Import local video through Android Storage Access Framework.
- Probe media metadata and validate supported codecs/dimensions/duration.
- Persist project/job state in Room.
- Copy or reference media safely in app-private storage.
- Implement resumable, cancellable background job orchestration.
- Add progress, notifications, failure details, and cleanup of temporary files.

**Exit criteria:** import and project recovery work across app restarts and device rotation; long-running work is tested on a real Android device.

## Phase 3 — Seller AI processing modules

Implement independently behind contracts:
1. OCR and editable text-region detection.
2. Authorized overlay cleanup (blur first; inpainting adapter where available).
3. Speech transcription and source-language detection.
4. Chinese-to-Vietnamese translation and ad-copy rewrite.
5. Vietnamese voiceover provider adapters.
6. Subtitle timing and editing.
7. FFmpeg composition/export presets.

**Exit criteria:** each stage has test fixtures, capability reporting, and graceful fallback.

## Phase 4 — Optional backend

- Evaluate actual free tiers and terms before choosing a host/provider.
- Deploy only stateless inference/API services that provide clear value.
- Add upload limits, queue limits, retention policy, authentication, and cost/quota visibility.
- Keep local import/export functional if the backend is unavailable.

**Exit criteria:** cloud processing is opt-in and replaceable; no secret is embedded in the app.

## Phase 5 — Release and safe updates

- Establish a stable application ID and protect the signing key offline.
- Add CI builds and automated tests.
- Publish signed releases and version metadata.
- Add update checks, compatibility rules, changelog, and rollback strategy for remote configuration.
- Test in-place upgrade and project/database preservation.

**Exit criteria:** upgrade from a previous signed build succeeds without deleting user projects.

## Future applications

New apps should reuse core modules and the design system but own their app-specific workflow and branding. Add an app only when there is a concrete use case; do not force every feature into one oversized application.
