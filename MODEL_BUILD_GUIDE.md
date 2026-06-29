# SnapKnow Model Build Guide

This guide explains how to build all the models for the SnapKnow app.

## Recommended Workflow

The project now treats model work as a separate off-device phase before Android
or macOS integration.

1. Bootstrap a dedicated model environment:
```bash
make bootstrap-model-env
source .venv-models/bin/activate
```

2. Create the standardized artifact folders:
```bash
make build-model-set
```

3. Export or stage artifacts into the paths declared in:
```text
artifacts/model_set.json
```

4. Validate the model set and generate reports:
```bash
make validate-models
```

5. Review:
```text
artifacts/reports/model_validation_report.json
artifacts/reports/model_validation_report.html
```

## Models Overview

### 1. Face Embedding (Primary)
- **File**: `app/src/main/assets/face_embedding.pt`
- **Size**: 87 MB
- **Type**: PyTorch Mobile (TorchScript)
- **Architecture**: InceptionResnetV1 512-dim with INT8 quantization
- **Status**: ✅ Ready to use
- **Build Script**: `rebuild_face_embedding_pt.py`

```bash
python rebuild_face_embedding_pt.py
```

### 2. ExecuTorch Face Embedding (Portable + Android Optimized)
- **Build Script**: `export_face_embedding.py`
- **Outputs**:
  - `artifacts/android/face_embedding_xnnpack.pte`
  - `artifacts/android/face_embedding_qnn.pte`
- **Requires**: ExecuTorch, facenet-pytorch, torch.export
- **Optional backends**: QNN (Qualcomm), XNNPACK
- **Important**: This exporter currently supports `InceptionResnetV1` only.

```bash
# Build for CPU (XNNPACK)
python export_face_embedding.py \
    --output artifacts/android/face_embedding_xnnpack.pte \
    --verify

# Build for QNN (Qualcomm Hexagon NPU) - requires QNN SDK
python export_face_embedding.py \
    --output artifacts/android/face_embedding_qnn.pte \
    --qnn \
    --verify
```

### 3. Whisper Tiny (Speech-to-Text - Experimental)
- **Build Script**: `export_whisper_tiny.py`
- **Outputs**: `artifacts/shared/whisper/whisper_encoder.pte`
- **Expected runtime bundle**:
  - `artifacts/shared/whisper/whisper_encoder.pte`
  - `artifacts/shared/whisper/whisper_decoder.pte`
- **Status**: EXPERIMENTAL - currently using Android SpeechRecognizer instead
- **Requires**: ExecuTorch, openai-whisper

```bash
# Build Whisper models
python export_whisper_tiny.py \
    --out_dir artifacts/shared/whisper
```

### 4. Piper Voice Assets (Text-to-Speech - Optional)
- **Stage Script**: `scripts/stage_speech_assets.sh`
- **Expected runtime bundle**:
  - `artifacts/shared/piper/en_US-lessac-medium.onnx`
  - `artifacts/shared/piper/en_US-lessac-medium.onnx.json`
- **Status**: Asset/runtime bridge ready; synthesis binding still pending in Android code
- **Important**: Piper should stay ONNX-based; it is not a `.pte` target.

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
| **Quick Android fallback** | face_embedding.pt | PyTorch Mobile |
| **Generic Android + macOS** | face_embedding_xnnpack.pte | ExecuTorch XNNPACK |
| **Qualcomm Android** | face_embedding_qnn.pte | ExecuTorch QNN |
| **On-device STT** | whisper_*.pte | ExecuTorch (experimental) |
| **On-device TTS** | Piper ONNX bundle | Piper runtime |

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

1. Build or stage desired artifacts into `artifacts/`
2. Run `make validate-models`
3. Fix any missing required artifacts from `artifacts/reports/model_validation_report.json`
4. Only then wire the Android or macOS runtime to those validated artifacts
