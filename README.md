# SnapKnow — On-Device Memory Assistant for Dementia Patients

An offline voice + vision assistant running fully on-device on Samsung Galaxy S25 Ultra,
powered by ExecuTorch and the Qualcomm Hexagon NPU.

**Zero internet. Zero cloud. Zero privacy compromise.**

## Model-First Workflow

Before doing any Android or macOS deployment work, finish the off-device model
artifact phase:

```bash
make bootstrap-model-env
source .venv-models/bin/activate
make build-model-set
make validate-models
```

Review the generated report:

```text
artifacts/reports/model_validation_report.html
```

This report tells you which artifacts are already present, which ones are
missing, and which formats are intended to be shared across Android and macOS.

---

## What it does

| Feature | How to use |
|---------|-----------|
| **Remember object location** | Point camera at keys → say *"I'm keeping my keys on the right side of the table"* |
| **Recall object location** | Say *"Where are my keys?"* → hears spoken answer |
| **Remember a person** | Point camera at someone → tap **Remember Face** → say *"This is John, my son"* |
| **Recognise a person** | Point camera at that person → app auto-announces *"That's John, your son"* |

---

## Architecture

```
Live Camera (CameraX)
       │
       ▼
ML Kit Face Detection (on-device, no network)
       │
       ▼
ExecuTorch MobileFaceNet → face embedding [128-dim float32]
       │ running on Qualcomm Hexagon NPU (QNN backend)
       ▼
Room Database (SQLite)  ←→  Cosine similarity matching
       │
       ▼
Android TextToSpeech (offline)

Android SpeechRecognizer (offline)  ──►  CommandParser (regex NLP)  ──►  MemoryRepository
```

---

## Hackathon Setup: End-to-End Runbook

### Prerequisites
Install these **manually** first (on Linux x86_64):

