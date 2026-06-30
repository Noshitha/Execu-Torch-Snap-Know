# Piper TTS On-Device Plan

## Recommended Artifacts

- `en_US-lessac-medium.onnx`
- `en_US-lessac-medium.onnx.json`

## Why

Piper is best treated as an ONNX voice package. The important work here is to
standardize the asset layout and runtime contract for Android and macOS, not
to re-export it as an ExecuTorch model.

## Runtime Path

- Android: Piper runtime bridge plus packaged ONNX assets
- macOS: Piper host runtime or ONNX-based desktop runner

## Files To Keep Stable

- `artifacts/shared/piper/manifest.json`
- `models/tts-piper/current/VOICE_PACKAGE_STATUS.txt`

## Next Export Work

1. Stage the voice package in the expected folder layout.
2. Add checksum or size metadata after the real assets are added.
3. Wire the runtime bridge against the same asset contract on both platforms.
