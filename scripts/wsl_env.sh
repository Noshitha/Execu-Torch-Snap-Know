#!/usr/bin/env bash
# =============================================================================
# wsl_env.sh  —  Paths matched to YOUR actual D:\SnapOn folder
#
# Usage:  source /mnt/d/SnapOn/scripts/wsl_env.sh
# =============================================================================

# ── Project root ──────────────────────────────────────────────────────────────
export SNAPON="/mnt/d/SnapOn"

# ── ExecuTorch ────────────────────────────────────────────────────────────────
# You have executorch-release-1.3 in D:\SnapOn
# We copy it to Linux home (~/executorch) for fast build — done by wsl_first_run.sh
export ET_HOME="$HOME/executorch"

# ── QNN SDK ───────────────────────────────────────────────────────────────────
# Your QNN folder: D:\SnapOn\v2.47.0.260601\
# In WSL2 that is: /mnt/d/SnapOn/v2.47.0.260601
export QNN_SDK_ROOT="/mnt/d/SnapOn/v2.47.0.260601"

# Set QNN library paths for the build
if [[ -d "$QNN_SDK_ROOT" ]]; then
    export LD_LIBRARY_PATH="${QNN_SDK_ROOT}/lib/x86_64-linux-clang:${LD_LIBRARY_PATH:-}"
    export PATH="${QNN_SDK_ROOT}/bin/x86_64-linux-clang:$PATH"
fi

# ── Android NDK ───────────────────────────────────────────────────────────────
# NOTE: android-ndk-r27d-WINDOWS in D:\SnapOn won't work inside WSL2
# (Windows .exe binaries can't run in Linux). The Linux NDK is installed
# into ~/android-sdk/ndk/ by wsl_first_run.sh via sdkmanager.
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_NDK_ROOT="$ANDROID_HOME/ndk/27.2.12479018"   # r27d Linux version
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# ── Python venv ───────────────────────────────────────────────────────────────
# Venv lives on Linux FS (not /mnt/d) — symlinks don't work on Windows NTFS
export VENV_DIR="$HOME/snapknow-venv"
if [[ -f "$VENV_DIR/bin/activate" ]]; then
    source "$VENV_DIR/bin/activate"
fi

# ── Status check ──────────────────────────────────────────────────────────────
echo "✓ SnapOn environment loaded"
echo "  QNN SDK  : $QNN_SDK_ROOT  $([ -d "$QNN_SDK_ROOT" ] && echo '✓ FOUND' || echo '✗ NOT FOUND')"
echo "  NDK      : $ANDROID_NDK_ROOT  $([ -d "$ANDROID_NDK_ROOT" ] && echo '✓ FOUND' || echo '(will install)')"
echo "  ExecuTorch: $ET_HOME  $([ -d "$ET_HOME" ] && echo '✓ FOUND' || echo '(will copy from D:\\SnapOn\\executorch-release-1.3)')"
