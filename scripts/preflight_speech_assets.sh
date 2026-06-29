#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

check_file() {
    local path="$1"
    if [[ -f "$path" ]]; then
        echo "OK  $path"
    else
        echo "MISS $path"
    fi
}

echo "Speech asset preflight:"
check_file "$REPO_ROOT/app/src/main/assets/speech/manifest.json"
check_file "$REPO_ROOT/app/src/main/assets/speech/stt/whisper-tiny/whisper_encoder.pte"
check_file "$REPO_ROOT/app/src/main/assets/speech/stt/whisper-tiny/whisper_decoder.pte"
check_file "$REPO_ROOT/app/src/main/assets/speech/tts/piper/en_US-lessac-medium/en_US-lessac-medium.onnx"
check_file "$REPO_ROOT/app/src/main/assets/speech/tts/piper/en_US-lessac-medium/en_US-lessac-medium.onnx.json"
