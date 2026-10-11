#!/usr/bin/env bash
#
# Regenerates the launcher icon set from the master vector artwork.
#
#   tools/gen_icons.sh
#
# Source selection (first match wins, override with RVPN_ICON_SVG):
#   1. $RVPN_ICON_SVG
#   2. <repo>/icon.svg                      (rvpn-browser/icon.svg)
#   3. <workspace>/icon.svg                 (one level above the repo)
#   4. <repo>/browser/extension/icons/icon128.png   (raster fallback, Lanczos)
#
# Output:
#   app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png        48/72/96/144/192
#   app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_round.png  same sizes, circular
#
# The adaptive icon (mipmap-anydpi-v26/ic_launcher.xml) uses the hand-written
# vector drawable res/drawable/ic_launcher_foreground.xml, which carries the
# very same path data as the master SVG scaled to 66 % and centred, plus the
# background colour res/values/colors.xml -> ic_launcher_background.
#
# Requirements: rsvg-convert (librsvg2-bin) for SVG input, ImageMagick for the
# raster fallback and for the round variant compositing.
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ANDROID_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
REPO_DIR=$(cd "$ANDROID_DIR/.." && pwd)
RES_DIR="$ANDROID_DIR/app/src/main/res"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
log() { printf '[icons] %s\n' "$*"; }

# --- locate the master artwork ---------------------------------------------
MASTER=""
for cand in "${RVPN_ICON_SVG:-}" "$REPO_DIR/icon.svg" "$(dirname "$REPO_DIR")/icon.svg" \
            "$REPO_DIR/browser/extension/icons/icon128.png"; do
    [ -n "$cand" ] && [ -f "$cand" ] && { MASTER="$cand"; break; }
done
[ -n "$MASTER" ] || die "no master artwork found. Set RVPN_ICON_SVG=/path/to/icon.svg"
log "master artwork: $MASTER"

case "$MASTER" in
    *.svg|*.SVG)
        command -v rsvg-convert >/dev/null 2>&1 || \
            die "rsvg-convert is required for SVG input: sudo apt-get install -y librsvg2-bin"
        MODE=svg ;;
    *)
        command -v magick >/dev/null 2>&1 || command -v convert >/dev/null 2>&1 || \
            die "ImageMagick is required for raster input: sudo apt-get install -y imagemagick"
        MODE=raster ;;
esac
command -v magick >/dev/null 2>&1 && IM="magick" || IM="convert"
command -v magick >/dev/null 2>&1 || command -v convert >/dev/null 2>&1 || \
    die "ImageMagick is required for the round variant and verification."

# render <pixels> <output.png>  - artwork only, transparent background
render() {
    local size="$1" out="$2"
    if [ "$MODE" = svg ]; then
        rsvg-convert -w "$size" -h "$size" -o "$out" "$MASTER"
    else
        "$IM" "$MASTER" -background none -filter Lanczos -resize "${size}x${size}" \
              -gravity center -extent "${size}x${size}" "$out"
    fi
}

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

DENSITIES=(mdpi hdpi xhdpi xxhdpi xxxhdpi)
SIZES=(48 72 96 144 192)
ROUND_SCALE=72   # percent of the canvas occupied by the artwork in ic_launcher_round

declare -a REPORT=()
for i in "${!DENSITIES[@]}"; do
    d="${DENSITIES[$i]}"; n="${SIZES[$i]}"
    dir="$RES_DIR/mipmap-$d"
    mkdir -p "$dir"

    render "$n" "$dir/ic_launcher.png"

    # Round variant: white filled circle + the artwork centred at ROUND_SCALE %.
    rs=$(( n * ROUND_SCALE / 100 ))
    render "$rs" "$TMP/art_$d.png"
    "$IM" -size "${n}x${n}" xc:none -fill white \
          -draw "circle $((n / 2)),$((n / 2)) $((n / 2)),0" "$TMP/bg_$d.png"
    "$IM" "$TMP/bg_$d.png" "$TMP/art_$d.png" -gravity center -composite "$dir/ic_launcher_round.png"

    for f in "$dir/ic_launcher.png" "$dir/ic_launcher_round.png"; do
        read -r w h < <("$IM" identify -format '%w %h' "$f")
        bytes=$(stat -c '%s' "$f")
        [ "$w" = "$n" ] && [ "$h" = "$n" ] || die "$f is ${w}x${h}, expected ${n}x${n}"
        [ "$bytes" -gt 200 ] || die "$f is suspiciously small ($bytes bytes)"
        REPORT+=("$(printf '%-58s %sx%s  %6s bytes' "${f#$ANDROID_DIR/}" "$w" "$h" "$bytes")")
    done
done

# --- report ----------------------------------------------------------------
printf '\n'
for line in "${REPORT[@]}"; do printf '  %s\n' "$line"; done

for extra in "$RES_DIR/mipmap-anydpi-v26/ic_launcher.xml" \
             "$RES_DIR/mipmap-anydpi-v26/ic_launcher_round.xml" \
             "$RES_DIR/drawable/ic_launcher_foreground.xml"; do
    [ -f "$extra" ] || die "missing adaptive icon resource: $extra"
    printf '  %-58s %s\n' "${extra#$ANDROID_DIR/}" "$(stat -c '%s bytes' "$extra")"
done

printf '\n[icons] done: %d PNG files regenerated.\n' "$(( ${#DENSITIES[@]} * 2 ))"
