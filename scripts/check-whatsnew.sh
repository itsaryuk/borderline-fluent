#!/usr/bin/env bash
# Release-notes check for play/whatsnew/whatsnew-en-US, Play's "what's new" copy. Always checks the file
# exists and has non-whitespace content, and that EVERY play/whatsnew/whatsnew-* file (the Play uploader
# reads them all, one per locale) fits Play's 500-character limit (counted under C.UTF-8 so a bullet
# character counts as one, not its multi-byte UTF-8 length). With a version argument (release.yml,
# called once the tag's version is known) it also checks en-US's first line reads "Borderline Fluent X.Y.Z" -- catches
# notes left over from the previous release. ci.yml calls it with no argument on every push, which catches a
# missing or oversized file early; only a run with the version (the release skill before tagging, then the
# tag build) can catch stale notes.
#
#   scripts/check-whatsnew.sh [X.Y.Z]
set -euo pipefail
cd "$(dirname "$0")/.."
f="play/whatsnew/whatsnew-en-US"

[ -f "$f" ] || { echo "check-whatsnew: $f is missing"; exit 1; }
grep -q '[^[:space:]]' "$f" || { echo "check-whatsnew: $f has no non-whitespace content"; exit 1; }

shopt -s nullglob
n=
for wf in play/whatsnew/whatsnew-*; do
  wn=$(LC_ALL=C.UTF-8 wc -m < "$wf")
  [ "$wn" -le 500 ] || { echo "check-whatsnew: $wf is $wn characters, over Play's 500-character limit"; exit 1; }
  [ "$wf" = "$f" ] && n="$wn"
done

if [ $# -ge 1 ]; then
  want="Borderline Fluent $1"
  got=$(head -n1 "$f")
  got=${got%$'\r'}
  [ "$got" = "$want" ] || { echo "check-whatsnew: first line is '$got', want '$want' (stale notes from the previous release?)"; exit 1; }
fi

echo "check-whatsnew: ok, $n characters"
