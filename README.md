# SnapKnow

SnapKnow is an on-device memory assistant designed to help dementia patients
remember people, objects, and context using vision and voice, while keeping
data local on the device.

## What It Does

| Feature | How to use |
|---|---|
| Remember object location | Point the camera at an object and say where you are keeping it |
| Recall object location | Ask where an object is and hear the saved answer |
| Remember a person | Point the camera at a person, tap **Remember Face**, then say their name and relation |
| Recognize a person | Point the camera at a saved person and let the app identify them |
| Voice-based interaction | Use offline speech input to store or retrieve memories |
| Future scene understanding | Use VLM-based scene description or question answering once the full model bundle is ready |

## Architecture

```text
Camera
  ├─ ML Kit Face Detection
  ├─ Vision Language Model (planned full path)
  ├─ Object Detection
  └─ TTS / STT flows
          │
          ▼
     ExecuTorch
          │
          ▼
Room Database (SQLite)
          │
          ▼
Android TTS (offline)

Android SpeechRecognizer (offline)
          │
          ▼
CommandParser (regex NLP)
          │
          ▼
MemoryRepository
```

## Prerequisites

- Python 3.11 recommended for model export work
- Android Studio
- Android SDK / NDK
- Qualcomm AI Engine Direct SDK / QNN SDK
- Samsung Galaxy S25 Ultra with USB debugging enabled
- `cmake`
- `ninja`

## Setup

### Step 1: Set environment variables

```bash
export ANDROID_NDK_ROOT=/path/to/android-ndk
export QNN_SDK_ROOT=/path/to/qnn-sdk
export LD_LIBRARY_PATH=$QNN_SDK_ROOT/lib/x86_64-linux-clang:$LD_LIBRARY_PATH
```

### Step 2: Clone & set up ExecuTorch

```bash
bash scripts/setup_executorch.sh
```

### Step 3: Export the face embedding model

```bash
python export_face_embedding.py --output artifacts/android/face_embedding_qnn.pte --qnn --verify
```

### Step 4: Build ExecuTorch Android AAR with QNN backend

```bash
bash scripts/build_android_qnn.sh
```

### Step 5: Open in Android Studio & build APK

```bash
./gradlew :app:assembleDebug
```

### Step 6: Deploy to S25 Ultra

```bash
bash scripts/deploy_to_s25.sh
```

## Project Structure

```text
app/                      Android application
app/src/main/assets/      Runtime model assets used by the app
artifacts/                Off-device model manifests and validation reports
models/                   Human-friendly model hub grouped by capability
model_tools/              Model export helpers
scripts/                  Setup, build, validation, and deployment scripts
executorch-release-1.3/   Local ExecuTorch source checkout
```

## Tech Stack

| Component | Technology |
|---|---|
| On-device inference | ExecuTorch 1.3 + Qualcomm QNN delegate |
| NPU backend | Qualcomm Hexagon HTP (SM8750) |
| Face detection | Google ML Kit Face Detection |
| Face recognition | MobileFaceNet via ExecuTorch |
| Speech-to-text | Whisper |
| Text-to-speech | Piper |
| Database | Room Database (SQLite) |
| Camera | CameraX |
| Language | Kotlin + C++ |
| Target device | Samsung Galaxy S25 Ultra (SM-S938) |
