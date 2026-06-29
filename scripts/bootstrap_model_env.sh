#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV_DIR="${ROOT_DIR}/.venv-models"
PYTHON_BIN="${PYTHON_BIN:-python3.11}"

echo "SnapKnow model environment bootstrap"
echo "  root:   ${ROOT_DIR}"
echo "  python: ${PYTHON_BIN}"
echo "  venv:   ${VENV_DIR}"

if ! command -v "${PYTHON_BIN}" >/dev/null 2>&1; then
  echo "ERROR: ${PYTHON_BIN} not found. Install Python 3.11 before building model artifacts." >&2
  exit 1
fi

"${PYTHON_BIN}" -m venv "${VENV_DIR}"
source "${VENV_DIR}/bin/activate"

python -m pip install --upgrade pip wheel setuptools
python -m pip install -r "${ROOT_DIR}/requirements-dev.txt"

cat <<EOF

Model environment ready.
Activate it with:
  source "${VENV_DIR}/bin/activate"

Next recommended step:
  python scripts/validate_model_artifacts.py
EOF
