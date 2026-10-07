#!/bin/sh
# Release only: copies the Whisper turbo files into the core_whisper asset pack (on demand, ~1 GB; Play caps a pack
# at 1.5 GB). Usage: stage-models.sh <dir with turbo-encoder.int8.onnx, turbo-decoder.int8.onnx, turbo-tokens.txt>
# Needs ~1 GB free next to the repo. Undo with: git clean -fdX packs/core_*  (or delete the staged files).
set -e
src=${1:?model dir}; cd "$(dirname "$0")/.."
cp "$src"/turbo-encoder.int8.onnx "$src"/turbo-decoder.int8.onnx "$src"/turbo-tokens.txt packs/core_whisper/src/main/assets/models/
ls -la packs/core_whisper/src/main/assets/models/
