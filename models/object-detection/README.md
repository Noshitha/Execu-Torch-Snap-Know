# Object Detection

## Current status

- Present in the repo
- Current artifact format: `tflite`
- Current recommended artifact: `fssd_25_8bit_v2.tflite`

## Why this format

This model is already mobile-ready and is currently integrated through
TensorFlow Lite. It is the cleanest working detection artifact in the repo
today.

## Current artifact view

- `artifact_status.json`
- `app/src/main/assets/fssd_25_8bit_v2.tflite`
- `artifacts/object-detection/manifest.json`

## Next step

Keep this as the baseline detection model while the portable face/STT/TTS/VLM
artifact pipeline is being stabilized.
