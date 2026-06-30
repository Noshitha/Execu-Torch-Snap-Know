# VLM On-Device Plan

## Recommended Artifact Bundle

- `vision_encoder.pte`
- `projector.pte`
- `decoder.pte`
- tokenizer assets

## Why

The VLM path should be modeled as a staged bundle rather than a single export.
That matches how scene understanding actually gets deployed on-device.

## Runtime Path

- Android: ExecuTorch for encoder and decoder stages plus host-side token loop
- macOS: same artifact bundle with a desktop host runner

## Current Gap

The repo already has a manifest and a staged Android integration path, but it
does not yet contain the full multimodal decode runtime.

## Next Export Work

1. Lock the exact VLM checkpoint.
2. Export encoder, projector, and decoder targets separately.
3. Add tokenizer assets and a desktop smoke-test path.
