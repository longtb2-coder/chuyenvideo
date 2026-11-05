# Video Pipeline V2 — Hybrid, Free-First Redesign

Status: design proposal only. This document does not claim that Android code, a server, an APK, or a successful video export exists.

## Product goals
- Replace the old single-frame blur/patch-stretch pipeline; do not extend it.
- Remove editable/authorized Chinese captions or overlays from the main content area while preserving the underlying scene as faithfully as possible.
- Keep original video, audio, timing, frame rate, and aspect ratio unless the user explicitly changes them.
- Add Vietnamese subtitles only after restoration, so subtitle pixels are never passed through the restoration model.
- Use phone-side processing by default and optional user-authorized free compute when available.
- Never require a paid API, paid GPU, API key, or always-on third-party free server.
- Be honest when a scene cannot be restored faithfully; offer keep-original / manual-review / subtitle-only fallbacks rather than inventing background.

## Key feasibility decision
There is no dependable, always-on, unlimited free GPU backend that an Android app can safely depend on. Google Colab states that free resources are not guaranteed, limits fluctuate, and free managed runtimes may be terminated when used as a web-service backend. Kaggle offers free notebook GPU access, but availability is limited and queued. Therefore, notebook services are optional, user-initiated compute sessions, not a production API hidden behind the app.
Sources:
- Google Colab FAQ: https://research.google.com/colaboratory/faq.html
- Kaggle Notebooks: https://www.kaggle.com/docs/notebooks

## Proposed architecture
### A. Android client — always available
1. Import and probe the source with Android media APIs or the selected media backend.
2. Create a durable project/job record and copy media into managed temporary storage only when required.
3. Detect scene cuts and sample frames adaptively (dense around text appearance/disappearance and scene changes; sparse elsewhere).
4. Run on-device OCR and text-language classification. Identify candidate text tracks, not generic rectangles.
5. Let the user confirm the main content ROI and exclude watermarks, logos, edge captions, product labels, and unrelated UI text. Save exclusions per project.
6. Generate frame-level glyph masks and track IDs. Use OCR boxes, text contours, optical flow/tracking, and temporal union to avoid missed strokes during motion. Preserve a reviewable mask overlay.
7. Build a processing plan per region/scene and choose the safest restoration strategy.
8. Run supported lightweight local operations on phone: mask refinement, temporal frame matching, patch/texture reconstruction where the same background is visible in nearby frames, and conservative color/noise matching.
9. Keep original-resolution frames and audio as the master. Any reduced-resolution AI preview is a proxy only; upscale/composite carefully and never silently replace the source.
10. Show per-stage progress, estimated storage, cancellation, pause/resume, and a clear reason when a stage needs external compute.
11. Add Vietnamese subtitles as a separate final compositing pass after restoration.
12. Export, then run a quality audit before marking the project complete.

### B. Optional free compute — explicit handoff, not a hidden service
Define a provider-neutral interface called RemoteComputeAdapter with capabilities, limits, license metadata, upload consent, job state, cancellation, and result verification.
Initial modes:
- LOCAL_ONLY: fully on-device; always available, slower and limited.
- NOTEBOOK_HANDOFF: prepare a versioned job bundle (source clip, frame masks, metadata, audio policy, model config, checksum) for a user-started Kaggle/Colab notebook. The user opens the notebook, starts a free session if available, uploads/runs the job, and imports the result back into the app. Do not automate or disguise a notebook as a persistent web API.
- USER_HOSTED_ENDPOINT: optional future adapter for a server the user owns or is explicitly authorized to use. Disabled by default; no server is included or promised free.
- PUBLIC_FREE_ENDPOINT: disabled unless a provider publishes a stable API, permits this workload, offers an actual free quota, and passes privacy, terms, reliability, and load tests. Never assume a free demo UI is an API.
The app must never upload source video without explicit per-job consent. Allow mask-only or low-resolution proxy export for privacy-sensitive tests where quality requirements permit.

### C. Model selection and license gate
Do not hardwire a model into the Android app. Use a model registry containing model name/version, code license, weight license, required VRAM/RAM, supported resolution, runtime, and provenance.
Research candidates:
- SVOR (Xiaomi Research): repository identifies Apache-2.0 for the code and describes temporal-stability and imperfect-mask handling. Its README says default inference uses about 33 GB GPU memory; a 24 GB GPU may use CPU offload, with lower sample sizes to reduce memory. This is a research candidate for a capable external GPU, not a phone model and not a promise that a free notebook GPU can run it. Verify licenses for all separately downloaded base weights, LoRAs, and dependencies before integration. https://github.com/xiaomi-research/svor
- ProPainter: temporal video inpainting research baseline, but its official repository declares non-commercial-only terms. Exclude from any commercial/seller-app distribution unless written commercial permission is obtained. https://github.com/sczhou/ProPainter
- E2FGVI: flow-guided video inpainting research baseline, but its official repository declares CC BY-NC 4.0 for non-commercial use. Exclude from commercial distribution unless permission is obtained. https://github.com/MCG-NKU/E2FGVI
No model is approved for release until both source code and exact weight files have been license-reviewed. An Apache-2.0 code repository does not automatically make separately downloaded model weights or dependencies Apache-2.0.

