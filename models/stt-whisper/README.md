# STT (Whisper)

## Current status

- Placeholder only
- Intended model: `Whisper tiny`
- Intended artifact format: ExecuTorch `.pte`

## What exists today

The repo now has a real artifact contract:

- `artifacts/shared/whisper/manifest.json`
- `app/src/main/assets/speech/manifest.json`
- staging instructions under `app/src/main/assets/speech/stt/whisper-tiny/`

The actual Whisper runtime artifacts are still missing.

## Expected artifacts

- `whisper_encoder.pte`
- `whisper_decoder.pte`

## Current artifact view

- `current/artifact_status.json`
- `artifacts/shared/whisper/manifest.json`
- `app/src/main/assets/speech/manifest.json`

## Next step

Export the Whisper artifacts off-device, validate them, and only then wire the
Android/macOS runtime path.
