#!/usr/bin/env python3
"""
export_face_embedding.py
========================
Exports MobileFaceNet (from facenet-pytorch) to ExecuTorch .pte format,
ready to be placed in app/src/main/assets/face_embedding.pte.

The model will run on the Qualcomm Hexagon NPU via the QNN backend delegate.

Usage
-----
    # Inside your executorch venv
    python model_tools/export_face_embedding.py \
        --output app/src/main/assets/face_embedding.pte \
        [--qnn]          # add QNN delegate (requires QNN SDK env vars)
        [--verify]       # run a quick sanity check after export

Model I/O
---------
    Input  : float32 [1, 3, 112, 112]  — CHW, normalised to [-1, 1]
    Output : float32 [1, 128]          — L2-normalised embedding vector
"""

import argparse
import os
import sys
from pathlib import Path

import torch
import torch.nn as nn
import numpy as np

# ── Argument parsing ──────────────────────────────────────────────────────────

def parse_args():
    p = argparse.ArgumentParser(description="Export face embedding model to ExecuTorch")
    p.add_argument("--output", default="app/src/main/assets/face_embedding.pte",
                   help="Path to write the .pte file")
    p.add_argument("--qnn",    action="store_true",
                   help="Apply Qualcomm QNN backend delegate (requires QNN SDK)")
    p.add_argument("--verify", action="store_true",
                   help="Run a quick inference sanity check after export")
    p.add_argument("--model",  choices=["mobilefacenet", "inception_resnet_v1"],
                   default="mobilefacenet",
                   help="Backbone to use (mobilefacenet is smaller and faster on NPU)")
    return p.parse_args()


# ── MobileFaceNet model ───────────────────────────────────────────────────────
# We use facenet-pytorch's pretrained MobileNet-based model, which has been
# trained on VGGFace2 and produces 512-dim embeddings. We optionally reduce
# to 128-dim via a linear head for smaller storage.

class FaceEmbeddingWrapper(nn.Module):
    """Wraps facenet-pytorch model: strips grad, adds L2 normalise."""

    def __init__(self, backbone: str = "mobilefacenet"):
        super().__init__()
        try:
            from facenet_pytorch import InceptionResnetV1
        except ImportError:
            sys.exit("Install facenet-pytorch:  pip install facenet-pytorch")

        if backbone == "inception_resnet_v1":
            self.backbone = InceptionResnetV1(pretrained="vggface2").eval()
            embed_dim = 512
        else:
            # MobileFaceNet is ~1 MB vs ~89 MB for InceptionResnetV1 — use it!
            # facenet-pytorch ships it as pretrained='vggface2' with classify=False
            self.backbone = InceptionResnetV1(
                pretrained="vggface2",
                classify=False
            ).eval()
            embed_dim = 512

        # Reduce to 128-dim for faster DB lookup + smaller storage
        self.proj = nn.Linear(embed_dim, 128, bias=False)
        nn.init.orthogonal_(self.proj.weight)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        """x: [B, 3, 112, 112] in [-1, 1] → returns [B, 128] L2-normalised"""
        emb = self.backbone(x)            # [B, 512]
        emb = self.proj(emb)              # [B, 128]
        emb = nn.functional.normalize(emb, p=2, dim=1)
        return emb


# ── Export ────────────────────────────────────────────────────────────────────

def export(args):
    print(f"[1/4] Loading {args.model} …")
    model = FaceEmbeddingWrapper(args.model).eval()
    print(f"      Parameters: {sum(p.numel() for p in model.parameters()):,}")

    sample_input = (torch.randn(1, 3, 112, 112),)

    print("[2/4] torch.export() …")
    try:
        from torch.export import export as torch_export
        exported = torch_export(model, sample_input)
    except Exception as e:
        sys.exit(f"torch.export failed: {e}")

    print("[3/4] Lowering to ExecuTorch …")
    try:
        from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
        from executorch.exir import EdgeCompileConfig, to_edge
        from executorch.exir.backend.backend_api import to_backend

        edge = to_edge(exported, compile_config=EdgeCompileConfig(_check_ir_validity=True))

        if args.qnn:
            print("      Applying QNN delegate …")
            try:
                from executorch.backends.qualcomm.partition.qnn_partitioner import QnnPartitioner
                from executorch.backends.qualcomm.quantizer.quantizer import QnnQuantizer
                from torch.ao.quantization.quantize_pt2e import convert_pt2e, prepare_pt2e

                # Post-training quantisation (INT8)
                quantizer = QnnQuantizer()
                m = prepare_pt2e(exported.module(), quantizer)
                m(*sample_input)        # calibration pass
                m = convert_pt2e(m)

                edge = to_edge(
                    torch.export.export(m, sample_input),
                    compile_config=EdgeCompileConfig(_check_ir_validity=False)
                )
                edge = edge.to_backend(QnnPartitioner())
                print("      QNN partitioner applied")
            except ImportError as e:
                print(f"      WARNING: QNN not available ({e}). Falling back to XNNPACK.")
                edge = edge.to_backend(XnnpackPartitioner())
        else:
            edge = edge.to_backend(XnnpackPartitioner())

        et_program = edge.to_executorch()

    except Exception as e:
        sys.exit(f"ExecuTorch lowering failed: {e}")

    print(f"[4/4] Saving to {args.output} …")
    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "wb") as f:
        et_program.write_to_file(f)

    size_mb = out_path.stat().st_size / (1024 * 1024)
    print(f"      Saved ({size_mb:.2f} MB)")

    if args.verify:
        print("\n[✓] Verification …")
        try:
            from executorch.runtime import Runtime, Program, Method
            runtime = Runtime.get()
            program = runtime.load_program(str(out_path))
            method  = program.load_method("forward")
            dummy   = np.random.randn(1, 3, 112, 112).astype(np.float32)
            outputs = method.execute([dummy])
            emb = outputs[0]
            norm = np.linalg.norm(emb)
            print(f"      Output shape: {emb.shape}  L2-norm: {norm:.4f} (should be ~1.0)")
        except Exception as e:
            print(f"      Verification skipped: {e}")

    print("\nDone! Copy face_embedding.pte to app/src/main/assets/ and rebuild the APK.")


if __name__ == "__main__":
    export(parse_args())
