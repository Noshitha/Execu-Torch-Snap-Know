#!/bin/zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

if [[ ! -x "$ROOT_DIR/.venv/bin/python" ]]; then
  echo "Missing virtual environment at $ROOT_DIR/.venv"
  echo "Create it with: python3 -m venv .venv && .venv/bin/pip install qai_hub_models"
  exit 1
fi

mkdir -p "$ROOT_DIR/.cache" "$ROOT_DIR/.qaihm" "$ROOT_DIR/export_assets"

export TORCH_HOME="$ROOT_DIR/.cache/torch"
export XDG_CACHE_HOME="$ROOT_DIR/.cache"
export HF_HOME="$ROOT_DIR/.cache/huggingface"
export QAIHM_STORE_ROOT="$ROOT_DIR"

"$ROOT_DIR/.venv/bin/python" -m qai_hub_models.models.resnet18.export \
  --target-runtime qnn_dlc \
  --output-dir "$ROOT_DIR/export_assets" \
  "$@"
