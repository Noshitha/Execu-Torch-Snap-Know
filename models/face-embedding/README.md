# Face Embedding

## Current status

- Present in the repo
- Current artifact format: `pt`
- Current runtime: PyTorch Mobile
- Current file: `app/src/main/assets/face_embedding.pt`

## What we still need

For true cross-platform on-device deployment, this model family still needs:

- `face_embedding_xnnpack.pte`
- `face_embedding_qnn.pte`

The portable `.pte` artifact is the important next milestone because it is the
artifact we want to use as the shared Android/macOS direction.

## Current artifact view

- `current/artifact_status.json`
- `artifacts/android/face-embedding/manifest.json`
- `artifacts/macos/face-embedding/manifest.json`

## Next step

Export the portable `.pte` artifact first, then add the Qualcomm-specific QNN
variant for Android optimization.
