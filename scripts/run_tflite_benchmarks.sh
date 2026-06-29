#!/bin/zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MODELS_DIR="${1:-$ROOT_DIR/benchmark_models}"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
OUTPUT_ROOT="$ROOT_DIR/benchmark_results"
RUN_DIR="$OUTPUT_ROOT/$TIMESTAMP"
LATEST_DIR="$OUTPUT_ROOT/latest"

mkdir -p "$RUN_DIR" "$LATEST_DIR"

echo "Running TFLite benchmarks from: $MODELS_DIR"
echo "Saving outputs to: $RUN_DIR"

python3 "$ROOT_DIR/scripts/benchmark_tflite_models.py" \
  --models-dir "$MODELS_DIR" \
  --output-dir "$RUN_DIR" | tee "$RUN_DIR/terminal_output.txt"

cp "$RUN_DIR/results.json" "$LATEST_DIR/results.json"
cp "$RUN_DIR/results.csv" "$LATEST_DIR/results.csv"
cp "$RUN_DIR/results.md" "$LATEST_DIR/results.md"
cp "$RUN_DIR/terminal_output.txt" "$LATEST_DIR/terminal_output.txt"

echo
echo "Latest results mirrored to: $LATEST_DIR"
