#!/usr/bin/env bash
# =============================================================================
# wsl_first_run.sh  —  Works on Kali Linux + Ubuntu WSL2
# Paths matched to D:\SnapOn with your exact downloaded files:
#   ✓ executorch-release-1.3\
#   ✓ v2.47.0.260601\  (QNN SDK)
#   ✓ android-ndk-r27d-windows\  (Windows version — Linux NDK installed separately)
# =============================================================================
set -euo pipefail

SNAPON="/mnt/d/SnapOn"
ET_SRC="$SNAPON/executorch-release-1.3"
ET_HOME="$HOME/executorch"
QNN_SDK_ROOT="$SNAPON/v2.47.0.260601"
ANDROID_HOME="$HOME/android-sdk"
VENV_DIR="$HOME/snapknow-venv"          # ← Linux FS, NOT Windows mount (symlinks work here)
LIBS_DIR="$SNAPON/app/libs"
ASSETS_DIR="$SNAPON/app/src/main/assets"
JOBS=$(nproc)

# Suppress apt service-restart dialogs (harmless in WSL2)
export DEBIAN_FRONTEND=noninteractive

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
ok()   { echo -e "${GREEN}✓ $*${NC}"; }
warn() { echo -e "${YELLOW}⚠ $*${NC}"; }
die()  { echo -e "${RED}✗ ERROR: $*${NC}"; echo "Check /mnt/d/SnapOn/build.log for details"; exit 1; }

echo ""
echo "╔══════════════════════════════════════════════════════════╗"
echo "║            SnapOn · Full WSL2 First-Time Setup          ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo "  ExecuTorch src : $ET_SRC"
echo "  ExecuTorch dst : $ET_HOME"
echo "  QNN SDK        : $QNN_SDK_ROOT"
echo "  CPU threads    : $JOBS"
echo ""

# Guards
[[ -d "$ET_SRC" ]]       || die "executorch-release-1.3 not found at $ET_SRC"
[[ -d "$QNN_SDK_ROOT" ]] || die "QNN SDK not found at $QNN_SDK_ROOT"

# ── STEP 1: System packages ───────────────────────────────────────────────────
echo "━━━ STEP 1/6 — System packages ━━━"

# Detect distro
DISTRO=$(grep -i '^ID=' /etc/os-release 2>/dev/null | cut -d= -f2 | tr -d '"' | tr '[:upper:]' '[:lower:]')
echo "  Detected distro: $DISTRO"

# Prevent needrestart from prompting about service restarts in WSL2
export NEEDRESTART_MODE=a
export NEEDRESTART_SUSPEND=1

export NEEDRESTART_MODE=a
export NEEDRESTART_SUSPEND=1

apt-get update -qq

# --no-upgrade: install NEW packages only, never upgrade existing ones.
# This is critical: upgrading libc6/libc-bin while bash is running
# kills the running script (WSL2 issue). We only need new packages anyway.
# clang/lld are intentionally omitted — the Android NDK ships its own clang.
apt-get install -y --no-upgrade \
    git \
    cmake \
    ninja-build \
    python3 \
    python3-venv \
    python3-pip \
    unzip \
    wget \
    curl \
    build-essential \
    pkg-config \
    libssl-dev \
    zlib1g-dev \
    patchelf

# Java — try JDK 17 first, fall back to default-jdk
if apt-get install -y --no-upgrade openjdk-17-jdk 2>/dev/null; then
    ok "openjdk-17-jdk installed"
elif apt-get install -y --no-upgrade default-jdk 2>/dev/null; then
    ok "default-jdk installed"
else
    warn "Could not install Java — Android build may fail later"
fi

PYTHON_CMD="python3"

ok "System packages done"
echo "  Python: $($PYTHON_CMD --version)"
echo "  CMake : $(cmake --version | head -1)"

# ── STEP 2: Android SDK + NDK r27d (Linux) ───────────────────────────────────
echo ""
echo "━━━ STEP 2/6 — Android NDK r27d (Linux) ━━━"
mkdir -p "$ANDROID_HOME/cmdline-tools"

