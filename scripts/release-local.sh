#!/usr/bin/env bash
# Full local Play build, the alternative to the CI path:
# stages the pinned models as release.yml does, runs the unit tests and bundleRelease WITHOUT UPLOAD_KEYSTORE, strips
# the debug signature into ~/releases/borderline-fluent-X.Y.Z-unsigned.aab and signs it with the upload key through
# jarsigner into ~/releases/borderline-fluent-X.Y.Z.aab (that name only ever means signed). The passwords come from a
# zenity dialog straight into jarsigner's environment; argv carries only the variable names; nothing prints them.
# Never uploads, pushes or tags: the owner does those.
#
#   scripts/release-local.sh X.Y.Z [--check]     --check runs only the preflight.
#
# Disk: needs RELEASE_NEED_MB free on the repo's filesystem (default: the estimate below) and RELEASE_OUT_NEED_MB on
# ~/releases' when that is another filesystem. Staged models and release build intermediates are deleted afterwards,
# also on failure; the signed AAB stays.
set -eu
case $- in *x*) echo "release-local: refusing to run with xtrace on (it would trace the password)" >&2; exit 1 ;; esac
cd "$(dirname "$0")/.."
die() { echo "release-local: $*" >&2; exit 1; }
usage="usage: release-local.sh X.Y.Z [--check]"
[ $# -ge 1 ] && [ $# -le 2 ] || die "$usage"
v=$1; check=
case ${2:-} in '') ;; --check) check=1 ;; *) die "unknown argument '$2'; $usage" ;; esac
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}
KS=$HOME/.android/borderline-upload.jks
BT=tools/bundletool-all-1.18.3.jar
OUT=$HOME/releases
AAB=$OUT/borderline-fluent-$v.aab
UNSIGNED=$OUT/borderline-fluent-$v-unsigned.aab
PIN=play/upload-cert-sha256.txt   # tracked, public: the upload certificate's SHA-256 (AA:BB:…), once the key exists
# One run at a time (two would stage, clean and sign over each other). fd 9 holds the lock until exit.
mkdir -p build; exec 9>build/release-local.lock
flock -n 9 || die "another release-local.sh is running (build/release-local.lock)"

