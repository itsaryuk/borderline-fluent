#!/bin/sh
# Release only: downloads the pinned models (Whisper turbo, Piper voices), checks their sha256, and stages them into
# the asset packs (scripts/stage-models.sh). Usage: fetch-models.sh <work dir>   — needs ~2 GB free there (the
# 0.6 GB Whisper archive plus its extracted int8 files) and ~1.2 GB in packs/; the work dir is emptied as it goes.
# "fetch-models.sh <work dir> voices" fetches only the Piper voices (PACKS=<dir> to stage elsewhere).
# Bumping a model = new URL + new sha256 here, then the matching bench on the phone.
set -eu
w=${1:?work dir}; mkdir -p "$w"; cd "$(dirname "$0")/.."
PACKS=${PACKS:-packs}

# Piper voices (sherpa-onnx tts-models, int8), chosen by measured pitch (BenchActivity `voices`, male < ~165 Hz).
# One row per officer voice: lang gender release-name sha256 speaker-id. Languages or genders without a row use the
# system voice (no Piper voice of that gender in the measured candidates; ja has none at all). sid != 0 -> speaker.txt.
# Licences: NOTICE.md. Not redistributable, so dropped (2026-10-15): en_US-lessac (research-only dataset), ru_RU-irina
# and ar_JO-kareem (no licence stated); those officers speak with the system voice.
# Each language pack also gets espeak-ng-data minus the other languages' *_dict (the voice's espeak.voice decides which).
PIPER_URL=https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models
VOICES='
ar f ar_JO-SA_dii-high 02f6f990b31323b7f36c8c68c3a5dc7f7c9f3db555c632310693cf7d82b9540e 0
de m de_DE-thorsten-medium 07e240b7b9c1fc9211d5a69512f8cbe11b3286c2ed79c15c076ac6ed427fdf13 0
de f de_DE-kerstin-low bcd8039667940cf2efc939b844f4b33d0823096572fcc1a8caaa2faa77f3379c 0
en m en_US-ryan-medium 376eb489d42e98cb49f0e13a633e8d88580bd247ed8aacc298a733210404f771 0
es m es_ES-davefx-medium 8bb8ac1cefb727caec9bd9c6c3185c673c8b42c53bd29bb25d5a7715dac37125 0
es f es_ES-sharvard-medium bf5507cbdedea650365bd6175b3164a8ecca5847533ddf97afcba9d76ea599d1 1
fr m fr_FR-tom-medium b158f884eb4231b8dddd372c3dc506949630c992a8e48ca692a90323e8bb4e09 0
fr f fr_FR-siwis-medium 3909cff9b3cfd4820c66aa13bf554315c82e34899c161f0b446ece372bc4b5ec 0
it m it_IT-riccardo-x_low afc2ea457fd45420dbdd87db4b3927b4fc97b00511964d474bb43e03092d37c1 0
it f it_IT-paola-medium 2b975ed305391c056944a4dde67ee754dd824099503a860295bb4c1d724662d8 0
pl m pl_PL-darkman-medium 0ec47b7d591e48913da887ea7e81287f4ef621d9c5aa1848700d2d736ed3f99c 0
pl f pl_PL-gosia-medium 72acac4c4b031725c41a61b3af0314a3d30e1ec2cd83ee410ea5f9e6d2d9d4fb 0
pt m pt_BR-faber-medium dbc8b1d7d729fd417ea78a350ed35696c928770ac93513d3f507bd4e88eee3fd 0
pt f pt_PT-dii-high 7fc966c08bea9acd6b89f356e3070bf0217e0f8bcca81e40c70076cf2d5d7c66 0
tr m tr_TR-dfki-medium 6cda7bc029b7d5549a80439c51beb4c40c42df4b4614f01a8c61deb2fc859aa2 0
tr f tr_TR-fettah-medium 4374bfdabb88ada0f3edf9bd46eacf1c5391a8a93bdce364196495891e5bc323 0
uk m uk_UA-ukrainian_tts-medium 9e3e20f311e3c486d989c3d481d9537741deb7beaeed1aa63c737574498b1e75 1
uk f uk_UA-ukrainian_tts-medium 9e3e20f311e3c486d989c3d481d9537741deb7beaeed1aa63c737574498b1e75 2
'
# Held-back languages (heldBackLanguages in gradle.properties, the release switch) have no pack in the bundle: their
# rows stay above for the day they come back, but nothing is fetched for them (HELD_BACK= in the environment fetches all).
HELD_BACK=${HELD_BACK-$(sed -n 's/^heldBackLanguages=//p' gradle.properties | tr ',' ' ')}
fetch_voices() {
  echo "$VOICES" | while read -r lang g name sha sid; do
    [ -n "$lang" ] || continue
    case " $HELD_BACK " in *" $lang "*) echo "fetch-models: skipping $name ($lang is held back)"; continue ;; esac
    t="$w/$name.tar.bz2"; d="$PACKS/lang_$lang/src/main/assets/tts"
    curl -fL --retry 3 -o "$t" "$PIPER_URL/vits-piper-$name-int8.tar.bz2"
    echo "$sha  $t" | sha256sum -c
    rm -rf "$w/v"; mkdir -p "$w/v" "$d/${lang}_$g"
    tar xjf "$t" -C "$w/v" --strip-components=1; rm "$t"
    mv "$w"/v/*.onnx "$d/${lang}_$g/model.onnx"; mv "$w/v/tokens.txt" "$d/${lang}_$g/"
    [ "$sid" = 0 ] || echo "$sid" > "$d/${lang}_$g/speaker.txt"
    if [ ! -d "$d/espeak-ng-data" ]; then
      case $lang in zh) keep=cmn_dict ;; *) keep=${lang}_dict ;; esac
      mkdir -p "$d/espeak-ng-data"
      for f in "$w"/v/espeak-ng-data/*; do
        case $f in *_dict) [ "$(basename "$f")" = "$keep" ] || continue ;; esac
        cp -r "$f" "$d/espeak-ng-data/"
      done
    fi
  done
  rm -rf "$w/v"
}
[ "${2:-}" = voices ] && { fetch_voices; exit 0; }

# Whisper large-v3-turbo for sherpa-onnx; only the int8 encoder/decoder and the tokens are used.
TURBO_URL=https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-turbo.tar.bz2
TURBO_SHA=b11acbbcd660b44a8e0df33724feb5aaa709cf65668f2823d59f656312544f22

curl -fL --retry 3 -o "$w/turbo.tar.bz2" "$TURBO_URL"
echo "$TURBO_SHA  $w/turbo.tar.bz2" | sha256sum -c
tar xjf "$w/turbo.tar.bz2" -C "$w" --strip-components=1 \
  sherpa-onnx-whisper-turbo/turbo-encoder.int8.onnx sherpa-onnx-whisper-turbo/turbo-decoder.int8.onnx sherpa-onnx-whisper-turbo/turbo-tokens.txt
rm "$w/turbo.tar.bz2"

fetch_voices
scripts/stage-models.sh "$w"
rm -rf "$w"
