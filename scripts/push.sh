#!/bin/sh
# Copy files into the app's internal storage as the app user: push.sh <models|wavs> <file>...
# Files already on /sdcard are piped through run-as; local files are streamed over adb.
set -e
S=${ANDROID_SERIAL:?set ANDROID_SERIAL}; P=com.lampensoft.borderlinefluent; sub=$1; shift
adb -s "$S" shell "run-as $P mkdir -p files/$sub"
for f in "$@"; do
  n=$(basename "$f")
  case "$f" in
    /sdcard/*) adb -s "$S" shell "cat '$f' | run-as $P sh -c 'cat > files/$sub/$n'" ;;
    *) adb -s "$S" shell "run-as $P sh -c 'cat > files/$sub/$n'" < "$f" ;;
  esac
  echo "copied $n"
done
