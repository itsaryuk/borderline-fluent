#!/bin/sh
# Run one headless benchmark and print its BORDER log lines: bench.sh --es mode asr --es asr small
S=${ANDROID_SERIAL:?set ANDROID_SERIAL}; P=com.lampensoft.borderlinefluent
adb -s "$S" logcat -c
adb -s "$S" shell am force-stop $P
adb -s "$S" shell am start --activity-clear-task -f 0x10000000 -n $P/.BenchActivity "$@" >/dev/null
for _ in $(seq 400); do
  sleep 3
  adb -s "$S" logcat -d -s BORDER:I | grep -q 'DONE mode\|ERROR mode' && break
done
adb -s "$S" logcat -d -s BORDER -v raw | grep -v '^---------'
