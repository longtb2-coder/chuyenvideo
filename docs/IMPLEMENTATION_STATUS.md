# Implementation status — foundation build

This commit establishes the first Android source skeleton, not a production-ready video editor.

## Step 1 — Android shell
- Kotlin + Jetpack Compose application module.
- Application ID: `ai.longkaka.seller`.
- Min SDK 26; compile/target SDK 36.
- Java 17 toolchain target.

## Step 2 — UI shell
- One-screen dark navy / cyan / gold Seller AI dashboard.
- Local video picker through Android's document picker.
- URL field is informational until per-platform import adapters are implemented.

## Step 3 — Media foundation
- Typed media job and pipeline stage model.
- Provider-neutral processor interface.
- No FFmpeg renderer is connected yet.

## Step 4 — Background foundation
- WorkManager worker boundary exists.
- Worker deliberately does not report success until a real processor is connected.
- Foreground execution, persistent job database, notification channel, cancellation and recovery tests remain to be implemented before long jobs are enabled.

## Step 5 — AI integration boundaries
- AI work is represented by replaceable processor contracts.
- OCR, speech recognition, translation, inpainting, TTS and subtitle timing are not yet implemented.
- Provider selection must be based on tested quality, licensing, device support, actual free quotas and privacy terms.

## Icon
The approved icon is documented in `docs/BRAND_AND_ICON.md`. The original PNG binary still needs to be added to the repository and converted into adaptive launcher assets. This text-only GitHub integration cannot upload the attached binary directly.

## Build and verification
No APK has been assembled or published. A Gradle wrapper and CI compile verification are still needed before calling this foundation buildable.
