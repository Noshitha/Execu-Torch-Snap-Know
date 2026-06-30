# Whisper STT On-Device Plan

## Recommended Artifacts

- `whisper_encoder.pte`
- `whisper_decoder.pte`

## Why

The export path is more realistic when the encoder and decoder are tracked as
separate mobile artifacts. That keeps the runtime contract clear for Android
and macOS host integration.

## Runtime Path

- Android: ExecuTorch speech pipeline plus audio feature extraction
- macOS: ExecuTorch or a desktop validation runner using the same exported pair

## Current Gap

This branch does not claim a finished Whisper export yet. It creates the
artifact contract and expected layout so the export work has a stable target.

## Next Export Work

1. Freeze the Whisper tiny variant.
2. Export encoder and decoder separately.
3. Add smoke-test scripts for loading the pair off-device.
