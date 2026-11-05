# Temporal text restoration — implementation notes and acceptance tests

Status: implementation refinement in `feature/temporal-text-restoration`; APK/visual QA pending.

## Scope
This is the LONGKAKA SELLER AI Android app, package `ai.longkaka.seller`. It is not the LongVideo AI story-narration app.

The current on-device path uses ML Kit Chinese OCR geometry and OpenCV glyph-shaped masks. It is a conservative spatial inpainting method, not a learned temporal video-inpainting model. This change improves the safety of temporal patch reuse; it does not claim perfect background reconstruction.

## Refinements in this branch
- Raise the global sampled restoration-patch budget from 160 to 240, with a per-track cap, to reduce temporal gaps while bounding memory.
- Do not reuse a sampled restoration patch when its timestamp is too far from the output frame. A stale patch can otherwise slide across a changing background and create obvious smears.
- Keep the original source unchanged and retain the existing two-stage export order: restoration first, Vietnamese subtitles second.
- Preserve uncertain or scene-native text by default. Product printing, text shown on a TV/device, and corner graphics are negative controls and must not be selected as subtitle overlays by automatic broad-region rules.

## Fixture
User-provided `16607.mp4`:
- 127.313 seconds
- 1080 x 1920 portrait
- 30 fps
- H.264 video + AAC audio

A four-frame spot inspection found distinct classes: bottom Chinese dialogue captions (candidate for removal), Chinese text on a TV/device screen (preserve), product/package printing (preserve), and a persistent corner graphic/watermark (exclude). This is not a full-video visual pass.

## Acceptance checklist
1. Only user-approved bottom caption tracks are removed.
2. TV/device text, product printing, and corner graphics remain pixel-faithful.
3. No broad rectangular blur/cover is used as the sole restoration result.
4. No stale patch is reused across a long timestamp gap.
5. Review at normal playback speed and frame-by-frame at text start/end, scene cuts, motion, hands, product edges, and patterned backgrounds.
6. Vietnamese subtitle layer is rendered only after restoration; timing, wrap, safe area and readability are checked.
7. Export decodes fully; preserve source duration, 30 fps, 1080x1920, audio presence and A/V sync.
8. Log every defect with timestamp. A successful Gradle build is not visual acceptance.

## Known limitations
- OpenCV Telea/NS inpainting is local spatial reconstruction. It cannot recover hidden texture faithfully where no evidence exists.
- The current app does not yet run a full temporal neural video-inpainting model on-device.
- Increasing sampled patches improves coverage but is not equivalent to per-frame optical-flow reconstruction.
- This branch must pass CI and be exercised on the actual phone before it is called release-ready.
