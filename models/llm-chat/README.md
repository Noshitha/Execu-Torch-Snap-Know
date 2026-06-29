# LLM Chat

## Current status

- Placeholder only
- No finalized chat model artifact is in this repo yet

## Why it is separate

The chat LLM should be chosen after the rest of the model pipeline is stable,
because it affects:

- tokenizer/runtime packaging
- memory budget
- latency budget
- Android/macOS compatibility

## Expected future artifacts

- model weights in a portable runtime format
- tokenizer files
- generation/runtime config

## Next step

Choose a small on-device instruction/chat model once face embedding, object
detection, Whisper, and Piper artifacts are cleaned up.
