# LONGKAKA SELLER AI — Module contract and implementation checklist

This document is the agreed product contract for the two independent video workflows.
Keep it in the repository so future changes do not silently change the scope.

## 1. Sales video module

### Agreed workflow
1. Import a product video.
2. Analyze sampled video frames for product, scenes, and usage context.
3. Extract original spoken dialogue with speech-to-text; inspect visible text with OCR.
4. Analyze delivery characteristics (pace, energy, emotion, broad voice presentation) without cloning a person's identity.
5. Combine visual observations, transcript, OCR, and seller-confirmed facts into a ChatGPT handoff.
6. Rewrite into a natural Vietnamese sales script with a strong opening, customer need, verified benefits, and call to action.
7. Never invent price, discounts, specifications, origin, certifications, guarantees, stock, or benefits.
8. Generate narration, preferring the integrated VieNeu Nano engine.
9. Blur/cover source text, add Vietnamese subtitles, optionally mix background music, and export MP4.

### Audio policy
- New Vietnamese narration is the primary voice track.
- Source audio and background music must have separate controls where technically available.
- Do not let sales audio settings leak into movie translation.

## 2. Movie translation module

### Agreed workflow
1. Import a movie/video.
2. Extract multilingual spoken dialogue with timestamps; identify speakers if available, but speaker-specific voices are not required.
3. Translate dialogue in context, preserving names, relationships, pronouns, terminology, and conversational intent.
4. Use exactly one consistent Vietnamese voice for all translated dialogue.
5. Generate synchronized Vietnamese subtitles.
6. Preserve original picture and, where possible, music and sound effects.
7. Export translated MP4.

### Audio policy
Provide independent controls for:
- Original film/dialogue audio: original / reduced / muted.
- Vietnamese translated narration: level / muted.
- Background music and effects: separate level when a reliable stem or track is available.

If dialogue/music cannot be separated reliably from a single mixed source track, disclose that limitation and provide an original-audio volume control; do not claim perfect music preservation.

## 3. Existing implementation status at baseline commit 9ce0f34119397fcfa186ff089bcd875f7798a979

### Already present in source
- Sales and movie workflow selection in the dashboard.
- OCR scanning of sampled frames for Chinese Han text.
- Online translation of recognized on-screen text.
- Editable overlay regions, blur/cover layers, Vietnamese text editing, and MP4 overlay export.
- Manual ChatGPT/Gemini prompt handoff for sales copy.
- VieNeu Nano narration integration for the sales script.
- Export path under Movies/LONGKAKA SELLER AI.

### Not yet implemented as a complete workflow
- General multilingual OCR coverage (current OCR filter is Chinese-focused).
- Speech-to-text extraction of original video/movie dialogue with timestamps.
- Product understanding from sampled video frames as a complete sales analysis stage.
- Original speech delivery analysis.
- Automatic ChatGPT API round-trip; current sales handoff is manual copy/share.
- Context-batched translation of full spoken dialogue.
- Single-voice movie narration pipeline with dialogue timing.
- Independent original / translated / music audio mixing controls.
- Reliable source dialogue/music separation.
- End-to-end device validation for long videos, sync, memory, background execution, and export recovery.

## 4. Regression and delivery rules
- Keep Sales and Movie pipelines independent; share only safe low-level media utilities.
- Do not remove or rewrite working blur, subtitle editor, VieNeu, or export behavior without a focused regression test.
- Do not trigger a new APK build merely for documentation or intermediate source updates.
- Before release, run compile/tests and inspect packaged native libraries; then test both workflows on-device.
- A successful CI build is not proof of real-device audio/video correctness.
- Prefer free/on-device processing. If a network service or API is needed, disclose the dependency and any cost/limits before enabling it.
