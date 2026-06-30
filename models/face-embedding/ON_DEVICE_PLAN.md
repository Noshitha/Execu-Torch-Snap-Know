# Face Embedding On-Device Plan

## Recommended Artifacts

- `face_embedding_xnnpack.pte`
- `face_embedding_qnn.pte`

## Why

Face embedding is the best candidate in this repo for a shared ExecuTorch
artifact path. The portable XNNPACK export should be the first deliverable,
with the QNN variant layered on for Snapdragon optimization.

## Runtime Path

- Android generic: ExecuTorch + XNNPACK
- Android Snapdragon: ExecuTorch + QNN
- macOS: ExecuTorch + XNNPACK

## Current Gap

The repo still contains a legacy `face_embedding.pt` asset, but the real
cross-platform milestone is the `.pte` export pair above.

## Next Export Work

1. Normalize the exporter to the intended face model.
2. Produce the portable XNNPACK `.pte`.
3. Add the QNN-specific export once the portable artifact validates.
