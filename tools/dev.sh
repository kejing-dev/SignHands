#!/usr/bin/env bash
# Sign Hands dev helper. SERIAL defaults to the factory emulator assigned to sign-hands (set SERIAL=... to override).
#   bash tools/dev.sh build            assembleDebug
#   bash tools/dev.sh install          install -r on $SERIAL (never launches on a headset)
#   bash tools/dev.sh launch           monkey launch (emulator only)
#   bash tools/dev.sh cmd "<command>"  debug broadcast, e.g. cmd "letter G stage=follow"
#   bash tools/dev.sh shot <out.png>   emulator console screenshot (taken twice: the first frame can be stale)
#   bash tools/dev.sh log              logcat -s SignHands
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
SERIAL="${SERIAL:?set SERIAL to the emulator assigned in state.json}"
PKG=io.embodit.signhands
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
case "${1:-}" in
  build) (cd "$ROOT" && ./gradlew --offline :app:assembleDebug) ;;
  install) "$ADB" -s "$SERIAL" install -r "$APK" ;;
  launch)
    [[ "$SERIAL" == emulator-* ]] || { echo "refusing to launch on a headset"; exit 1; }
    "$ADB" -s "$SERIAL" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 ;;
  cmd) "$ADB" -s "$SERIAL" shell am broadcast -a $PKG.DEBUG -p $PKG --es cmd "'$2'" >/dev/null && echo "sent: $2" ;;
  shot)
    out="${2:-shot.png}"
    "$ADB" -s "$SERIAL" emu screenrecord screenshot "$(cygpath -w "$(dirname "$out")" 2>/dev/null || dirname "$out")" >/dev/null 2>&1 || true
    sleep 1
    "$ADB" -s "$SERIAL" exec-out screencap -p > "$out"
    "$ADB" -s "$SERIAL" exec-out screencap -p > "$out" ;;
  log) "$ADB" -s "$SERIAL" logcat -s SignHands ;;
  *) sed -n 2,9p "$0" ;;
esac
