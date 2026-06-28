# SnapKnow Development Setup Guide

Complete guide to set up the development environment and build the SnapKnow app.

## Table of Contents

1. [Quick Start](#quick-start)
2. [System Requirements](#system-requirements)
3. [Full Setup](#full-setup)
4. [Building Models](#building-models)
5. [Building Android App](#building-android-app)
6. [Deploying to Device](#deploying-to-device)
7. [Troubleshooting](#troubleshooting)

---

## Quick Start

If you just want to build and run the app:

```bash
# 1. Clone the repo
git clone https://github.com/Noshitha/Execu-Torch-Snap-Know.git
cd Execu-Torch-Snap-Know

# 2. Set up Python environment
bash setup.sh

# 3. Build and deploy
make build-apk install-apk
```

That's it! The app should now be running on your device.

---

## System Requirements

### macOS / Linux / WSL

- **Python**: 3.10 or 3.11 (not 3.12+, due to torch.export limitations)
- **Java/JDK**: 17+ (for Gradle/Android build)
- **Android SDK**: API 34 (auto-downloaded by Gradle)
- **NDK**: arm64-v8a support (auto-downloaded by Gradle)
- **Git**: For version control
- **adb**: For device deployment (`brew install android-platform-tools` on macOS)

### Hardware

- **RAM**: 8 GB minimum (16 GB+ recommended for model building)
- **Disk Space**: 20 GB minimum (models + build artifacts)
- **Device**: Android 8.0+ (API 26+)

---

## Full Setup

### 1. Clone Repository

```bash
git clone https://github.com/Noshitha/Execu-Torch-Snap-Know.git
cd Execu-Torch-Snap-Know
```

### 2. Verify Requirements

Check you have all prerequisites:

```bash
# Python
python3 --version  # Should be 3.10+

# Java
java -version      # Should be 17+

# Git
git --version

# ADB (for deployment)
adb version
```

### 3. Set Up Development Environment

**Option A: Automated (Recommended)**

```bash
bash setup.sh
```

This script will:
- ✓ Check Python version
- ✓ Create virtual environment (`venv/`)
- ✓ Install all Python dependencies from `requirements-dev.txt`
- ✓ Verify installation

**Option B: Manual**

```bash
# Create virtual environment
python3 -m venv venv
source venv/bin/activate

# Upgrade pip
pip install --upgrade pip setuptools wheel

# Install dependencies
pip install -r requirements-dev.txt
```

### 4. Verify Setup

```bash
# Activate venv (if not already active)
source venv/bin/activate

# Check installations
python -c "import torch; print(f'PyTorch: {torch.__version__}')"
python -c "import facenet_pytorch; print('facenet-pytorch: OK')"
python -c "import executorch; print('ExecuTorch: OK')"
```

---

## Building Models

### Models Included

| Model | Format | Size | Status | Speed |
|-------|--------|------|--------|-------|
| **face_embedding.pt** | PyTorch Mobile | 87 MB | ✓ Ready | ~100ms |
| face_embedding.pte | ExecuTorch (CPU) | ~90 MB | Build required | ~200ms |
| face_embedding_qnn.pte | ExecuTorch (NPU) | ~90 MB | Build required | ~20ms* |
| whisper_*.pte | ExecuTorch | ~50 MB | Build required | ~500ms |

*On Qualcomm Hexagon NPU devices

### Building PyTorch Mobile Model (Default)

```bash
# Activate venv
source venv/bin/activate

# Build
python rebuild_face_embedding_pt.py

# Output: app/src/main/assets/face_embedding.pt (87 MB)
```

Or using make:
```bash
make build-model
```

### Building ExecuTorch Models (Optional)

**CPU version (XNNPACK):**
```bash
python export_face_embedding.py \
    --output app/src/main/assets/face_embedding.pte \
    --verify

# Or with make:
make build-model-executorch
```

**NPU version (Qualcomm QNN):**
```bash
# Requires QNN SDK environment variables
export QNN_SDK_ROOT=/path/to/qnn/sdk

python export_face_embedding.py \
    --output app/src/main/assets/face_embedding_qnn.pte \
    --qnn \
    --verify

# Or with make:
make build-model-executorch-qnn
```

### Building Whisper Models (Experimental)

```bash
python export_whisper_tiny.py --out_dir app/src/main/assets/

# Or with make:
make build-model-whisper
```

**Status:** Currently experimental. App uses Android SpeechRecognizer instead.

---

## Building Android App

### Prerequisites

- Models built (see [Building Models](#building-models))
- Gradle wrapper included (`./gradlew`)
- Android SDK configured (Gradle will auto-download if missing)

### Build Debug APK

```bash
./gradlew :app:assembleDebug

# Output: app/build/outputs/apk/debug/app-debug.apk

# Or with make:
make build-apk
```

Expected build time: **2-5 minutes** (first build slower)

### Build Release APK

```bash
./gradlew :app:assembleRelease

# Output: app/build/outputs/apk/release/app-release.apk
# (Requires keystore for production signing)

# Or with make:
make build-release
```

### Build Troubleshooting

**"gradlew: command not found"**
```bash
chmod +x gradlew
./gradlew --version  # Should print Gradle version 8.4
```

**"No SDK found"**
```bash
./gradlew --version  # This triggers auto-setup
# Or manually set: export ANDROID_HOME=/path/to/android/sdk
```

---

## Deploying to Device

### Prerequisites

- Connected Android device (USB debugging enabled)
- `adb` installed and in PATH
- APK built (see [Building Android App](#building-android-app))

### Install & Run

```bash
# Connect device via USB
adb devices  # Should show your device

# Install APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch app
adb shell am start -n com.snapknow.app/com.snapknow.app.MainActivity

# Or with make:
make install-apk
```

### Monitor Logs

```bash
# Stream logs while app runs
adb logcat | grep "snapknow\|FaceEmbedding\|MainActivity"

# Or search logs for errors:
adb logcat | grep ERROR
```

### Verify On-Device

1. App should start with camera view
2. UI buttons visible: "Show saved faces", "Manage memories"
3. Face detection boxes appear when you point camera at a face
4. After ~3-5 seconds, model status shows "Model loaded"

---

## Directory Structure

```
Execu-Torch-Snap-Know/
├── app/
│   ├── src/main/
│   │   ├── assets/
│   │   │   ├── face_embedding.pt          ← Face recognition model (87 MB)
│   │   │   └── PUT_PTE_FILES_HERE.txt     ← Alternative models go here
│   │   ├── java/com/snapknow/app/
│   │   │   ├── MainActivity.kt
│   │   │   ├── MainViewModel.kt
│   │   │   ├── inference/
│   │   │   │   ├── FaceEmbeddingModel.kt  ← PyTorch Mobile wrapper
│   │   │   │   └── ExecuTorchModule.kt    ← ExecuTorch wrapper
│   │   │   ├── database/
│   │   │   └── ...
│   │   └── res/
│   ├── build.gradle.kts
│   └── ...
├── export_face_embedding.py       ← Build ExecuTorch models
├── export_whisper_tiny.py         ← Build Whisper models
├── rebuild_face_embedding_pt.py   ← Rebuild PyTorch model
├── requirements-dev.txt           ← Python dependencies
├── setup.sh                        ← Automated environment setup
├── Makefile                        ← Quick commands
├── SETUP.md                        ← This file
├── MODEL_BUILD_GUIDE.md           ← Detailed model info
├── gradlew                         ← Gradle wrapper
├── gradlew.bat                     ← Gradle wrapper (Windows)
└── README.md                       ← Project overview
```

---

## Environment Configuration

### Python Environment Variables

```bash
# Optional: Speed up PyTorch (set before importing)
export OMP_NUM_THREADS=4
export MKL_NUM_THREADS=4

# Optional: Use specific CUDA device (if GPU available)
export CUDA_VISIBLE_DEVICES=0
```

### Android Build Variables

```bash
# Optional: Set SDK location
export ANDROID_SDK_ROOT=/path/to/android/sdk
export ANDROID_HOME=$ANDROID_SDK_ROOT

# Optional: Set NDK location
export ANDROID_NDK_ROOT=/path/to/android/ndk

# Optional: Set Java home
export JAVA_HOME=/path/to/java
```

### ExecuTorch QNN Setup (Optional)

For NPU acceleration on Qualcomm devices:

```bash
# Set QNN SDK paths
export QNN_SDK_ROOT=/path/to/qnn/sdk
export PATH=$QNN_SDK_ROOT/bin:$PATH
```

---

## Troubleshooting

### Python Setup Issues

**"Python 3.10+ required"**
```bash
# Install Python 3.10 or 3.11
brew install python@3.11              # macOS
sudo apt install python3.11           # Linux
```

**"ModuleNotFoundError: No module named 'torch'"**
```bash
# Make sure venv is activated
source venv/bin/activate

# Reinstall dependencies
pip install -r requirements-dev.txt --force-reinstall
```

**"torch.export failed: Python 3.12+ not yet supported"**
```bash
# Use Python 3.11 or earlier
python3.11 -m venv venv
source venv/bin/activate
pip install -r requirements-dev.txt
```

### Model Building Issues

**"No module named 'executorch'"**
```bash
pip install executorch
```

**"FacenetPytorch failed to download"**
```bash
# Models download automatically on first use
# If stuck, try manually:
python -c "from facenet_pytorch import InceptionResnetV1; InceptionResnetV1(pretrained='vggface2')"
```

**"Out of memory while building model"**
```bash
# Reduce batch size or run on machine with more RAM
# Or build on Google Colab: https://colab.research.google.com
```

### Android Build Issues

**"Could not find gradle"**
```bash
# Ensure gradlew has execute permission
chmod +x gradlew

# Try again
./gradlew --version
```

**"Gradle sync failed"**
```bash
# Clean and retry
./gradlew clean
./gradlew :app:assembleDebug
```

**"Insufficient permissions for device"**
```bash
# Android device not responding properly
adb kill-server
adb start-server
adb devices
```

### Device Deployment Issues

**"adb not found"**
```bash
# Install Android tools
brew install android-platform-tools    # macOS
sudo apt install android-tools-adb    # Linux
```

**"Device not authorized"**
```bash
# On device: Check for USB debugging prompt
adb kill-server
adb start-server
adb devices   # Accept authorization on device if prompted
```

**"APK fails to install"**
```bash
# Uninstall previous version
adb uninstall com.snapknow.app

# Reinstall
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Getting Help

1. Check `MODEL_BUILD_GUIDE.md` for detailed model info
2. Read logs: `adb logcat | grep snapknow`
3. Review GitHub issues: https://github.com/Noshitha/Execu-Torch-Snap-Know/issues
4. Check `app/build/intermediates/` for build logs

---

## Next Steps

After successful deployment:

1. ✓ Test face detection by pointing camera at a face
2. ✓ Save a face by speaking a name/relation
3. ✓ Test face recognition by showing the same face again
4. ✓ Manage memories via "Manage memories" button

---

## Performance Tips

| Optimization | Impact | Difficulty |
|-------------|--------|-----------|
| Use ExecuTorch QNN | 10x faster | Hard (requires QNN SDK) |
| Use quantized model | 2x faster | Easy (already done) |
| Add more samples per person | Better accuracy | Easy |
| Optimize camera preview | Smoother UI | Medium |

---

## Support & Contributing

- **Issues**: https://github.com/Noshitha/Execu-Torch-Snap-Know/issues
- **Model improvements**: Suggest in issues
- **Performance optimizations**: Welcome!

---

Generated: 2024
Last Updated: 2024
