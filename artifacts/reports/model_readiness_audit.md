# SnapKnow Model Readiness Audit

Base audited: `origin/main` at `37aa2ae`

This audit is limited to the model/export stage. It does not claim runtime
completion for Android or macOS.

## Branch sources reviewed

| Model family | Source branch | Commit | Readiness |
|---|---|---|---|
| Face embedding | `codex/model-face-embedding` | `8606d09` | Legacy artifact present, portable `.pte` still missing |
| Object detection | `codex/model-object-detection` | `a862eb7` | Artifact-ready in `tflite` form |
| STT (Whisper) | `codex/model-stt-whisper` | `c19a55e` | Contract defined, artifacts missing |
| TTS (Piper) | `codex/model-tts-piper` | `0eb5593` | Contract defined, assets missing |
| VLM | `codex/model-vlm` | `c154c78` | Manifest only |
| LLM chat | `codex/model-llm-chat` | `47fc93f` | Planned contract only |

## What is genuinely usable today

- `app/src/main/assets/fssd_25_8bit_v2.tflite`
- `app/src/main/assets/face_embedding.pt`

## What is defined but not yet exported

- `artifacts/android/face_embedding_xnnpack.pte`
- `artifacts/android/face_embedding_qnn.pte`
- `artifacts/shared/whisper/whisper_encoder.pte`
- `artifacts/shared/whisper/whisper_decoder.pte`
- `artifacts/shared/piper/en_US-lessac-medium.onnx`
- `artifacts/shared/piper/en_US-lessac-medium.onnx.json`
- `artifacts/shared/vlm/vision_encoder.pte`
- `artifacts/shared/vlm/projector.pte`
- `artifacts/shared/vlm/decoder.pte`
- `artifacts/shared/llm-chat/decoder.pte`

## Main takeaway

The repo now has a much clearer artifact contract for every model family, but
only object detection is truly artifact-ready and face embedding only has a
legacy fallback artifact. Whisper, Piper, VLM, and chat LLM are still at the
manifest or staged-contract phase.
