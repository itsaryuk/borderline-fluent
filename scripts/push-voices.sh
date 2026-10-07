#!/bin/sh
# Dev parity for the release voices: pushes the Piper layout fetch-models.sh stages into the language packs
# (PACKS=<dir> fetch-models.sh <work> voices, then push-voices.sh <dir>) into files/tts, merging every language's
# tts/ dir (one espeak-ng-data with all dicts). Replaces files/tts. Usage: push-voices.sh [packs dir, default packs]
set -e
S=${ANDROID_SERIAL:?set ANDROID_SERIAL}; P=com.lampensoft.borderlinefluent; packs=${1:-packs}
m=$(mktemp -d); trap 'rm -rf "$m"' EXIT
for d in "$packs"/lang_*/src/main/assets/tts; do [ -d "$d" ] && cp -r "$d"/. "$m"/; done
adb -s "$S" shell "run-as $P rm -rf files/tts; run-as $P mkdir -p files/tts"
tar c -C "$m" . | adb -s "$S" shell "run-as $P sh -c 'tar x -C files/tts'"
echo "pushed: $(ls "$m" | tr '\n' ' ')"
