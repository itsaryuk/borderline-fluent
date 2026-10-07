#!/bin/sh
# Dev install with Play Asset Delivery simulated locally: builds the debug bundle, then lets bundletool install it
# with --local-testing so on-demand packs "download" from the phone itself. Needs ANDROID_SERIAL.
set -e
cd "$(dirname "$0")/.."
S=${ANDROID_SERIAL:?set ANDROID_SERIAL}
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}
# A fresh versionCode per install (minutes since epoch): Play Asset Delivery only replaces installed packs when the
# version grows, so a fixed code 1 kept serving the first art the phone ever got (2026-10-04: old human officers).
./gradlew -q bundleDebug -PversionCode=$(( $(date +%s) / 60 ))
out=$(mktemp -d)
"$JAVA_HOME/bin/java" -jar tools/bundletool-all-1.18.3.jar build-apks --bundle=app/build/outputs/bundle/debug/app-debug.aab \
  --output="$out/app.apks" --local-testing --connected-device --device-id="$S" --adb="$(command -v adb)"
"$JAVA_HOME/bin/java" -jar tools/bundletool-all-1.18.3.jar install-apks --apks="$out/app.apks" --device-id="$S" --adb="$(command -v adb)"
rm -rf "$out"
# An instance started mid-install asks for packs before bundletool has copied them ("Failed fetching APKs for
# pack"); stop it so the next launch sees the finished local_testing dir.
adb -s "$S" shell am force-stop com.lampensoft.borderlinefluent
