# Video localization pipeline — design specification

Status: DESIGN APPROVED FOR PLANNING
Branch: foundation/architecture
APK builds: intentionally disabled during module design
Implementation status: specification only; no OCR, inpainting, translation, ASR, TTS, or subtitle engine is claimed to be implemented by this document.

## 1. Product decision

Default workflow: **Smart automatic**.

Output defaults:
- Vietnamese voice-over.
- Vietnamese subtitles.
- Preserve the original video and keep the imported source unchanged.
- Keep original music/ambience where possible, while reducing or replacing original speech only when the user chooses that mode.
- Prefer on-device/free processing; never require a paid API key for the basic workflow.
- Ask the user to review uncertain OCR, translation, timing, or restoration results rather than silently inventing content.

## 2. One-tap user journey

1. Select an imported video from the local library.
2. Tap “Chuyển video sang tiếng Việt”.
3. App probes the media, creates a working copy/proxy if needed, and estimates duration, resolution, frame rate, audio tracks, and free storage.
4. Detect Chinese text overlays and spoken language separately.
5. Build a reviewable content timeline: OCR text regions with timestamps, ASR speech segments with timestamps, and confidence values.
6. Translate approved Chinese text and speech into natural Vietnamese for product-selling context.
7. Restore text regions where safe. If restoration quality is poor or uncertain, use a localized Vietnamese overlay/subtitle instead.
8. Generate Vietnamese TTS from the approved speech translation; align subtitles to the same Vietnamese script and generated speech timing.
9. Mix voice-over with retained music/ambience, then render a new output file.
10. Present preview, warnings, and export location. Never overwrite the imported source.

## 3. Processing stages and contracts

### A. Media inspection and preparation
Input: MediaStore content URI.
Output: MediaInfo (duration, dimensions, rotation, frame rate when available, audio presence, codec hints, estimated working-space requirement).
- Work with content URIs; do not assume a filesystem path.
- Use a temporary working directory and clean it after successful export or explicit cancellation.
- Check free storage before starting and during long jobs.
- Long jobs must use persistent foreground WorkManager work with notification, progress, pause/cancel where feasible, retry-safe checkpoints, and process-death recovery.

### B. OCR and text tracking
Input: sampled frames plus targeted frames around detected text changes.
Output: TextRegion records containing text, language, normalized bounding box, first/last timestamps, per-frame/track references, confidence, and classification.
- Detect Simplified and Traditional Chinese; also preserve digits, Latin product codes, units, and prices.
- Merge repeated detections into a stable track so one overlay is not translated repeatedly.
- Track movement/animation; do not assume the text stays at a fixed coordinate.
- Distinguish editable marketing text from product labels printed on the product itself. Printed labels should not be erased automatically.
- Low-confidence OCR is marked for review, not silently translated.

### C. Speech recognition (ASR)
Input: extracted audio or audio chunks.
Output: SpeechSegment records with source language, text, start/end times, speaker/voice hints where available, and confidence.
- OCR and ASR are separate sources; do not assume on-screen text equals spoken narration.
- Detect whether the speech is Chinese, Vietnamese, mixed, or unknown.
- Preserve non-speech music and useful product sound effects where possible.
- If ASR is unavailable or confidence is low, offer subtitles/voice-over from OCR text only after user confirmation.

### D. Translation and copy adaptation
Input: approved OCR and ASR segments.
Output: Vietnamese segments retaining source references and timestamps.
- Default to concise, natural Vietnamese suitable for product demonstrations.
- Preserve product names, model numbers, measurements, currency, quantities, and technical specifications.
- Do not invent discounts, certifications, performance claims, warranty terms, or product features.
- Separate faithful translation from optional sales-style rewriting. The default is faithful, clear Vietnamese; optional rewriting must remain truthful.
- Provide an editable transcript before final TTS/rendering.
- Cache approved translations per project so reruns do not repeat work.

### E. Text removal and visual restoration
Input: tracked text regions and source frames.
Decision order:
1. Check whether the region is a removable video overlay and whether it is appropriate for the user to edit.
2. Prefer temporal reconstruction from neighboring frames when the same background is visible without the text.
3. Otherwise try local inpainting on the masked region, with temporal consistency checks across adjacent frames.
4. Evaluate the result for flicker, blur, smearing, edge damage, and product-detail loss.
5. If restoration is poor/uncertain, do not force it: preserve the source region and place a Vietnamese subtitle/translation in a clear safe area, or use a restrained background panel to cover only the editable text.
- Never claim perfect reconstruction where the original pixels are obscured.
- Do not remove platform ownership watermarks or attribution marks by default. Treat them separately from ordinary Chinese promotional text.
- Never erase text printed on the physical product by default.
- Save a restoration mask and decision metadata so the project can be revised.

### F. Vietnamese TTS
Input: approved Vietnamese speech script, segment timing, selected voice settings.
Output: audio segments and a final continuous voice track.
- Free/offline voice is the default; no per-character paid API dependency for the basic path.
- Keep one selected voice consistently across the whole video.
- Provide short audition, speed, volume, pause, and pronunciation dictionary controls.
- Split long scripts into bounded chunks; preserve sentence boundaries and context; join with consistent sample rate, channels, loudness, and format.
- Cache generated chunks and resume after interruption.
- If offline model is not installed, explain required model download and storage before starting.
- Do not silently switch voices midway if a chunk fails.

