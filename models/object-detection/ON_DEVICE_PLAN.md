# Object Detection On-Device Plan

## Recommended Artifact

- `fssd_25_8bit_v2.tflite`

## Why

This detector is already quantized, small, and integrated in a mobile-friendly
format. It does not need to be forced into `.pte` to be viable on Android or
macOS.

## Runtime Path

- Android: TensorFlow Lite interpreter
- macOS: TensorFlow Lite or a compatible host-side TFLite runner

## Files To Keep Stable

- `app/src/main/assets/fssd_25_8bit_v2.tflite`
- `artifacts/object-detection/manifest.json`

## Next Export Work

1. Verify label mapping for the SSD output tensor.
2. Add a reproducible benchmark script for desktop latency checks.
3. Only evaluate ExecuTorch conversion if TFLite becomes a blocker.
