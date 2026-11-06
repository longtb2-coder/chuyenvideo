# Free-first video text removal and restoration

## Product requirement

The default LONGKAKA SELLER AI workflow must not require a paid API, subscription,
cloud GPU, or remote inference service. The Android device must retain a local
fallback so that export never depends on network availability.

The quality target is to reconstruct the scene behind Chinese glyphs so that the
result is visually close to a source clip that never had the overlay. This is a
quality target, not a guarantee: pixels fully hidden by text cannot always be
recovered from a single frame.

## Current local pipeline

1. ML Kit Chinese OCR detects text and character/element boxes on-device.
2. OCR observations are tracked over time. Glyph-shaped masks are used instead
   of painting a fixed rectangular blur panel.
3. Before inpainting, the restoration stage looks for nearby sampled time points
   where this OCR track was not observed.
4. A candidate temporal donor is accepted only if:
   - the candidate crop has low local contrast inside the glyph boxes (a guard
     against copying another text remnant); and
   - a sparse comparison of pixels outside the glyph boxes shows that the
     surrounding scene is still sufficiently similar.
5. When a safe donor exists, its real pixels replace only the glyph mask.
   Otherwise OpenCV Telea inpainting is used, with Navier-Stokes as an exception
   fallback. Pixels outside the glyph mask remain from the current frame.
6. The export verifier scans the cleaned intermediate before Vietnamese
   subtitles are burned in.

This is a conservative temporal-donor strategy, not full optical-flow video
inpainting. It intentionally rejects donors when the background has moved or
the scene differs, rather than copying a visibly misaligned patch.

## Model selection policy

Do not make SVOR, VOID, VACE, ROSE, ProPainter, or any other research model a
mandatory runtime dependency. Before adding an adapter, verify:

- the repository and checkpoint license permit the intended Seller AI use;
- model weights are available from a legitimate source;
- Android/runtime compatibility and supported ABI are known;
- peak RAM, VRAM, disk, and processing time are measured;
- the model can be disabled or replaced without changing the editor/export UI;
- output is compared against the local fallback on the same test clips.

Large research models that require paid/cloud GPUs are not part of the default
free path. LiteRT or ONNX Runtime may be evaluated as local inference runtimes,
but a runtime alone is not an image-restoration model.

## Planned fallback order

- Safe temporal donor pixels, only when temporal and background checks pass.
- Local OpenCV Telea inpainting.
- Local OpenCV Navier-Stokes inpainting if the primary method fails.
- If all methods fail validation, stop export and identify the affected time
  range; do not silently publish a falsely successful result.

## Resource limits

- Keep temporary frame/bitmap memory bounded.
- Decode candidate frames one at a time and recycle them immediately.
- Never retain full-resolution video frames for the entire clip.
- Preserve cancellation and progress reporting.
- Prefer a smaller local processing resolution only when the user is informed
  and the quality trade-off is measured.

## Required quality tests

Use the same source clips for all methods and inspect the exported MP4, not only
the source code or CI build:

- static text over flat and textured backgrounds;
- text over moving people, hands, fabric, and products;
- moving text and changing text size;
- text that appears/disappears between scenes;
- camera movement and scene cuts;
- portrait and landscape clips;
- long clips and multiple OCR tracks.

Record residual Chinese, mask leakage, seams, blur, temporal flicker, subtitle
legibility, audio sync, export stability, peak memory, and processing time.

A successful Android build is not evidence of visually successful restoration.
