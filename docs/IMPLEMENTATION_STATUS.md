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
- Imported files are visible in the phone's Files/Gallery under Movies/LONGKAKA SELLER AI. The local SQLite video-library index now stores metadata and content URIs, and the dashboard reloads the list after app restart. Rename, safe file deletion, duplicate warnings, thumbnails and missing-file reconciliation remain pending.
- Large files are accepted when sufficient free storage exists; import reserves a safety margin of free space.
- The imported file remains in app storage until a future project/file-management screen provides explicit deletion controls.

## Video library — implemented in source
- SQLite stores metadata only; video bytes remain in shared phone storage.
- Dashboard reloads saved entries on launch and lets the user select a previously imported video.
- Removing a library entry is deliberately separate from deleting the actual media file. No delete-file action is exposed yet.
- `contentUri` is explicitly a MediaStore `content://` URI, not a filesystem path; future media processing must consume it as a URI or copy it to a controlled working file.

## Sales-video visual storyboard handoff — implemented in source, not yet device-verified
- A dedicated sampler extracts up to 8 evenly distributed frames from the imported sales video.
- Frames are downscaled to a maximum edge of 1280 px and JPEG-compressed into app cache; the original video is never modified.
- The app shares the storyboard images plus the product brief/OCR context and a structured sales-rewrite prompt to the user's selected ChatGPT/Gemini app through Android's share chooser.
- The user can paste the returned sales script into the app; the accepted text continues into the existing VieNeu Nano narration workflow.
- The storyboard is cleared when a different video is selected so images from an older clip cannot be accidentally sent with a new project.
- FileProvider grants read-only temporary access to the generated cache images. No API key or automatic upload is embedded.

## Sales narration subtitles over the selected blur/cover region — implemented in source, not yet device-verified
- The exporter now accepts the same VieNeu Nano chunk timings that were used to generate the narration audio.
- During MP4 rendering, each caption is shown only during its generated speech interval.
- In Sales mode, the user-selected OCR/blur region is used as the subtitle placement area. Other recognized Chinese text regions can still be blurred/covered without displaying the new narration text on every region.
- If the selected OCR region is temporarily not visible, the caption can use its saved cover rectangle as a stable placement region.
- The Movie workflow is unchanged; this timed narration-caption path is enabled only for Sales mode.

### Current limitation of this slice
- This milestone sends sampled visual frames and recognized on-screen text. It does not yet transcribe the video's spoken audio, and it does not guarantee that every ChatGPT/Gemini app version accepts multiple image attachments from Android sharing.
- The user must explicitly choose the destination app in the share sheet. The returned script still requires user review before narration.
- Compile, APK assembly and real-device share-flow testing have not yet been run for this change.

## Not implemented yet
- Persistent local SQLite video library for imported-video metadata is implemented; full project/job persistence and migration tests remain pending.
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
