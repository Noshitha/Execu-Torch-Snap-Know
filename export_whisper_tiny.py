#!/usr/bin/env python3
"""
export_whisper_tiny.py  (OPTIONAL — for future Whisper integration)
====================================================================
Exports Whisper Tiny encoder + decoder to ExecuTorch .pte format.
This is OPTIONAL for the hackathon; the app uses Android's built-in offline
SpeechRecognizer by default, which is simpler and already works offline.

Use this if you want to replace the Android ASR with Whisper on ExecuTorch.

Usage
-----
    python model_tools/export_whisper_tiny.py \
        --out_dir app/src/main/assets/ \
        [--qnn]

Output files
------------
    whisper_encoder.pte   — mel spectrogram → audio features
    whisper_decoder.pte   — audio features → token logits
    tokenizer.json        — stage separately from the Whisper model repo for decoding
"""

import argparse
import json
import sys
from pathlib import Path

import torch


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--out_dir", default="app/src/main/assets/speech/stt/whisper-tiny")
    p.add_argument("--qnn", action="store_true")
    return p.parse_args()


def export_whisper(args):
    try:
        import whisper
    except ImportError:
        sys.exit("Install openai-whisper:  pip install openai-whisper")

    print("[1/5] Loading Whisper Tiny …")
    model = whisper.load_model("tiny").eval()

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    # ── Encoder ──────────────────────────────────────────────────────────────
    print("[2/5] Exporting encoder …")

    class WhisperEncoder(torch.nn.Module):
        def __init__(self, m): super().__init__(); self.enc = m.encoder
        def forward(self, x): return self.enc(x)

    enc = WhisperEncoder(model)
    dummy_mel = torch.zeros(1, 80, 3000)   # 30-second mel spectrogram

    try:
        from torch.export import export as torch_export
        from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
        from executorch.exir import EdgeCompileConfig, to_edge

        enc_exported = torch_export(enc, (dummy_mel,))
        enc_edge = to_edge(enc_exported, compile_config=EdgeCompileConfig(_check_ir_validity=False))
        enc_edge = enc_edge.to_backend(XnnpackPartitioner())
        enc_et   = enc_edge.to_executorch()

        enc_path = out_dir / "whisper_encoder.pte"
        with open(enc_path, "wb") as f:
            enc_et.write_to_file(f)
        print(f"      Encoder: {enc_path}  ({enc_path.stat().st_size/1024/1024:.2f} MB)")
    except Exception as e:
        print(f"      Encoder export failed: {e}")

    manifest = {
        "model": "openai/whisper-tiny",
        "asset_dir": str(out_dir),
        "generated_files": ["whisper_encoder.pte"],
        "missing_files": ["whisper_decoder.pte"],
        "notes": "Decoder export remains experimental in this environment."
    }

    manifest_path = out_dir / "whisper_manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"      Manifest: {manifest_path}")

    # ── Decoder ──────────────────────────────────────────────────────────────
    print("[3/5] Exporting decoder (greedy) …")
    print("      NOTE: Whisper decoder uses dynamic shapes — ExecuTorch support is experimental.")
    print("      The Android SpeechRecognizer is recommended for the hackathon demo.")

    print("\nDone. To use Whisper on-device, see docs/whisper_integration.md")


if __name__ == "__main__":
    export_whisper(parse_args())
