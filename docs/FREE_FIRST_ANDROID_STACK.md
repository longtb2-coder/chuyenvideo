# Free-first Android implementation choices

Status: DESIGN DECISION — NOT IMPLEMENTED
Branch: foundation/architecture
APK builds: do not trigger while module design is in progress.

## Product decisions

The user has selected:
- Smart automatic Chinese text handling.
- Vietnamese voice-over AND Vietnamese subtitles.
- Free-first, on-device processing wherever practical.
- VieNeu-TTS v3 Nano as the preferred offline Vietnamese voice engine, because the user has downloaded and auditioned this voice package and prefers its sound.
- Do not build an APK until module design is agreed.

## 1. OCR

Preferred direction: PaddleOCR mobile model deployed on Android using ONNX Runtime or a compatible mobile runtime.

Responsibilities:
- Detect and recognize Simplified and Traditional Chinese text.
- Retain digits, Latin product codes, units, prices, and punctuation.
- Return bounding boxes, timestamps, and confidence.
- Sample frames adaptively; do not run OCR on every frame of a long video.
- Track repeated/moving overlays across frames.
- Classify likely overlay text separately from text printed on the physical product.
- Require review for low-confidence OCR.

Do not bundle every model in the APK if this causes a large download. Prefer a separately downloadable model package with version/checksum and one-time local storage.

## 2. Restoration and fallback

Preferred first implementation: OpenCV classical methods plus temporal frame reuse, not a large generative inpainting model.

Order:
1. Use tracked text masks.
2. If background is static/simple, try local inpainting/patch-based restoration.
3. If the same background is visible in nearby frames without the overlay, use temporal reconstruction/alignment where reliable.
4. Inspect adjacent frames for flicker, smearing, blur, and product-detail loss.
5. If quality is poor, stop restoration and use a Vietnamese text panel/subtitle in a safe area.
6. Never alter the imported original file.

Do not promise pixel-perfect recovery where the text has hidden underlying product details. Preserve printed labels on products. Treat platform attribution/watermarks separately and do not remove them by default.

## 3. Translation

Default: faithful, natural Vietnamese for product demonstration/sales language, not invented marketing claims.

- OCR text and ASR speech are separate inputs.
- Preserve model numbers, prices, units, dimensions, quantities, and brand names.
- Provide editable translation before TTS.
- A no-cost online translation route may be offered only if its current terms/limits permit it; do not promise unlimited free usage or embed unofficial endpoints as a guaranteed service.
- Offline translation is a later optional model download, subject to size, speed, and device memory.

## 4. TTS — VieNeu-TTS v3 Nano

Preferred engine: VieNeu-TTS v3 Nano, not cloud API, because the user prefers the Nano voice and wants a one-time model download followed by repeated offline use.

Implementation constraints:
- Android app is Kotlin; the official VieNeu SDK is Python. Do not assume the Python package can be directly embedded in the APK.
- Verify the Nano model files, preprocessing, phonemizer/G2P requirements, ONNX graph inputs/outputs, and audio decoder before implementing Android inference.
- Implement or port a Kotlin/JNI/ONNX Runtime Android inference adapter only after validating model compatibility and licensing.
- Download the model package once into app-managed storage; verify checksums/version; skip download if complete.
- Persist model availability across app sessions and app updates where Android storage rules allow.
- Split long text into bounded sentence-aware chunks, preserve context and punctuation, cache each generated chunk, and resume failed jobs.
- Keep one voice/model configuration across the full project. Never silently switch voices on error.
- Normalize sample rate/channels/encoding and audio levels before joining. Preserve 24 kHz if that is the selected model output format, unless downstream encoding requires an explicit conversion.
- Preview must use the same engine, voice, and settings as final rendering.
- No per-character API billing in the offline path. Internet is needed only for initial model download or optional online translation.
- If app data is cleared or the user changes device, the model may need to be downloaded again.

Quality/performance note:
VieNeu Nano is a lightweight/preview model; the user's auditioned package is the target baseline. Do not silently upgrade to Turbo or a different voice. Test on the target OPPO/Android device before declaring performance acceptable.

## 5. Subtitle and audio

- Generate subtitles from the same approved Vietnamese segments used for TTS.
- Align subtitles to actual generated speech duration/timing, not just original Chinese timestamps.
- Retain original background music/ambience where possible.
- If original speech cannot be separated cleanly, offer clear choices to lower/mute original audio; do not claim perfect vocal isolation.
- Render through FFmpeg in a persistent foreground WorkManager job with notification, progress, cancellation, and resumable checkpoints.

## 6. Device and storage strategy

- Process one stage at a time: OCR -> translation review -> TTS chunks -> subtitles/audio -> final render.
- Use low-resolution proxies for analysis where suitable; apply confirmed results to original-resolution output.
- Check available storage before model download and before render.
- Keep intermediate artifacts until the export succeeds; then allow safe cleanup.
- Avoid running OCR, restoration, TTS, and FFmpeg render concurrently on an 8 GB RAM phone.
- Model download must be resumable and checksum-verified.

## 7. Acceptance gates

Before APK release:
1. OCR correctly detects Chinese text in representative product clips.
2. Tracking remains stable when overlay moves/fades.
3. Printed product labels are preserved.
4. Restoration is visually checked; bad results fall back to Vietnamese overlay.
5. Translation does not alter numeric/product facts.
6. VieNeu Nano inference runs locally on the target Android phone with no API call.
7. Model downloads once and is reused after app restart.
8. Long TTS scripts resume by chunk and keep one voice.
9. Subtitle timing matches the generated Vietnamese voice.
10. Background processing survives app minimization and recovers after process death where WorkManager permits.
11. Export succeeds without modifying the source video.

This is a design decision only. No engine or APK build is claimed to be complete.