### G. Subtitle and audio synchronization
Input: final Vietnamese transcript and TTS audio.
Output: SRT/ASS subtitle track plus render instructions.
- Subtitles derive from the same approved Vietnamese text as TTS.
- Align captions to generated speech, not merely to original Chinese timestamps.
- Break lines into readable phrases; avoid covering the product or important demonstration actions.
- Allow user adjustment of position, size, outline/background, and safe margins.
- Include a preview of subtitle timing before rendering.

### H. Audio mix and render
- Preserve original background music/ambience where possible.
- Reduce original speech under the Vietnamese voice using available audio processing; if clean separation is not possible, offer original-audio mute or lower-volume fallback rather than promising perfect vocal isolation.
- Duck background audio while Vietnamese narration is speaking.
- Keep output aspect ratio/orientation unless user selects a target format.
- Render through FFmpeg in the persistent background job.
- Export a new file; never overwrite the imported source.

## 4. Smart fallback policy

| Condition | Automatic response |
|---|---|
| OCR confidence high; background is recoverable | Restore region, then verify temporal consistency |
| OCR high; restoration quality poor | Use Vietnamese overlay/subtitle in a safe area |
| OCR confidence low | Mark for user review; do not invent translation |
| Text appears printed on the product | Preserve it; optionally translate in subtitles |
| ASR confidence high | Translate speech and prepare Vietnamese TTS |
| ASR confidence low or unsupported language | Offer transcript review or OCR-only workflow |
| TTS chunk fails | Retry/resume that chunk; do not change voice silently |
| Original speech cannot be separated cleanly | Lower/mute original audio with user-visible notice |
| Storage or memory is insufficient | Stop safely, preserve checkpoints, explain what can be cleared |

## 5. Proposed project data model

- VideoProject: id, sourceContentUri, sourceMetadata, createdAt, status, outputUri.
- TextRegion: id, projectId, text, language, normalizedBounds, startMs, endMs, confidence, regionType, trackId.
- SpeechSegment: id, projectId, sourceText, sourceLanguage, startMs, endMs, confidence, speakerHint.
- TranslationSegment: id, sourceSegmentId, vietnameseText, userEdited, approved.
- RestorationDecision: id, textRegionId, method, qualityStatus, maskReference, userApproved.
- VoiceSettings: voiceId, modelId, speed, pitch, volume, pronunciationDictionary.
- AudioSegment: id, translationSegmentId, localFileReference, sampleRate, channels, durationMs, status.
- SubtitleCue: id, translationSegmentId, startMs, endMs, text, styleReference.
- RenderJob: id, projectId, progress, checkpoint, status, errorCode, outputUri.

All long-running operations must be resumable and tied to a project. Store content URIs and app-managed file references, not assumed absolute paths.

## 6. Performance and cost strategy

- Basic workflow should not require paid cloud APIs.
- Prefer local OCR, local audio processing, local TTS, and FFmpeg.
- Translation can use an optional online provider only with explicit user choice; offline translation is a later model-download option and may be slower/larger.
- Run OCR on sampled/change-detected frames rather than every frame where feasible.
- Use lower-resolution proxy analysis, then apply confirmed masks and subtitles at original output resolution.
- Process long videos in timestamped segments and checkpoint intermediate artifacts.
- Show estimated storage and model download size when known; do not promise a fixed processing time.
- Device resources are finite: large restoration models may be unsuitable for an 8 GB RAM phone unless tiled/optimized or run on a permitted external service.

## 7. User-facing controls

Default screen: one action, “Chuyển video sang tiếng Việt”.
Advanced review screen:
- OCR text and translation list with timestamps.
- Speech transcript and Vietnamese translation.
- Restoration preview: Original / Restored / Vietnamese overlay.
- Voice audition and voice consistency indicator.
- Subtitle preview and style controls.
- Audio mode: retain music + reduce speech, lower original audio, or mute original audio.
- Export resolution/format and output destination.

The default path should require minimal interaction; advanced controls remain optional.

## 8. Acceptance tests before APK release

1. Chinese overlay on a plain static background: restore without visible flicker.
2. Chinese overlay on a moving background: temporal tracking does not leave trails.
3. Chinese text covers product details: fallback to subtitle/overlay, never fabricate hidden details.
4. Printed Chinese label on product: preserve original label.
5. Mixed Chinese speech and Chinese on-screen text: OCR and ASR remain separate and are both translated.
6. Product code, dimensions, price, and units: exact values survive translation.
7. Long speech: TTS chunks resume and keep one voice and consistent audio format.
8. Subtitles match the generated Vietnamese speech and remain inside safe margins.
9. Original music remains audible when possible; failed speech separation is clearly disclosed.
10. App is backgrounded or process is killed: persistent job resumes or reports a recoverable state.
11. Low storage / model missing / permission revoked: fail safely without corrupting source.
12. Exported file plays correctly; source file remains unchanged.

## 9. Scope boundary

This document defines the design only. It does not claim the above engines or user interface are implemented. No APK build or workflow dispatch should occur until the user approves the complete module design and the planned integration scope.
