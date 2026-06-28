#!/usr/bin/env bash
# =============================================================================
# setup_executorch.sh
# Sets up the ExecuTorch environment from scratch on Linux x86_64.
# Run this ONCE before building the Android AAR.
#
# Prerequisites (install manually first):
#   • Python 3.10–3.13
#   • Android NDK r26c  (export ANDROID_NDK_ROOT before running)
#   • Qualcomm AI Engine Direct SDK 2.37+  (export QNN_SDK_ROOT before running)
#   • git, cmake 3.22+, ninja
#
# Usage:
#   export ANDROID_NDK_ROOT=/path/to/android-ndk-r26c
#   export QNN_SDK_ROOT=/path/to/qairt/2.xx.x.xxxxxxxx
#   bash scripts/setup_executorch.sh
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
ET_DIR="$REPO_ROOT/../executorch"   # sibling directory

# ── Validate env vars ─────────────────────────────────────────────────────────
if [[ -z "${ANDROID_NDK_ROOT:-}" ]]; then
    echo "ERROR: Set ANDROID_NDK_ROOT before running this script."
    echo "       export ANDROID_NDK_ROOT=/path/to/android-ndk-r26c"
    exit 1
fi

echo "============================================================"
echo " SnapKnow · ExecuTorch Setup"
echo "============================================================"
echo " Android NDK : $ANDROID_NDK_ROOT"
echo " QNN SDK     : ${QNN_SDK_ROOT:-NOT SET (CPU/XNNPACK only)}"
echo " ExecuTorch  : $ET_DIR"
echo ""

# ── Clone ExecuTorch release/1.3 ──────────────────────────────────────────────
if [[ ! -d "$ET_DIR" ]]; then
    echo "[1/4] Cloning ExecuTorch release/1.3 …"
    git clone \
        --branch release/1.3 \
        --depth 1 \
        https://github.com/pytorch/executorch.git \
        "$ET_DIR"
    echo "      Cloned to $ET_DIR"
else
    echo "[1/4] ExecuTorch already cloned at $ET_DIR — skipping"
fi

# ── Submodules ─────────────────────────────────────────────────────────────────
echo "[2/4] Updating submodules …"
cd "$ET_DIR"
git submodule sync --recursive
git submodule update --init --recursive 2>&1 | tail -20
echo "      Done"

# ── Python deps ───────────────────────────────────────────────────────────────
echo "[3/4] Installing Python dependencies …"
pip install --upgrade pip
pip install -r requirements.txt
pip install -r requirements-examples.txt
pip install -e .
echo "      Python deps installed"

# ── Model tool deps ───────────────────────────────────────────────────────────
echo "[4/4] Installing model conversion deps …"
cd "$REPO_ROOT"
pip install -r model_tools/requirements.txt
echo "      Done"

echo ""
echo "============================================================"
echo " Setup complete!"
echo " Next step:  bash scripts/build_android_qnn.sh"
echo "============================================================"
