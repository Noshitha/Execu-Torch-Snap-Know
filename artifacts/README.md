# SnapKnow Model Artifacts

This folder is the source of truth for off-device model readiness before any
Android or macOS deployment work begins.

## Layout

- `model_set.json`
  Defines the intended model set, target platforms, runtimes, and expected
  artifact paths.
- `android/`
  Android-specific outputs such as QNN-optimized `.pte` files.
- `macos/`
  macOS-specific outputs if we later add platform-tuned bundles.
- `shared/`
  Cross-platform artifacts such as XNNPACK `.pte`, Whisper exports, or Piper
  ONNX voice assets.
- `reports/`
  Generated validation reports for off-device review.

## Current Rule

Before wiring any runtime integration:

1. Export or stage the artifacts listed in `model_set.json`
2. Run `python3 scripts/validate_model_artifacts.py`
3. Review `artifacts/reports/model_validation_report.html`

If the required portable artifacts are missing, the device integration phase is
not ready yet.