# --- Preflight: the version and the tree stop at once; the rest is all reported before stopping.
[[ "$v" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || die "version must be X.Y.Z with no leading zeros, got '$v'"
a=${BASH_REMATCH[1]}; b=${BASH_REMATCH[2]}; c=${BASH_REMATCH[3]}
[ "$b" -lt 100 ] && [ "$c" -lt 100 ] || die "minor and patch must be below 100, got '$v'"
code=$((a * 10000 + b * 100 + c))
[ "$(git branch --show-current)" = main ] || die "not on main (on '$(git branch --show-current)')"
[ -z "$(git status --porcelain)" ] || die "the tree is not clean: commit or stash first"$'\n'"$(git status --short)"
# Tags pushed from elsewhere count too. No TTY here: never prompt (ssh passphrase, https credentials).
asof=
GIT_TERMINAL_PROMPT=0 GIT_SSH_COMMAND="ssh -o BatchMode=yes" timeout 30 git fetch -q --tags origin 2>/dev/null || {
  asof=" (as last fetched)"; echo "release-local: warning: could not fetch from origin; comparing with local tags and origin/main only"; }
last=$(git tag --list 'v[0-9]*' --sort=-v:refname | head -1)
if [ -n "$last" ]; then
  [[ "$last" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || die "newest tag '$last' is not vX.Y.Z"
  lastcode=$((10#${BASH_REMATCH[1]} * 10000 + 10#${BASH_REMATCH[2]} * 100 + 10#${BASH_REMATCH[3]}))
  [ "$code" -gt "$lastcode" ] || die "version $v has versionCode $code, not above $last ($lastcode); codes only go up"
  echo "release-local: version $v, versionCode $code (newest tag $last = $lastcode)"
else
  echo "release-local: version $v, versionCode $code (no v* tag yet)"
fi
scripts/check-whatsnew.sh "$v"

problems=0
problem() { echo "release-local: $*" >&2; problems=$((problems + 1)); }
git merge-base --is-ancestor HEAD origin/main 2>/dev/null ||
  problem "HEAD $(git rev-parse --short HEAD) is not on origin/main$asof: the owner pushes first (! git push origin main)"
if [ ! -f "$KS" ]; then
  problem "no upload keystore at $KS. The owner creates it in a terminal (it prompts for the passwords), then backs
  the .jks up next to the password:
    keytool -genkeypair -v -keystore ~/.android/borderline-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000"
fi
norm() { tr -d ' \t\r\n' | tr a-f A-F; }
if [ -f "$PIN" ]; then
  pin=$(norm < "$PIN")
  [[ "$pin" =~ ^([0-9A-F]{2}:){31}[0-9A-F]{2}$ ]] || problem "$PIN must hold one SHA-256 fingerprint (AA:BB:… 32 bytes), got '$pin'"
else
  pin=; echo "release-local: warning: no $PIN yet; the signer's fingerprint will be printed for the owner to pin"
fi
command -v zenity >/dev/null || problem "zenity is missing (it asks the owner for the keystore password)"
[ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ] || problem "no DISPLAY/WAYLAND_DISPLAY: the zenity password dialog can't open"
for t in java jarsigner keytool; do [ -x "$JAVA_HOME/bin/$t" ] || problem "$JAVA_HOME/bin/$t is missing"; done
[ -f "$BT" ] || problem "$BT is missing (it reads the bundle's manifest)"
# The suggestion-report URL comes from BF_REPORT_URL or bfReportUrl in gradle.properties, resolved as app/build.gradle.kts
# does; a Play build must have one (empty would silently ship a copy-only dialog).
report=${BF_REPORT_URL:-}; [[ "$report" =~ [^[:space:]] ]] ||
  report=$(sed -n 's/^bfReportUrl[[:space:]]*=//p' "${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties" 2>/dev/null | tail -1)
report=${report#"${report%%[![:space:]]*}"}; report=${report%"${report##*[![:space:]]}"}
if scripts/check-report-url.sh "$report" >/dev/null; then
  scripts/check-report-url.sh "$report" live "$v" || problem "the report URL does not answer 200 with the dialog's query: $report"
else problem "no valid report URL: set BF_REPORT_URL or bfReportUrl in ~/.gradle/gradle.properties (https, printable ASCII)"; fi
command -v zip >/dev/null && command -v unzip >/dev/null || problem "zip and unzip are needed to strip the debug signature"

# Disk estimate, MB. MODELS: staged Whisper turbo int8 (~1.1 GB from its 0.54 GB pinned archive) + 17 Piper voices
# (0.4 GB of pinned archives) with espeak-ng-data. BUNDLE: the models plus ~0.1 GB of app. Peak is the Gradle step:
# the staged models + two model copies in the asset-pack/bundle intermediates + the AAB + ~2 GB of non-model release
# intermediates (app/build of a debug build measured 1.9 GB) + margin. Fetching (~2.7 GB peak) and signing (unsigned
# + signed AAB, after the intermediates are deleted) stay below that.
MODELS_MB=1600 BUNDLE_MB=1700 CODE_MB=2000 MARGIN_MB=500
need=${RELEASE_NEED_MB:-$((MODELS_MB + 2 * BUNDLE_MB + BUNDLE_MB + CODE_MB + MARGIN_MB))}
outneed=${RELEASE_OUT_NEED_MB:-$((2 * BUNDLE_MB + MARGIN_MB))}
how="estimate: models $MODELS_MB + 3 x bundle $BUNDLE_MB + intermediates $CODE_MB + margin $MARGIN_MB; override with RELEASE_NEED_MB"
[ -z "${RELEASE_NEED_MB:-}" ] || how="set by RELEASE_NEED_MB"
free_mb() { df -Pm "$1" | awk 'NR==2 {print $4}'; }
outdir=$OUT; [ -d "$outdir" ] || outdir=$HOME
have=$(free_mb .)
echo "release-local: disk: repo filesystem has ${have} MB free, needs ${need} MB ($how)"
[ "$have" -ge "$need" ] || problem "not enough disk on the repo's filesystem: ${have} MB free, ${need} MB needed"
if [ "$(stat -c %d "$outdir")" != "$(stat -c %d .)" ]; then
  ohave=$(free_mb "$outdir")
  echo "release-local: disk: $OUT filesystem has ${ohave} MB free, needs ${outneed} MB (2 x bundle + margin; override with RELEASE_OUT_NEED_MB)"
  [ "$ohave" -ge "$outneed" ] || problem "not enough disk for $OUT: ${ohave} MB free, ${outneed} MB needed"
else
  echo "release-local: disk: $OUT is on the same filesystem (covered by the figure above)"
fi
for f in "$AAB" "$UNSIGNED"; do [ ! -e "$f" ] || problem "$f already exists; move it away first"; done
[ "$problems" -eq 0 ] || die "preflight failed ($problems problem(s)), nothing built"
echo "release-local: preflight ok"
[ -z "$check" ] || exit 0

# --- Build. Everything staged or built here is gitignored and deleted on exit, success or not.
work=build/release-work
# Exactly what fetch-models.sh/stage-models.sh stage (.gitignore: packs/core_*/…/models/* but PLACEHOLDER.txt,
# packs/lang_*/…/tts/) and what Gradle builds for the packs (packs/*/build). Other ignored files under packs/ stay.
clean_staged() {
  local f
  for f in packs/core_*/src/main/assets/models/*; do [ "${f##*/}" = PLACEHOLDER.txt ] || rm -rf "$f"; done
  rm -rf packs/lang_*/src/main/assets/tts packs/*/build
}
cleanup() {
  set +e
  rm -rf "$work"
  clean_staged
  # Release-variant dirs only (intermediates/*/release*, outputs/bundle/release, tmp|kotlin/*Release*); the debug
  # build stays. Also takes release intermediates of an earlier release build, which is the point.
  find app/build -type d -name '*[Rr]elease*' -prune -exec rm -rf {} + 2>/dev/null
  echo "release-local: cleaned staged models and release intermediates; repo filesystem has $(free_mb .) MB free"
}
echo "release-local: disk before: $(free_mb .) MB free"
clean_staged   # stale models/voices from an interrupted run must never ship
trap cleanup EXIT
# PACKS and HELD_BACK are the only variables it reads: force the repo's packs and gradle.properties' held-back list.
env -u HELD_BACK PACKS=packs scripts/fetch-models.sh "$work" 9>&-
env -u UPLOAD_KEYSTORE -u UPLOAD_STORE_PASSWORD -u UPLOAD_KEY_PASSWORD \
  ./gradlew :app:testDebugUnitTest :app:bundleRelease -PversionCode="$code" -PversionName="$v" 9>&-
mkdir -p "$OUT"
mv app/build/outputs/bundle/release/app-release.aab "$UNSIGNED"

scripts/check-manifest.sh "$UNSIGNED" "$BT" || die "manifest check failed"
scripts/check-report-url.sh "$report" aab "$UNSIGNED" || die "the bundle does not carry the report URL"
trap - EXIT; cleanup; set -e

# Strip the debug signature (top-level META-INF only; base/root/META-INF is app content), as release.yml does.
zip -qd "$UNSIGNED" 'META-INF/MANIFEST.MF' 'META-INF/*.SF' 'META-INF/*.RSA' 'META-INF/*.EC' 'META-INF/*.DSA' || [ $? -eq 12 ]
verify=$("$JAVA_HOME/bin/jarsigner" -verify "$UNSIGNED" 2>&1 || true)
grep -q '^jar is unsigned' <<<"$verify" || die "$UNSIGNED is still signed after stripping: $verify"
! unzip -Z1 "$UNSIGNED" | grep -Eq '^META-INF/[^/]+\.(SF|RSA|EC|DSA)$' || die "signature files left in $UNSIGNED's META-INF"

# Passwords: zenity -> shell variable -> jarsigner's environment only. `-storepass:env SP` puts the NAME on argv, not
# the value. Cancel or an empty answer stops, leaving the unsigned AAB for signing by hand (SKILL.md step 7).
# Known risk, accepted: jarsigner's environment is readable by same-user processes (/proc/PID/environ), as is
# anything typed into the dialog by a same-user keylogger or ptrace.
byhand="unsigned AAB left at $UNSIGNED; to sign by hand: jarsigner -keystore ~/.android/borderline-upload.jks -sigalg SHA256withRSA -digestalg SHA-256 -signedjar $AAB $UNSIGNED upload"
ask() { local p; p=$(zenity --password --title="Borderline Fluent $v: $1") || die "password dialog cancelled; $byhand"
  [ -n "$p" ] || die "empty password; $byhand"; printf %s "$p"; }
SP=$(ask "upload keystore password")
KP=$SP
# Never touches a file it didn't create: $AAB must not exist before the attempt, so a leftover after a failed
# attempt is this attempt's partial output.
sign() {
  [ ! -e "$AAB" ] && [ ! -L "$AAB" ] || die "$AAB already exists (created by someone else since the preflight); not overwriting it"
  SP=$SP KP=$KP "$JAVA_HOME/bin/jarsigner" -keystore "$KS" -storepass:env SP -keypass:env KP \
    -sigalg SHA256withRSA -digestalg SHA-256 -signedjar "$AAB" "$UNSIGNED" upload && return 0
  rm -f "$AAB"; return 1
}
if ! sign; then
  KP=$(ask "key password for alias 'upload'")
  sign || { unset SP KP; die "signing failed (wrong password?); $byhand"; }
fi
unset SP KP

"$JAVA_HOME/bin/jarsigner" -verify "$AAB" | grep -q '^jar verified' || die "jarsigner -verify did not print 'jar verified.' for $AAB"
echo "release-local: jar verified"
fp=$("$JAVA_HOME/bin/keytool" -printcert -jarfile "$AAB" | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p') ||
  die "keytool could not read $AAB"
[ -n "$fp" ] || die "keytool printed no SHA256 fingerprint for $AAB"
[ "$(grep -c . <<<"$fp")" -eq 1 ] || die "expected exactly one signer, got: $fp"
got=$(norm <<<"$fp")
echo "release-local: upload certificate SHA256: $got"
if [ -n "$pin" ]; then
  [ "$got" = "$pin" ] || { rm -f "$AAB"; die "signed with the WRONG key: signer $got, pinned in $PIN $pin; deleted $AAB, $byhand"; }
  echo "release-local: matches the pinned upload key ($PIN)"
else
  echo "release-local: owner: pin this key: printf '%s\n' '$got' > $PIN, check it against Play Console → App integrity → Upload key certificate, commit it"
fi
rm -f "$UNSIGNED"
# Known risk, accepted: a same-user process could swap $AAB after these checks; the owner compares the sha256 below.
ls -la "$AAB"
echo "release-local: sha256 $(sha256sum "$AAB" | cut -d' ' -f1)  $AAB"
echo "release-local: done: $AAB ($(du -m "$AAB" | cut -f1) MB), versionCode $code; disk now $(free_mb .) MB free"
echo "release-local: next (owner): upload to Play internal testing, then tag v$v on this commit and push it so the code is recorded"