## Restoration strategy — scene-aware cascade
1. Temporal evidence first: search adjacent frames and other moments in the same shot for pixels that reveal the background behind the text. Align candidates using camera motion/optical flow and reject occluded or inconsistent samples.
2. Conservative local reconstruction: use aligned real pixels and texture/color blending only when confidence is high. Avoid stretching a patch from one observation across a moving box.
3. Optional temporal AI inpainting: if the local result is uncertain and an eligible external model is available, process a short shot segment with temporally expanded masks. Keep context frames before/after the target interval.
4. Confidence gate: compare temporal consistency, edge continuity, color/texture discontinuity, residual text probability, and scene semantics. If confidence is low, mark for review rather than hallucinating.
5. Manual fallback: allow the user to refine the mask or choose subtitle-only mode. Never force a blurred rectangle over the scene as the only outcome.

## Subtitle and visual finishing
- Generate subtitle timing from speech/transcription or translated source captions; keep text and timing editable.
- Render subtitles after all restoration and enhancement passes.
- Use a readable minimum font size based on output dimensions; wrap lines and reposition around the product/subject and restored region.
- Preserve original audio by default; if translated voiceover is enabled, keep original audio as a separately controllable track.
- Apply optional, restrained finishing: shot-level exposure/white-balance normalization, mild denoise, conservative sharpening, stabilization only when needed, and consistent subtitle typography.
- Do not apply global beautification or frame interpolation by default; those can distort products, faces, text, or motion.

## Quality assurance — release blockers
Every test export must be checked at full speed and frame-by-frame at text appearance/disappearance and scene cuts; fast motion and camera movement; patterned backgrounds, faces, product edges, hands, reflective surfaces; start/end of every mask track; subtitle boundaries and multi-line wrapping; audio/video sync and output metadata.
Automated checks: residual Chinese-text OCR rate inside target masks; false-positive removal outside approved ROI; temporal flicker / frame-to-frame patch discontinuity; edge and texture discontinuity around restored regions; subtitle OCR/readability and minimum font size; frame count, FPS, resolution, duration, audio stream, and A/V sync; output decode test and file-size sanity.
Acceptance requires side-by-side source/output review on the same real source clip, with a documented defect log. A successful Gradle build is not evidence of restoration quality.

## Suggested Android module boundaries
- feature/import: import, metadata, source permissions
- feature/scene-analysis: shot detection, ROI, OCR, language classification
- feature/text-tracking: glyph masks, track IDs, temporal mask union
- core/restoration-contracts: strategy, confidence, model capability, result types
- core/restoration-local: on-device temporal evidence and conservative reconstruction
- core/restoration-remote: notebook bundle/import and future user-hosted adapter
- feature/subtitles: translation text, timing, layout, preview
- core/media-export: final composition, audio preservation, codec handling
- feature/quality-audit: QA metrics, frame sampling, defect report
- core/jobs: persistent progress, pause/resume, cleanup, notifications

## Delivery phases
1. Freeze old pipeline and preserve a known baseline; do not carry its single-frame blur implementation forward.
2. Build the Android project foundation and typed pipeline contracts.
3. Implement phone-side analysis, glyph tracking, scene segmentation, and manual review.
4. Implement local temporal evidence reconstruction and subtitle-after-restoration export.
5. Create the optional notebook handoff bundle and one documented user-run notebook proof-of-concept. No persistent free backend.
6. Evaluate SVOR and other license-cleared temporal models on a GPU with enough VRAM; measure real memory and speed before deciding whether any can run in a free notebook.
7. Test against the user's 127-second 1080x1920 source video; inspect the complete export and record quality metrics.
8. Build APK only after pipeline tests, export QA, upgrade checks, and device-memory checks pass.

## Non-goals / honest limits
- No guarantee of free GPU availability or uninterrupted cloud processing.
- No claim that AI can recover pixels that never appear anywhere in the source; generated content may be plausible but not historically exact.
- No use of free notebook platforms as a covert persistent web service.
- No paid API, rented GPU, embedded API key, or non-commercial model license in a seller/commercial release.
- No APK or implementation claim until source code and CI artifacts actually exist.