# SnapKnow Models Hub

This folder is a human-friendly view of the model stack for the project.

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
3. What do we already have in the repo today?
4. What still needs export, training, or on-device optimization?

The real deployment files still live in app assets or future `artifacts/`
outputs. This folder organizes them by function so they are easier to review.
