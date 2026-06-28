#!/usr/bin/env bash
# =============================================================================
# build_android_qnn.sh
# Builds ExecuTorch for Android arm64 with the Qualcomm QNN backend delegate.
# Produces:
#   - app/libs/executorch.aar       ← Java/Kotlin API + native libs
#   - app/libs/arm64-v8a/libexecutorch*.so  ← pre-extracted .so files
#
# Run AFTER setup_executorch.sh.
#
# Usage:
#   export ANDROID_NDK_ROOT=/path/to/android-ndk-r26c
#   export QNN_SDK_ROOT=/path/to/qairt/2.xx.x.xxxxxxxx
#   bash scripts/build_android_qnn.sh
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
ET_DIR="$REPO_ROOT/../executorch"
BUILD_DIR="$ET_DIR/build-android"
AAR_DEST="$REPO_ROOT/app/libs"

# ── Validate ──────────────────────────────────────────────────────────────────
[[ -z "${ANDROID_NDK_ROOT:-}" ]] && { echo "ERROR: Set ANDROID_NDK_ROOT"; exit 1; }
[[ ! -d "$ET_DIR" ]] && { echo "ERROR: Run setup_executorch.sh first"; exit 1; }

echo "============================================================"
echo " SnapKnow · Build ExecuTorch Android (QNN)"
echo "============================================================"
echo " NDK   : $ANDROID_NDK_ROOT"
echo " QNN   : ${QNN_SDK_ROOT:-NOT SET — building without QNN}"
echo " Build : $BUILD_DIR"
echo ""

# ── Configure cmake ───────────────────────────────────────────────────────────
echo "[1/3] CMake configure …"
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

QNN_FLAGS=""
if [[ -n "${QNN_SDK_ROOT:-}" ]]; then
    QNN_FLAGS="-DEXECUTORCH_BUILD_QNN=ON \
               -DQNN_SDK_ROOT=$QNN_SDK_ROOT"
    # Add QNN libs to LD_LIBRARY_PATH so linking works
    export LD_LIBRARY_PATH="${QNN_SDK_ROOT}/lib/aarch64-android:${LD_LIBRARY_PATH:-}"
fi

cmake "$ET_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-26 \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DEXECUTORCH_BUILD_ANDROID_JNI=ON \
    -DEXECUTORCH_BUILD_XNNPACK=ON \
    -DEXECUTORCH_BUILD_EXTENSION_MODULE=ON \
    -DEXECUTORCH_BUILD_EXTENSION_DATA_LOADER=ON \
    $QNN_FLAGS \
    -GNinja

echo "[2/3] Build …"
cmake --build . --parallel "$(nproc)"

# ── Package AAR ───────────────────────────────────────────────────────────────
echo "[3/3] Packaging AAR …"
cd "$ET_DIR"

# ExecuTorch provides a Gradle script to assemble the AAR
if [[ -f "build/android/gradlew" ]]; then
    bash build/android/gradlew -p build/android assembleRelease
    AAR_SRC="$(find build/android -name '*.aar' | head -1)"
else
    # Fallback: run the ExecuTorch Android build script directly
    bash "$ET_DIR/extension/android/build.sh" \
        --ndk "$ANDROID_NDK_ROOT" \
        --output "$BUILD_DIR/executorch-android"
    AAR_SRC="$(find "$BUILD_DIR/executorch-android" -name '*.aar' | head -1)"
fi

if [[ -f "$AAR_SRC" ]]; then
    mkdir -p "$AAR_DEST"
    cp "$AAR_SRC" "$AAR_DEST/executorch.aar"
    echo ""
    echo "============================================================"
    echo " ✓ AAR copied to app/libs/executorch.aar"
    echo " Next steps:"
    echo "   1. python model_tools/export_face_embedding.py --qnn --verify"
    echo "   2. bash scripts/deploy_to_s25.sh"
    echo "============================================================"
else
    echo ""
    echo "ERROR: Could not find compiled .aar file."
    echo "Check build logs above and re-run."
    exit 1
fi
