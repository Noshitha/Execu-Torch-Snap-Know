#!/usr/bin/env bash
# =============================================================================
# deploy_to_s25.sh
# Builds the APK and deploys it to a connected Samsung Galaxy S25 Ultra.
#
# Prerequisites:
#   • Android Studio or standalone SDK command-line tools installed
#   • S25 Ultra connected via USB with USB debugging enabled
#   • app/libs/executorch.aar present (from build_android_qnn.sh)
#   • app/src/main/assets/face_embedding.pte present (from export_face_embedding.py)
#
# Usage:
#   bash scripts/deploy_to_s25.sh
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
APK_PATH="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="com.snapknow.app"
ACTIVITY=".MainActivity"

echo "============================================================"
echo " SnapKnow · Deploy to Samsung Galaxy S25 Ultra"
echo "============================================================"

# ── Check ADB ──────────────────────────────────────────────────────────────
if ! command -v adb &>/dev/null; then
    echo "ERROR: adb not found. Add Android SDK platform-tools to PATH."
    exit 1
fi

DEVICE=$(adb devices | grep -v "List of" | grep "device$" | head -1 | awk '{print $1}')
if [[ -z "$DEVICE" ]]; then
    echo "ERROR: No Android device connected."
    echo "       Enable USB debugging on the S25 Ultra and connect via USB."
    exit 1
fi
echo " Device: $DEVICE"

MODEL=$(adb -s "$DEVICE" shell getprop ro.product.model | tr -d '\r')
echo " Model : $MODEL"
if [[ "$MODEL" != *"S25"* && "$MODEL" != *"SM-S93"* ]]; then
    echo " WARNING: Expected Galaxy S25 series, got '$MODEL'. Continuing anyway."
fi

# ── Check assets ──────────────────────────────────────────────────────────────
ASSETS_DIR="$REPO_ROOT/app/src/main/assets"
if [[ ! -f "$ASSETS_DIR/face_embedding.pte" ]]; then
    echo ""
    echo "WARNING: face_embedding.pte not found in assets/."
    echo "         The app will run in detection-only mode (no face recognition)."
    echo "         To enable face recognition:"
    echo "           python model_tools/export_face_embedding.py --qnn"
    echo ""
fi

# ── Build APK ─────────────────────────────────────────────────────────────────
echo "[1/3] Building debug APK …"
cd "$REPO_ROOT"
if [[ -f "./gradlew" ]]; then
    ./gradlew assembleDebug
elif [[ -f "gradlew" ]]; then
    bash gradlew assembleDebug
else
    echo "ERROR: gradlew not found. Open in Android Studio and run 'Build > Build APK'."
    exit 1
fi
echo "      APK built: $APK_PATH"

# ── Install ───────────────────────────────────────────────────────────────────
echo "[2/3] Installing APK on $DEVICE …"
adb -s "$DEVICE" install -r "$APK_PATH"
echo "      Installed"

# ── Launch ────────────────────────────────────────────────────────────────────
echo "[3/3] Launching SnapKnow …"
adb -s "$DEVICE" shell am start -n "${PACKAGE}/${ACTIVITY}"

echo ""
echo "============================================================"
echo " ✓ SnapKnow is running on $MODEL!"
echo ""
echo " Demo checklist:"
echo "   1. Grant Camera + Microphone permissions on first launch"
echo "   2. Point camera at an object, say 'I'm keeping my keys on the table'"
echo "   3. Look away, say 'Where are my keys?'"
echo "   4. Point camera at a person → tap REMEMBER FACE → say their name"
echo "   5. Next time you see them, it auto-announces their name!"
echo "============================================================"