| Tool | Version | Where to get |
|------|---------|--------------|
| Python | 3.10–3.13 | python.org |
| Qualcomm AI Engine Direct SDK | 2.37+ | [Qualcomm Software Center](https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk) |
| Android NDK | r26c | [developer.android.com/ndk](https://developer.android.com/ndk/downloads) |
| Samsung Galaxy S25 Ultra | SM-S938 | with USB debugging enabled |
| cmake | 3.22+ | `apt install cmake` |
| ninja | any | `apt install ninja-build` |

---

### Step 1 — Set environment variables
```bash
export ANDROID_NDK_ROOT=/path/to/android-ndk-r26c
export QNN_SDK_ROOT=/path/to/qairt/2.xx.x.xxxxxxxx
export LD_LIBRARY_PATH=$QNN_SDK_ROOT/lib/x86_64-linux-clang:$LD_LIBRARY_PATH
```

### Step 2 — Clone & set up ExecuTorch
```bash
bash scripts/setup_executorch.sh
```
This clones ExecuTorch `release/1.3`, initialises submodules, and installs Python deps.

### Step 3 — Export the face embedding model
```bash
cd SnapKnow
python -m venv model_tools/.venv
source model_tools/.venv/bin/activate
pip install -r model_tools/requirements.txt
pip install -e ../executorch    # installs ExecuTorch Python package

python model_tools/export_face_embedding.py --qnn --verify
# Output: app/src/main/assets/face_embedding.pte  (~2 MB)
```

### Step 4 — Build ExecuTorch Android AAR with QNN backend
```bash
bash scripts/build_android_qnn.sh
# Output: app/libs/executorch.aar
```

### Step 5 — Open in Android Studio & build APK
1. Open the `SnapKnow/` folder in Android Studio
2. Wait for Gradle sync
3. **Build → Build APK(s)**

Or from command line:
```bash
chmod +x gradlew
./gradlew assembleDebug
```

### Step 6 — Deploy to S25 Ultra
```bash
bash scripts/deploy_to_s25.sh
```

---

## Voice command reference

| Intent | Example phrases |
|--------|----------------|
| Store object | "I'm putting my keys on the kitchen counter" |
| | "My glasses are on the bedside table" |
| | "Remember, phone is charging in the living room" |
| Recall object | "Where are my keys?" |
| | "Where did I leave my glasses?" |
| | "Find my phone" |
| Meet someone | *(point camera at person)* → tap **Remember Face** → say "This is Sarah, my daughter" |
| | "This is Doctor Kumar" |
| | "Her name is Mary" |
| Recognise | *(just point camera — auto-announces if known)* |
| List memories | "What do you remember?" |
| Forget | "Forget my keys" / "Forget John" |

---

## Project structure

```
SnapKnow/
├── app/src/main/
│   ├── java/com/snapknow/app/
│   │   ├── MainActivity.kt          Main entry — permissions, wires everything
│   │   ├── MainViewModel.kt         State + command dispatch
│   │   ├── camera/
│   │   │   ├── CameraHelper.kt      CameraX setup (preview + analysis)
│   │   │   └── FaceAnalyzer.kt      ML Kit face detection + bitmap crop
│   │   ├── database/
│   │   │   ├── AppDatabase.kt       Room database singleton
│   │   │   ├── entity/ObjectMemory  "keys on the table" rows
│   │   │   ├── entity/FaceMemory    name + 128-dim embedding rows
│   │   │   └── MemoryRepository.kt  cosine similarity, CRUD
│   │   ├── inference/
│   │   │   ├── ExecuTorchModule.kt  JNI wrapper → libsnapknow_jni.so
│   │   │   └── FaceEmbeddingModel   Preprocess → inference → L2 normalise
│   │   ├── nlp/CommandParser.kt     Regex NLP → Command sealed class
│   │   ├── voice/
│   │   │   ├── VoiceRecognitionManager  Always-on offline SpeechRecognizer
│   │   │   └── TtsManager           Android TextToSpeech wrapper
│   │   └── ui/FaceOverlayView.kt    Canvas overlay with name labels
│   ├── cpp/executorch_jni.cpp       C++ ExecuTorch Module API bridge
│   └── res/layout/activity_main.xml Full-screen camera + status card
├── model_tools/
│   ├── export_face_embedding.py     MobileFaceNet → .pte  ← run this!
│   └── export_whisper_tiny.py       Optional Whisper export
└── scripts/
    ├── setup_executorch.sh          Clone + install ExecuTorch
    ├── build_android_qnn.sh         Build AAR with QNN backend
    └── deploy_to_s25.sh             Build APK + adb install + launch
```

---

## Without ExecuTorch (quick demo mode)

If you don't have the AAR or the `.pte` file yet, the app still works:
- Camera + face detection boxes display correctly
- Object location memory fully functional via voice
- Face *recognition* is disabled (shows "Unknown" labels)
- Everything else runs — good for testing the UX flow

---

## Notes for the demo day

1. **Offline speech recognition**: Make sure the S25 has the English offline pack downloaded.
   Settings → General management → Language → English → Offline speech recognition → Download

2. **First run**: Grant Camera + Microphone on first launch

3. **Face recognition threshold**: Default 0.65 cosine similarity. Lower it in `MemoryRepository.kt` (`FACE_MATCH_THRESHOLD`) if too strict, raise it if too many false positives.

4. **Battery**: The QNN NPU path is extremely power-efficient vs CPU/GPU inference.

---

## Tech stack

| Component | Technology |
|-----------|-----------|
| On-device inference | ExecuTorch 1.3 + Qualcomm QNN delegate |
| NPU backend | Qualcomm Hexagon HTP (SM8750) |
| Face detection | Google ML Kit Face Detection |
| Face recognition | MobileFaceNet via ExecuTorch |
| Speech-to-text | Android SpeechRecognizer (offline) |
| Text-to-speech | Android TextToSpeech |
| Database | Room (SQLite) |
| Camera | CameraX |
| Language | Kotlin + C++17 (JNI) |
| Target device | Samsung Galaxy S25 Ultra (SM-S938) |
