#!/usr/bin/env bash
#
# RVPN Browser - one-command Android build.
#
#   ./build.sh                 # copy the engine AAR, then assembleDebug + assembleRelease
#   ./build.sh --icons         # regenerate the launcher icons first, then build
#   ./build.sh clean assembleDebug
#                              # extra arguments are forwarded to Gradle
#
# Environment (all optional, sensible defaults are detected):
#   ANDROID_HOME / ANDROID_SDK_ROOT   Android SDK location
#   JAVA_HOME                         JDK 17+ (Gradle 8.9 / AGP 8.7 requirement)
#   RVPN_KEYSTORE, RVPN_KEYSTORE_PASSWORD, RVPN_KEY_ALIAS, RVPN_KEY_PASSWORD
#                                     optional release signing material
#
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
cd "$SCRIPT_DIR"

if [ -t 1 ]; then BOLD=$'\033[1m'; BLUE=$'\033[1;34m'; RED=$'\033[1;31m'; YEL=$'\033[1;33m'; OFF=$'\033[0m'
else BOLD=""; BLUE=""; RED=""; YEL=""; OFF=""; fi
log()  { printf '%s[build]%s %s\n' "$BLUE" "$OFF" "$*"; }
warn() { printf '%s[build] warning:%s %s\n' "$YEL" "$OFF" "$*" >&2; }
die()  { printf '%s[build] ERROR:%s %s\n' "$RED" "$OFF" "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 1. Optional: regenerate the launcher icons
# ---------------------------------------------------------------------------
GRADLE_TASKS=()
for arg in "$@"; do
    if [ "$arg" = "--icons" ]; then
        log "regenerating launcher icons"
        "$SCRIPT_DIR/tools/gen_icons.sh"
    else
        GRADLE_TASKS+=("$arg")
    fi
done
if [ ${#GRADLE_TASKS[@]} -eq 0 ]; then
    GRADLE_TASKS=(assembleDebug assembleRelease)
fi

# ---------------------------------------------------------------------------
# 2. Java
# ---------------------------------------------------------------------------
if [ -z "${JAVA_HOME:-}" ]; then
    for cand in /usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/java-17-openjdk-amd64 \
                /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-17-openjdk \
                /usr/lib/jvm/default-java /opt/java/openjdk; do
        if [ -x "$cand/bin/javac" ]; then JAVA_HOME="$cand"; break; fi
    done
fi
if [ -z "${JAVA_HOME:-}" ] && command -v javac >/dev/null 2>&1; then
    JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
fi
[ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ] || \
    die "no JDK found. Install JDK 17 or newer (e.g. sudo apt-get install -y openjdk-17-jdk) and/or export JAVA_HOME."
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

JAVA_MAJOR=$("$JAVA_HOME/bin/javac" -version 2>&1 | sed -E 's/^javac ([0-9]+).*/\1/')
case "$JAVA_MAJOR" in
    ''|*[!0-9]*) warn "could not determine the javac version; continuing" ;;
    *) if [ "$JAVA_MAJOR" -lt 17 ]; then
           die "JDK $JAVA_MAJOR found at $JAVA_HOME but AGP 8.7 / Gradle 8.9 need JDK 17 or newer."
       fi ;;
esac
log "JAVA_HOME=$JAVA_HOME (javac $JAVA_MAJOR)"

# ---------------------------------------------------------------------------
# 3. Android SDK
# ---------------------------------------------------------------------------
if [ -z "${ANDROID_HOME:-}" ]; then
    ANDROID_HOME="${ANDROID_SDK_ROOT:-}"
fi
if [ -z "$ANDROID_HOME" ]; then
    for cand in "$HOME/Android/Sdk" "$HOME/android-sdk" "$HOME/Android/sdk" \
                /opt/android-sdk /opt/android-sdk-linux /usr/lib/android-sdk; do
        if [ -d "$cand/platforms" ] || [ -d "$cand/cmdline-tools" ]; then ANDROID_HOME="$cand"; break; fi
    done
fi
[ -n "$ANDROID_HOME" ] || die "Android SDK not found.
  Install the command line tools, then:
    sdkmanager --install 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
  and export ANDROID_HOME=/path/to/android-sdk (or write sdk.dir=... into local.properties)."
[ -d "$ANDROID_HOME" ] || die "ANDROID_HOME=$ANDROID_HOME does not exist."
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
log "ANDROID_HOME=$ANDROID_HOME"

if [ ! -d "$ANDROID_HOME/platforms/android-35" ]; then
    warn "platform android-35 is not installed; Gradle will try to download it (licences must be accepted)."
fi

if [ ! -f local.properties ]; then
    printf 'sdk.dir=%s\n' "$(printf '%s' "$ANDROID_HOME" | sed 's/\\/\\\\/g')" > local.properties
    log "wrote local.properties (sdk.dir=$ANDROID_HOME)"
fi

# ---------------------------------------------------------------------------
# 4. RVPN engine AAR
# ---------------------------------------------------------------------------
AAR_SRC="$SCRIPT_DIR/../core/build/rvpncore.aar"
AAR_DST="$SCRIPT_DIR/app/libs/rvpncore.aar"
mkdir -p "$SCRIPT_DIR/app/libs"

if [ -f "$AAR_SRC" ]; then
    if [ -f "$AAR_DST" ] && cmp -s "$AAR_SRC" "$AAR_DST"; then
        log "engine AAR up to date: app/libs/rvpncore.aar"
    else
        cp -f "$AAR_SRC" "$AAR_DST"
        log "copied $(realpath --relative-to="$SCRIPT_DIR" "$AAR_SRC" 2>/dev/null || printf '%s' "$AAR_SRC") -> app/libs/rvpncore.aar"
    fi
elif [ -f "$AAR_DST" ]; then
    log "using the existing app/libs/rvpncore.aar (no $AAR_SRC found)"
else
    die "RVPN engine AAR not found.
  Expected either:
    $AAR_SRC        (produced by the Go core build), or
    $AAR_DST        (checked in / copied manually).
  Build it with gomobile, for example:
    cd ../core
    gomobile bind -target=android/arm64,android/arm,android/amd64 -androidapi 26 \\
        -javapkg=org.rvpnbrowser.core -o build/rvpncore.aar ./mobile/rvpncore"
fi

if command -v unzip >/dev/null 2>&1; then
    if ! unzip -l "$AAR_DST" 2>/dev/null | grep -q 'classes.jar'; then
        warn "app/libs/rvpncore.aar does not look like a valid AAR (no classes.jar inside)."
    fi
fi

# ---------------------------------------------------------------------------
# 5. Gradle wrapper
# ---------------------------------------------------------------------------
if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
    if command -v gradle >/dev/null 2>&1; then
        warn "gradle/wrapper/gradle-wrapper.jar is missing; falling back to the system Gradle."
        GRADLE_CMD=(gradle)
    else
        die "gradle/wrapper/gradle-wrapper.jar is missing and no system gradle was found."
    fi
else
    [ -x ./gradlew ] || chmod +x ./gradlew
    GRADLE_CMD=(./gradlew)
fi

# ---------------------------------------------------------------------------
# 6. Build
# ---------------------------------------------------------------------------
log "running: ${GRADLE_CMD[*]} ${GRADLE_TASKS[*]}"
set +e
"${GRADLE_CMD[@]}" --stacktrace "${GRADLE_TASKS[@]}"
STATUS=$?
set -e

echo
if [ $STATUS -ne 0 ]; then
    printf '%s[build] FAILED (gradle exit code %d)%s\n' "$RED" "$STATUS" "$OFF" >&2
    exit $STATUS
fi

printf '%s[build] APK output%s\n' "$BOLD" "$OFF"
FOUND=0
for apk in app/build/outputs/apk/debug/app-debug.apk \
           app/build/outputs/apk/release/app-release.apk \
           app/build/outputs/apk/release/app-release-unsigned.apk; do
    if [ -f "$apk" ]; then
        printf '  %s  (%s)\n' "$SCRIPT_DIR/$apk" "$(du -h "$apk" | cut -f1)"
        FOUND=$((FOUND + 1))
    fi
done
if [ "$FOUND" -eq 0 ]; then
    warn "the build reported success but no APK was found under app/build/outputs/apk/"
fi
log "done."
