# VLM

## Current status

- Manifest only
- Intended direction: staged SmolVLM path
- Intended artifact format: ExecuTorch `.pte` plus tokenizer/config files

## What exists today

The repo currently contains a manifest describing the intended asset bundle, but
not the complete runnable VLM stack.

## Expected artifacts

- `vision_encoder.pte`
- `projector.pte`
- `text_decoder.pte`
- tokenizer/config files

## Current artifact view

- `artifact_status.json`
- `app/src/main/assets/vlm/smolvlm_manifest.json`
- `artifacts/shared/vlm/manifest.json`

## Next step

Bundle the full VLM asset set and then validate scene-description or scene-QA
off-device before mobile runtime work.
