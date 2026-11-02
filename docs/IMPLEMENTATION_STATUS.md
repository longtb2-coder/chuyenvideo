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
- Selected source is copied into the phone's shared `Movies/LONGKAKA SELLER AI` folder through MediaStore; the source file is not modified or deleted.
- Basic validation of MIME type / video extension and available storage. No arbitrary fixed file-size cap; import is limited by actual free phone storage and file-system constraints.
- Reads duration, width and height using Android `MediaMetadataRetriever`.
- Displays import progress, filename, duration, resolution and file size.
- HTTPS direct-file downloader for URLs whose HTTP response is an actual `video/*` stream.
- Network and local imports use on-device Android APIs; no paid API, account or remote backend is required for this intake path.

## Video intake — known limits
- Social-platform page URLs (TikTok, Facebook, Shopee, Douyin) are not universal direct video files. Login walls, anti-bot controls, expiring signatures and platform policies can prevent direct retrieval. The app reports non-video responses and provides the local-file fallback; it does not pretend every page URL can be downloaded.
- Imported files are visible in the phone's Files/Gallery under Movies/LONGKAKA SELLER AI. The app's persistent project index in Room, rename/delete controls, duplicate detection and restart recovery are still pending.
- Large files are accepted when sufficient free storage exists; import reserves a safety margin of free space.
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
