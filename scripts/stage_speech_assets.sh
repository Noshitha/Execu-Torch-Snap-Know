#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

WHISPER_DIR="$REPO_ROOT/app/src/main/assets/speech/stt/whisper-tiny"
PIPER_DIR="$REPO_ROOT/app/src/main/assets/speech/tts/piper/en_US-lessac-medium"

WHISPER_ENCODER=""
WHISPER_DECODER=""
PIPER_MODEL=""
PIPER_CONFIG=""

usage() {
    cat <<'EOF'
Usage:
  bash scripts/stage_speech_assets.sh \
    [--whisper-encoder /path/to/whisper_encoder.pte] \
    [--whisper-decoder /path/to/whisper_decoder.pte] \
    [--piper-model /path/to/en_US-lessac-medium.onnx] \
    [--piper-config /path/to/en_US-lessac-medium.onnx.json]

Copies supplied speech assets into the Android asset layout expected by the app.
EOF
}

copy_asset() {
    local src="$1"
    local dst="$2"
    mkdir -p "$(dirname "$dst")"
    cp "$src" "$dst"
    echo "staged: $dst"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --whisper-encoder)
            WHISPER_ENCODER="$2"
            shift 2
            ;;
        --whisper-decoder)
            WHISPER_DECODER="$2"
            shift 2
            ;;
        --piper-model)
            PIPER_MODEL="$2"
            shift 2
            ;;
        --piper-config)
            PIPER_CONFIG="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

mkdir -p "$WHISPER_DIR" "$PIPER_DIR"

if [[ -n "$WHISPER_ENCODER" ]]; then
    copy_asset "$WHISPER_ENCODER" "$WHISPER_DIR/whisper_encoder.pte"
fi

if [[ -n "$WHISPER_DECODER" ]]; then
    copy_asset "$WHISPER_DECODER" "$WHISPER_DIR/whisper_decoder.pte"
fi

if [[ -n "$PIPER_MODEL" ]]; then
    copy_asset "$PIPER_MODEL" "$PIPER_DIR/en_US-lessac-medium.onnx"
fi

if [[ -n "$PIPER_CONFIG" ]]; then
    copy_asset "$PIPER_CONFIG" "$PIPER_DIR/en_US-lessac-medium.onnx.json"
fi

bash "$SCRIPT_DIR/preflight_speech_assets.sh"
