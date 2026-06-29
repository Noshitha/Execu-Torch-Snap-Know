#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_ROOT="${ROOT_DIR}/artifacts"
PYTHON_BIN="${PYTHON_BIN:-python3.11}"

mkdir -p \
  "${ARTIFACT_ROOT}/android" \
  "${ARTIFACT_ROOT}/macos" \
  "${ARTIFACT_ROOT}/shared/whisper" \
  "${ARTIFACT_ROOT}/shared/piper" \
  "${ARTIFACT_ROOT}/reports"

echo "SnapKnow model set build scaffold"
echo "  root:      ${ROOT_DIR}"
echo "  artifacts: ${ARTIFACT_ROOT}"

cat <<'EOF'
This script standardizes artifact output locations before device integration.

Expected outputs:
  artifacts/android/face_embedding_xnnpack.pte
  artifacts/android/face_embedding_qnn.pte
  artifacts/shared/whisper/whisper_encoder.pte
  artifacts/shared/whisper/whisper_decoder.pte
  artifacts/shared/piper/en_US-lessac-medium.onnx
  artifacts/shared/piper/en_US-lessac-medium.onnx.json

Recommended next steps:
  1. source .venv-models/bin/activate
  2. python export_face_embedding.py --output artifacts/android/face_embedding_xnnpack.pte --verify
  3. python export_face_embedding.py --output artifacts/android/face_embedding_qnn.pte --qnn --verify
  4. python export_whisper_tiny.py --out_dir artifacts/shared/whisper
  5. bash scripts/stage_speech_assets.sh ... and copy Piper assets into artifacts/shared/piper
  6. python scripts/validate_model_artifacts.py
EOF
