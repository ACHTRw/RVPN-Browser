#!/usr/bin/env bash
# Verify a built RVPN Browser APK without a device.
#
#   android/tools/verify-apk.sh app/build/outputs/apk/release/app-release-unsigned.apk
#   android/tools/verify-apk.sh dist/rvpn-browser-1.0.0-android.apk
#
# Checks: package identity, SDK levels, permissions, the RVPN components
# (activity/service in its own process), the bundled Go engines for every ABI,
# and - for signed APKs - the signing certificate.

set -uo pipefail

APK="${1:-}"
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "usage: $0 <apk>" >&2
  exit 2
fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
AAPT2="$BUILD_TOOLS/aapt2"
APKSIGNER="$BUILD_TOOLS/apksigner"
[ -x "$AAPT2" ] || { echo "!! aapt2 not found under $SDK/build-tools" >&2; exit 2; }

fails=0
ok()   { printf '  \033[32mOK\033[0m   %s\n' "$1"; }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$1"; fails=$((fails+1)); }
want() { if [ "$2" = "$3" ]; then ok "$1 ($2)"; else bad "$1: got '$2', want '$3'"; fi; }
has()  { if grep -q -- "$2" <<<"$1"; then ok "$3"; else bad "$3"; fi; }

echo "== APK: $APK ($(du -h "$APK" | cut -f1))"

BADGING="$("$AAPT2" dump badging "$APK" 2>/dev/null)"
want "package name" "$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$BADGING")" "com.rvpnbrowser.browser"
want "versionName" "$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$BADGING" | head -1)" "1.0.0"
want "minSdkVersion" "$(sed -n "s/^minSdkVersion:'\([^']*\)'.*/\1/p" <<<"$BADGING")" "26"
want "targetSdkVersion" "$(sed -n "s/^targetSdkVersion:'\([^']*\)'.*/\1/p" <<<"$BADGING")" "35"

PERMS="$("$AAPT2" dump permissions "$APK" 2>/dev/null)"
has "$PERMS" "android.permission.INTERNET" "INTERNET permission"
has "$PERMS" "android.permission.FOREGROUND_SERVICE" "FOREGROUND_SERVICE permission"
has "$PERMS" "android.permission.FOREGROUND_SERVICE_DATA_SYNC" "dataSync foreground service permission"

MANIFEST="$("$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" 2>/dev/null)"
has "$MANIFEST" "RvpnSettingsActivity" "RVPN settings activity declared"
has "$MANIFEST" "RvpnService" "RVPN engine service declared"
has "$MANIFEST" ":rvpn" "engine runs in its own process (:rvpn)"
has "$MANIFEST" "foregroundServiceType" "service is a foreground service"

LIBS="$(unzip -l "$APK" 2>/dev/null | awk '/lib\/.*libgojni\.so/ {print $4}')"
for abi in arm64-v8a armeabi-v7a x86_64; do
  has "$LIBS" "lib/$abi/libgojni.so" "Go engine bundled for $abi"
done
DEX="$(unzip -l "$APK" 2>/dev/null | grep -c 'classes.*\.dex')"
if [ "$DEX" -ge 1 ]; then ok "$DEX dex file(s)"; else bad "no dex file"; fi

if [ -x "$APKSIGNER" ] && "$APKSIGNER" verify "$APK" >/dev/null 2>&1; then
  CERT="$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | grep -m1 'SHA-256 digest')"
  ok "APK signature valid${CERT:+ ($CERT)}"
else
  echo "  --   unsigned (expected for *-unsigned.apk; sign it with apksigner before installing)"
fi

echo
if [ "$fails" = "0" ]; then echo "all checks passed"; else echo "$fails check(s) failed"; fi
exit $(( fails > 0 ))
