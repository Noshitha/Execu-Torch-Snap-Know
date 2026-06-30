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
        return 1
    fi
}

echo "Piper asset preflight:"
check_file "$REPO_ROOT/app/src/main/assets/speech/tts/piper/manifest.json"
check_file "$REPO_ROOT/app/src/main/assets/speech/tts/piper/en_US-lessac-medium/en_US-lessac-medium.onnx"
check_file "$REPO_ROOT/app/src/main/assets/speech/tts/piper/en_US-lessac-medium/en_US-lessac-medium.onnx.json"
