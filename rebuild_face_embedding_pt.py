#!/usr/bin/env python3
"""
Rebuild SnapKnow face embedding model (TorchScript .pt) from scratch.

Default behavior:
- Backbone: InceptionResnetV1 (VGGFace2 pretrained)
- Output: 512-dim L2-normalized embedding
- Quantization: dynamic INT8 on Linear layers
- Output file: app/src/main/assets/face_embedding.pt
"""

from __future__ import annotations

import argparse
from pathlib import Path
import shutil
import time

import torch
import torch.nn as nn
from torch.utils.mobile_optimizer import optimize_for_mobile


FACE_SIZE = 112


class FaceEmbeddingTorchScript(nn.Module):
    def __init__(self) -> None:
        super().__init__()
        from facenet_pytorch import InceptionResnetV1

        self.backbone = InceptionResnetV1(pretrained="vggface2", classify=False).eval()

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        emb = self.backbone(x)  # [B, 512]
        emb = nn.functional.normalize(emb, p=2, dim=1)
        return emb


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Rebuild quantized face_embedding.pt")
    p.add_argument(
        "--output",
        default="app/src/main/assets/face_embedding.pt",
        help="Where to write TorchScript model",
    )
    p.add_argument(
        "--no-quantize",
        action="store_true",
        help="Disable dynamic quantization (debug mode)",
    )
    return p.parse_args()


def main() -> None:
    args = parse_args()
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)

    if out.exists():
        stamp = time.strftime("%Y%m%d-%H%M%S")
        backup = out.with_suffix(out.suffix + f".{stamp}.bak")
        shutil.copy2(out, backup)
        print(f"[backup] {backup}")

    print("[1/5] Loading pretrained InceptionResnetV1...")
    model = FaceEmbeddingTorchScript().eval()

    if not args.no_quantize:
        print("[2/5] Applying dynamic INT8 quantization (Linear layers)...")
        supported = torch.backends.quantized.supported_engines
        if "qnnpack" in supported:
            torch.backends.quantized.engine = "qnnpack"
        elif "fbgemm" in supported:
            torch.backends.quantized.engine = "fbgemm"
        else:
            raise RuntimeError(f"No supported quantized engine available: {supported}")
        model = torch.ao.quantization.quantize_dynamic(
            model,
            {nn.Linear},
            dtype=torch.qint8,
        )
    else:
        print("[2/5] Quantization disabled.")

    dummy = torch.randn(1, 3, FACE_SIZE, FACE_SIZE)
    print("[3/5] Tracing TorchScript...")
    ts = torch.jit.trace(model, dummy)
    ts = torch.jit.freeze(ts.eval())

    print("[4/5] Optimizing for mobile...")
    ts_opt = optimize_for_mobile(ts)

    print(f"[5/5] Saving -> {out}")
    ts_opt._save_for_lite_interpreter(str(out))

    size_mb = out.stat().st_size / (1024 * 1024)
    print(f"[done] Saved {out} ({size_mb:.2f} MB)")

    y = ts_opt(dummy)
    print(f"[verify] Output shape: {tuple(y.shape)}; mean L2 norm: {y.norm(dim=1).mean().item():.4f}")


if __name__ == "__main__":
    main()
