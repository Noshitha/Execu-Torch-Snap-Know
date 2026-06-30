# SnapKnow Models Hub

This folder is the human-friendly index for the SnapKnow model stack.

Each subfolder represents one model family:

- `object-detection/`
- `face-embedding/`
- `stt-whisper/`
- `tts-piper/`
- `vlm/`
- `llm-chat/`

Use this folder to answer:

1. What model do we intend to use for each capability?
2. What artifact format should it be in?
3. What actually exists in the repo today?
4. What is still missing before Android and macOS on-device work?

## How To Read The Hub

- `models/model_index.json`
  Central machine-readable summary of readiness by model family.
- `models/<family>/README.md`
  Short human-readable explanation of the current model direction.
- `models/<family>/artifact_status.json`
  Audit record for what is present, what is missing, and which files are the
  current source of truth.
- `models/<family>/*.txt`
  Small plain-text status notes kept directly in the model folder.

The real deployment files still live under `app/src/main/assets/` and
`artifacts/`. This folder organizes them by function so they are easier to
review without pretending that planned artifacts already exist.