if [[ ! -f "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
    warn "Downloading Android command-line tools (~150 MB) …"
    wget -q --show-progress \
        "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" \
        -O /tmp/cmdtools.zip
    unzip -q /tmp/cmdtools.zip -d /tmp/cmdtools_extract
    mv /tmp/cmdtools_extract/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
    rm /tmp/cmdtools.zip
    ok "Android command-line tools installed"
else
    ok "Already installed — skipping"
fi

export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
yes | sdkmanager --licenses > /dev/null 2>&1 || true

NDK_VER="27.2.12479018"
if [[ ! -d "$ANDROID_HOME/ndk/$NDK_VER" ]]; then
    warn "Installing Android NDK r27d Linux (~700 MB) …"
    sdkmanager "ndk;$NDK_VER" "platform-tools"
    ok "NDK r27d installed"
else
    ok "NDK already installed"
fi

export ANDROID_NDK_ROOT="$ANDROID_HOME/ndk/$NDK_VER"
[[ -d "$ANDROID_NDK_ROOT" ]] || die "NDK not found after install: $ANDROID_NDK_ROOT"
ok "NDK ready: $ANDROID_NDK_ROOT"

# ── STEP 3: Copy ExecuTorch to Linux FS + submodules ─────────────────────────
echo ""
echo "━━━ STEP 3/6 — Prepare ExecuTorch ━━━"

if [[ ! -d "$ET_HOME" ]]; then
    warn "Copying executorch-release-1.3 to Linux filesystem (~few minutes) …"
    cp -r "$ET_SRC" "$ET_HOME"
    ok "Copied to $ET_HOME"
else
    ok "Already exists at $ET_HOME"
fi

cd "$ET_HOME"

# Make sure it's a proper git repo
if [[ ! -d "$ET_HOME/.git" ]]; then
    warn "Initialising git repo for submodule support …"
    git init -q
    git remote add origin https://github.com/pytorch/executorch.git 2>/dev/null || true
fi

warn "Downloading git submodules (~1.5 GB, takes 10-20 min) …"
git submodule sync --recursive 2>/dev/null || true
git submodule update --init --recursive
ok "Submodules ready"

# ── STEP 4: Python venv + packages ───────────────────────────────────────────
echo ""
echo "━━━ STEP 4/6 — Python environment + model export ━━━"

mkdir -p "$SNAPON/model_tools"

# Create requirements.txt if missing
if [[ ! -f "$SNAPON/model_tools/requirements.txt" ]]; then
    warn "requirements.txt not found — creating it …"
    cat > "$SNAPON/model_tools/requirements.txt" << 'EOF'
torch>=2.4.0
torchvision>=0.19.0
facenet-pytorch>=2.6.0
numpy>=1.24.0
Pillow>=10.0.0
onnx>=1.16.0
EOF
    ok "requirements.txt created"
fi

# Create venv on LINUX filesystem (not /mnt/d — symlinks don't work on Windows NTFS)
if [[ ! -f "$VENV_DIR/bin/activate" ]]; then
    warn "Creating Python venv at $VENV_DIR (Linux FS) …"
    $PYTHON_CMD -m venv "$VENV_DIR"
fi
source "$VENV_DIR/bin/activate"

# Verify we're inside the venv before pip-ing
ACTIVE_PYTHON=$(which python3)
[[ "$ACTIVE_PYTHON" == "$VENV_DIR"* ]] || die "venv activation failed — python is at $ACTIVE_PYTHON, expected inside $VENV_DIR"
ok "venv active: $ACTIVE_PYTHON"

pip install --upgrade pip wheel setuptools -q

warn "Installing Python packages — torch, facenet-pytorch (~2 GB, 10-20 min) …"
pip install -r "$SNAPON/model_tools/requirements.txt"

cd "$ET_HOME"
[[ -f requirements.txt ]] && pip install -r requirements.txt -q || warn "No ET requirements.txt — skipping"
[[ -f requirements-examples.txt ]] && pip install -r requirements-examples.txt -q || true
[[ -f setup.py ]] || [[ -f pyproject.toml ]] && pip install --no-deps -e . -q || warn "No ET setup.py — skipping"
ok "Python environment ready"

# Export face embedding model
warn "Exporting MobileFaceNet → face_embedding.pte …"
mkdir -p "$ASSETS_DIR"
cd "$SNAPON"

# Set QNN lib path for export
export LD_LIBRARY_PATH="${QNN_SDK_ROOT}/lib/x86_64-linux-clang:${LD_LIBRARY_PATH:-}"

python3 model_tools/export_face_embedding.py \
    --output "$ASSETS_DIR/face_embedding.pte" \
    --qnn \
    --verify \
    && ok "face_embedding.pte exported" \
    || warn "Model export failed — app still works in camera-only mode"

# ── STEP 5: Build ExecuTorch Android AAR ─────────────────────────────────────
echo ""
echo "━━━ STEP 5/6 — Build ExecuTorch AAR (20-40 min) ━━━"

BUILD_DIR="$ET_HOME/build-android"
mkdir -p "$BUILD_DIR"
cd "$ET_HOME"

export LD_LIBRARY_PATH="${QNN_SDK_ROOT}/lib/x86_64-linux-clang:${LD_LIBRARY_PATH:-}"

warn "Running CMake configure …"
cmake -B "$BUILD_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-26 \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DEXECUTORCH_BUILD_ANDROID_JNI=ON \
    -DEXECUTORCH_BUILD_XNNPACK=ON \
    -DEXECUTORCH_BUILD_EXTENSION_MODULE=ON \
    -DEXECUTORCH_BUILD_EXTENSION_DATA_LOADER=ON \
    -DEXECUTORCH_BUILD_QNN=ON \
    -DQNN_SDK_ROOT="$QNN_SDK_ROOT" \
    -GNinja \
    -DCMAKE_MAKE_PROGRAM=ninja

ok "CMake configure done"

warn "Building ($JOBS threads) — grab a coffee ☕ …"
cmake --build "$BUILD_DIR" --parallel "$JOBS"
ok "Build complete"

# ── STEP 6: Package AAR ───────────────────────────────────────────────────────
echo ""
echo "━━━ STEP 6/6 — Package AAR ━━━"
mkdir -p "$LIBS_DIR"

# Try official packaging
AAR_SRC=""
if [[ -f "$ET_HOME/extension/android/build_android_library.sh" ]]; then
    bash "$ET_HOME/extension/android/build_android_library.sh" \
        --build-dir "$BUILD_DIR" --output-dir "$BUILD_DIR/aar" 2>/dev/null || true
    AAR_SRC=$(find "$BUILD_DIR/aar" -name "*.aar" 2>/dev/null | head -1 || true)
fi

# Manual AAR if official script didn't produce one
if [[ -z "$AAR_SRC" || ! -f "$AAR_SRC" ]]; then
    warn "Building AAR manually …"
    AAR_STAGE="$BUILD_DIR/aar_stage"
    rm -rf "$AAR_STAGE" && mkdir -p "$AAR_STAGE/jni/arm64-v8a"

    find "$BUILD_DIR" -name "libexecutorch*.so" -exec cp {} "$AAR_STAGE/jni/arm64-v8a/" \; 2>/dev/null || true
    find "$BUILD_DIR" -name "libextension*.so"  -exec cp {} "$AAR_STAGE/jni/arm64-v8a/" \; 2>/dev/null || true
    find "$BUILD_DIR" -name "libqnn*.so"        -exec cp {} "$AAR_STAGE/jni/arm64-v8a/" \; 2>/dev/null || true

    cat > "$AAR_STAGE/AndroidManifest.xml" << 'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.pytorch.executorch"/>
XML
    AAR_SRC="$BUILD_DIR/executorch.aar"
    cd "$AAR_STAGE" && zip -r "$AAR_SRC" . > /dev/null
    cd "$ET_HOME"
fi

cp "$AAR_SRC" "$LIBS_DIR/executorch.aar"
ok "executorch.aar → $LIBS_DIR/executorch.aar"

# ── Final summary ──────────────────────────────────────────────────────────────
echo ""
echo "╔══════════════════════════════════════════════════════════╗"
echo "║                   ALL DONE! 🎉                          ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""
[[ -f "$LIBS_DIR/executorch.aar"       ]] \
    && ok "D:\\SnapOn\\app\\libs\\executorch.aar  ($(du -sh $LIBS_DIR/executorch.aar | cut -f1))" \
    || warn "executorch.aar MISSING"

[[ -f "$ASSETS_DIR/face_embedding.pte" ]] \
    && ok "D:\\SnapOn\\app\\src\\main\\assets\\face_embedding.pte  ($(du -sh $ASSETS_DIR/face_embedding.pte | cut -f1))" \
    || warn "face_embedding.pte MISSING — run: python3 model_tools/export_face_embedding.py --qnn"

echo ""
echo "  Next steps on Windows:"
echo "  1. Install Android Studio (android-studio-quail1-patch2-windows.exe)"
echo "  2. Open D:\\SnapOn in Android Studio"
echo "  3. Build → Build APK(s)"
echo "  4. Plug in S25 Ultra → Run"
echo ""
