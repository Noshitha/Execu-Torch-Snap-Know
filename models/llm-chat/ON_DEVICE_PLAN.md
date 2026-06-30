# Chat LLM On-Device Plan

## Recommended Model

- `SmolLM2-360M-Instruct`

## Recommended Artifact Bundle

- `decoder.pte`
- tokenizer assets

## Why

For conversational follow-up after recognition or scene description, a small
instruction-tuned LLM is easier to host than a large VLM-only stack.

## Runtime Path

- Android: ExecuTorch decoder plus host-side token loop
- macOS: same `.pte` and tokenizer assets through a desktop runner

## Current Gap

This branch sets the model direction and file layout only. It does not yet
include exported chat weights.

## Next Export Work

1. Confirm the exact chat checkpoint.
2. Export the decoder artifact.
3. Add tokenizer files and a prompt-format contract.
