# Implementation status — foundation and video intake

This branch contains an Android source foundation and the first functional video-intake slice. It is not yet a complete seller-video editor.

## Android shell
- Kotlin + Jetpack Compose application module.
- Application ID: `ai.longkaka.seller`.
- Min SDK 26; compile/target SDK 36.
- Java 17 toolchain target.
- GitHub Actions now runs `:app:assembleDebug` and uploads a debug APK artifact when successful.

## Video intake — implemented in source
- Android Storage Access Framework picker for user-selected video files.
- Selected source is copied into app-private `files/managed_media`; the source file is not modified or deleted.
- Basic validation of MIME type / video extension, file size cap (2 GB), and available storage.
- Reads duration, width and height using Android `MediaMetadataRetriever`.
- Displays import progress, filename, duration, resolution and file size.
- HTTPS direct-file downloader for URLs whose HTTP response is an actual `video/*` stream.
- Network and local imports use on-device Android APIs; no paid API, account or remote backend is required for this intake path.

## Video intake — known limits
- Social-platform page URLs (TikTok, Facebook, Shopee, Douyin) are not universal direct video files. Login walls, anti-bot controls, expiring signatures and platform policies can prevent direct retrieval. The app reports non-video responses and provides the local-file fallback; it does not pretend every page URL can be downloaded.
- Current import storage is app-private and is not yet indexed in Room. Project library, rename/delete controls, duplicate detection and restart recovery are still pending.
- The 2 GB cap is a safety limit for the first intake implementation and can be tuned after testing on real devices.
- The imported file remains in app storage until a future project/file-management screen provides explicit deletion controls.

## Not implemented yet
- Room database and persistent project/job library.
- Foreground long-running worker, notification, cancellation and retry/recovery.
- Media probing beyond basic metadata, thumbnails and codec compatibility warnings.
- OCR, ASR, translation, text cleanup/inpainting, Vietnamese TTS, subtitle editor and video renderer/export.
- Production signing, release APK and in-device acceptance tests.
- Approved icon PNG binary and adaptive launcher assets.

## Free-first policy
- Local import and direct HTTPS file intake are free and run without paid services.
- AI capabilities must be selected only after checking license, device requirements, privacy terms and real free quotas. No unlimited-free cloud service is promised.
- Do not embed paid-provider API keys in the APK or public repository.

## Verification
- Earlier workflow run `41427093609` passed Kotlin compilation only; it did not package an APK.
- The updated workflow now assembles a debug APK and uploads it as `longkaka-seller-ai-debug`. The new run must finish successfully before claiming the APK is available.
