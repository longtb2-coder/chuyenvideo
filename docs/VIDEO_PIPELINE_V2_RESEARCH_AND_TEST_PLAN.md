# Video Pipeline V2 — Research Update and Test Plan
Date: 2026-11-05
Status: engineering specification + source-video inspection only. No restoration model has been run and no edited MP4 has been produced.

## 1. Current source-video fixture

Library fixture: `16607.mp4` (user-provided).
- Duration: 127.313 s
- Frame size: 1080 x 1920 (portrait)
- Video: H.264, 30 fps, 3819 frames
- Audio: AAC, 44.1 kHz, stereo

A limited visual inspection was performed at 00:05, 00:35, 01:15, and 01:55. This is a sampling inspection, not a complete frame-by-frame review.

Observed text classes:
- Bottom-center Chinese dialogue/caption overlay with white glyphs and dark outline: primary removal candidate.
- Chinese text displayed on a television / device screen: scene content; preserve unless the user explicitly selects it.
- Chinese printing on product packaging or labels: preserve as part of the product.
- Small persistent top-right graphic/watermark: exclude from text-cleanup masks.
- Other incidental text in the scene: preserve by default.

This fixture is a useful negative-control test: a successful pipeline must remove the intended bottom overlay without erasing the TV display, product printing, or corner graphic.

## 2. Research update — candidates and exclusions

The key design decision remains: text-overlay removal is not identical to object removal. A model that removes people or objects well may still erase product details, rewrite printed labels, or hallucinate the background under subtitles.

| Candidate | What it offers | Practical decision |
|---|---|---|
| SVOR | Temporal mask-union and robustness to imperfect masks; repository/model card reports high GPU memory demand (about 33 GB default). | Keep as an external-GPU research candidate only. Not an Android-phone model. Verify every base-weight, LoRA, and dependency license separately. |
| VOID (Netflix) | Video object and interaction deletion; official project reports a 40 GB+ GPU requirement for its notebook workflow. | Not a first implementation target: too heavy for the phone/free-notebook baseline and object-removal focus differs from caption cleanup. |
| VACE / ROSE | Apache-licensed code/weights are reported for specific upstream revisions in a recent third-party evaluation. | Candidate only. That evaluation found inconsistent temporal behavior on its small test corpus, including a ROSE case that recreated a wordmark. Re-test on this exact caption task before adoption. |
| DiffuEraser | Temporal video inpainting approach. | Do not ship until the inherited ProPainter dependency and exact model weights are cleared for commercial use. |
| ProPainter / E2FGVI | Established temporal inpainting research methods. | Excluded from a seller/commercial build unless written commercial permission is obtained; their upstream terms are non-commercial. |

Research references:
- SVOR model card: https://huggingface.co/HigherHu/SVOR
- VOID official repository: https://github.com/Netflix/void-model
- VACE / ROSE comparison and caveats (third-party, limited evaluation): https://github.com/wiltodelta/remove-ai-watermarks/blob/main/docs/video-inpainting-research.md
- DiffuEraser repository and dependency/license note: https://github.com/apanner/DVP
- ProPainter: https://github.com/sczhou/ProPainter
- E2FGVI: https://github.com/MCG-NKU/E2FGVI

These references are research leads, not evidence that any model has passed our own fixture. Licenses must be checked against pinned commits and exact downloaded weight files at integration time.

## 3. Proposed pipeline refinement

### A. Classify before masking
1. Detect text instances per frame, retaining polygons/glyph contours, OCR text, confidence, and timestamps.
2. Classify each instance as editable overlay, scene-native text, product label, screen content, logo/watermark, or uncertain.
3. Use temporal persistence, position, style, motion, and scene context as evidence—not a single fixed bottom rectangle.
4. Default uncertain detections to KEEP. Let the user tap a text track to include/exclude it.
5. Store a separate track ID for each caption line; split tracks at scene cuts or major style/position changes.

### B. Build temporally stable glyph masks
1. Refine OCR polygons to glyph edges; expand only enough to cover anti-aliasing, outline, and shadow.
2. Track masks across adjacent frames using optical flow plus text re-detection.
3. Use a short temporal union around motion/appearance transitions to avoid clipped strokes.
4. Prevent mask leakage into nearby product edges, hands, screens, or unrelated text.
5. Show a mask preview before processing; do not silently apply a broad ROI.

### C. Restore using the least destructive source of evidence
1. Search nearby frames in the same shot for real background pixels revealed when the caption moves or disappears.
2. Align candidate pixels using camera/object motion; reject occluded or inconsistent samples.
3. Use conservative texture reconstruction only when confidence is high.
4. If no real background evidence exists, optionally invoke a license-cleared temporal AI model on a short shot segment with context frames.
5. Compare multiple candidate outputs and reject flicker, geometry changes, invented product details, or changes outside the mask.
6. If confidence remains low, ask for manual review or offer subtitle-only mode. Do not replace uncertainty with a blurred rectangle.

### D. Composite subtitles last
- Complete restoration first.
- Render Vietnamese subtitles as a separate final layer.
- Keep original audio, duration, frame rate, aspect ratio, and resolution unless the user changes them.
- Keep subtitle styling independent from the inpainting mask; use safe margins and avoid covering the product or hands.

## 4. Test plan for 16607.mp4

### Test cases
1. Bottom subtitle on a mostly static background.
2. Bottom subtitle over a moving hand/product demonstration.
3. Caption transition: first/last visible frame and changing text lines.
4. Product printing close to the caption region (negative control; must remain).
5. Chinese text on the TV/device display (negative control; must remain).
6. Persistent top-right watermark/graphic (negative control; must remain).
7. Scene cuts and fast camera motion.
8. Subtitle translation and two-line wrapping after restoration.

### Measurements and release gates
- Target-caption removal: no visible Chinese caption remnants at normal playback speed; inspect transitions frame-by-frame.
- Negative controls: product printing, TV text, and corner graphics remain intact.
- Mask precision: no broad rectangular spill; inspect polygon overlay and boundary pixels.
- Temporal quality: no flicker, crawling edges, patch sliding, sudden texture changes, or frame-to-frame brightness pumping.
- Scene fidelity: product shape, color, hands, faces, and background geometry must not change outside the authorized mask.
- Subtitle quality: Vietnamese text is readable, correctly timed, not clipped, and does not obscure the product.
- Technical integrity: output decodes fully; 30 fps, 1080x1920, duration, audio stream, and A/V sync are preserved within export tolerance.
- Compare source and output side-by-side at full speed and frame-by-frame. Record defects with timestamp and severity. A passing build or successful model inference is not a quality pass.

### Required test artifacts
- Original source checksum and media probe report.
- Per-frame OCR/text-track JSON and editable/excluded track decisions.
- Mask-overlay preview video.
- At least two restoration candidates when external models are compared.
- Final MP4, frame contact sheets, OCR residual report, temporal-flicker report, audio/video integrity report, and defect log.

## 5. Honest status after this inspection

Completed:
- Recovered the user's 127.313-second source fixture from the Library.
- Verified media metadata with FFprobe.
- Inspected four representative frames and identified target captions versus scene-native text and exclusions.
- Added this research update and a concrete test plan.

Not completed:
- No OCR track generation or glyph masks were produced.
- No inpainting model was executed.
- No restored frame or final MP4 exists.
- No Android source implementation, APK, or on-device performance test exists.

Next engineering prerequisite:
- Obtain the actual Android source repository/branch or source ZIP. The current architecture branch contains documentation only. Then implement the mask-review and restoration pipeline behind isolated interfaces, run the full fixture test, and build an APK only after visual QA passes.
