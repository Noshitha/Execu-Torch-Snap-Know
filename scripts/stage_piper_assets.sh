#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
PIPER_DIR="$REPO_ROOT/app/src/main/assets/speech/tts/piper/en_US-lessac-medium"

PIPER_MODEL=""
PIPER_CONFIG=""

usage() {
    cat <<'EOF'
Usage:
  bash scripts/stage_piper_assets.sh \
    --piper-model /path/to/en_US-lessac-medium.onnx \
    --piper-config /path/to/en_US-lessac-medium.onnx.json

Copies the supplied Piper voice bundle into the Android asset layout expected by the app.
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

if [[ -z "$PIPER_MODEL" || -z "$PIPER_CONFIG" ]]; then
    echo "Both --piper-model and --piper-config are required." >&2
    usage >&2
    exit 1
fi

mkdir -p "$PIPER_DIR"
copy_asset "$PIPER_MODEL" "$PIPER_DIR/en_US-lessac-medium.onnx"
copy_asset "$PIPER_CONFIG" "$PIPER_DIR/en_US-lessac-medium.onnx.json"

bash "$SCRIPT_DIR/preflight_piper_assets.sh"
