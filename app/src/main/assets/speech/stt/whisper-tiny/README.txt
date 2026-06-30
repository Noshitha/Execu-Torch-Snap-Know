Stage Whisper tiny assets in this directory.

Expected files:
- whisper_encoder.pte
- whisper_decoder.pte
- tokenizer.json
- whisper_manifest.json (optional but recommended)

Use one of these helpers:
- python model_tools/export_whisper_tiny.py
- python export_whisper_tiny.py
- bash scripts/stage_speech_assets.sh --whisper-encoder ... --whisper-decoder ... --whisper-tokenizer ...
