# STT (Whisper)

## Current status

- Placeholder only
- Intended model: `Whisper tiny`
- Intended artifact format: ExecuTorch `.pte`

## What exists today

The repo contains export scripts and placeholder asset docs, but not the actual
Whisper runtime artifacts in this branch yet.

## Expected artifacts

- `whisper_encoder.pte`
- `whisper_decoder.pte`

## Current artifact view

- `current/README.txt`

## Next step

Export the Whisper artifacts off-device, validate them, and only then wire the
Android/macOS runtime path.
