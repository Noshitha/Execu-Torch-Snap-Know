# SnapKnow Model Build Guide

This guide explains how to build all the models for the SnapKnow app.

## Models Overview

### 1. Face Embedding (Primary - Already in Repo)
- **File**: `app/src/main/assets/face_embedding.pt`
- **Size**: 87 MB
- **Type**: PyTorch Mobile (TorchScript)
- **Architecture**: InceptionResnetV1 512-dim with INT8 quantization
- **Status**: ✅ Ready to use
- **Build Script**: `rebuild_face_embedding_pt.py`

```bash
python rebuild_face_embedding_pt.py
```

### 2. ExecuTorch Face Embedding (Alternative - Optimized for NPU)
- **Build Script**: `export_face_embedding.py`
- **Output**: `face_embedding.pte`
- **Requires**: ExecuTorch, facenet-pytorch, torch.export
- **Optional backends**: QNN (Qualcomm), XNNPACK

```bash
# Install dependencies
pip install executorch facenet-pytorch

# Build for CPU (XNNPACK)
python export_face_embedding.py \
    --output app/src/main/assets/face_embedding.pte \
    --verify

# Build for QNN (Qualcomm Hexagon NPU) - requires QNN SDK
python export_face_embedding.py \
    --output app/src/main/assets/face_embedding_qnn.pte \
    --qnn \
    --verify
```

### 3. Whisper Tiny (Speech-to-Text - Optional)
- **Build Script**: `export_whisper_tiny.py`
- **Outputs**: `app/src/main/assets/speech/stt/whisper-tiny/whisper_encoder.pte`
- **Expected runtime bundle**: `whisper_encoder.pte` + `whisper_decoder.pte`
- **Status**: EXPERIMENTAL - currently using Android SpeechRecognizer instead
- **Requires**: ExecuTorch, openai-whisper

```bash
# Install dependencies
pip install executorch openai-whisper

# Build Whisper models
python export_whisper_tiny.py \
    --out_dir app/src/main/assets/speech/stt/whisper-tiny
```

### 4. Piper Voice Assets (Text-to-Speech - Optional)
- **Stage Script**: `scripts/stage_speech_assets.sh`
- **Expected runtime bundle**:
  - `app/src/main/assets/speech/tts/piper/en_US-lessac-medium/en_US-lessac-medium.onnx`
  - `app/src/main/assets/speech/tts/piper/en_US-lessac-medium/en_US-lessac-medium.onnx.json`
- **Status**: Asset/runtime bridge ready; synthesis binding still pending in Android code

```bash
bash scripts/stage_speech_assets.sh \
    --piper-model /path/to/en_US-lessac-medium.onnx \
    --piper-config /path/to/en_US-lessac-medium.onnx.json
```

## Environment Setup

### macOS
```bash
python3 -m venv venv
source venv/bin/activate

pip install --upgrade pip
pip install torch==2.2.2 torchvision==0.17.2
pip install facenet-pytorch
pip install openai-whisper
pip install executorch  # May have version conflicts
```

### Linux / WSL
```bash
python3 -m venv venv
source venv/bin/activate

pip install --upgrade pip
pip install torch==2.2.2 torchvision==0.17.2 torchaudio
pip install facenet-pytorch
pip install openai-whisper
pip install executorch
```

## Which Model Should I Use?

| Scenario | Model | Format |
|----------|-------|--------|
| **Quick development** | face_embedding.pt | PyTorch Mobile |
| **Qualcomm device (NPU)** | face_embedding_qnn.pte | ExecuTorch QNN |
| **Generic Android (CPU)** | face_embedding.pte | ExecuTorch XNNPACK |
| **On-device speech** | whisper_*.pte | ExecuTorch (experimental) |

## Current App Configuration

The app currently uses:
1. **Face Detection**: Google ML Kit (auto-downloaded)
2. **Face Embedding**: PyTorch Mobile (`face_embedding.pt`)
3. **Speech Recognition**: Android SpeechRecognizer (built-in)

To use alternative models, update:
- `app/src/main/java/com/snapknow/app/inference/FaceEmbeddingModel.kt` (for .pte models)
- `app/src/main/java/com/snapknow/app/inference/ExecuTorchModule.kt` (for ExecuTorch)

## Troubleshooting

### "No module named 'executorch'"
```bash
pip install executorch
```

### "torch.export failed: Python 3.12+ not yet supported for torch.compile"
Use Python 3.11 or earlier for torch.export support.

### "FacenetPytorch model not found"
```bash
pip install facenet-pytorch
# Models auto-download on first use (~90 MB)
```

### Build hangs or runs out of memory
Reduce batch size or run on a machine with more RAM (>8GB recommended).

## Next Steps

1. Build desired models using scripts above
2. Copy `.pt` or `.pte` files to `app/src/main/assets/`
3. Update Java inference wrappers if switching model formats
4. Optionally stage speech assets: `bash scripts/stage_speech_assets.sh --help`
5. Rebuild APK: `./gradlew assembleDebug`
