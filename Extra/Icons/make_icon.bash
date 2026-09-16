#!/usr/bin/env bash
#
# Build hieroglyph.icns from the SVG master.
#
# Two things this script is careful about:
#
#  * Rasterising is done by sips (CoreGraphics), not by ImageMagick. `sips -s format
#    png --resampleHeight N` renders the *vector* at the requested size — it is not an
#    upscale of the intrinsic size — and it honours gradients and opacity. ImageMagick's
#    built-in SVG renderer silently drops gradient fills (it renders them black) unless
#    librsvg is installed, so it is used here only for raster compositing.
#
#  * The image is made square by changing the WIDTH only. The height is fixed at the
#    target size and the width is padded (or cropped) symmetrically with transparency.
#    Nothing is ever stretched: an earlier version used `sips -z N N`, which forces both
#    dimensions and distorted this non-square artwork by about 10%.

set -euo pipefail

cd "$(dirname "$0")"

SRC="${1:-hieroglyph-male.svg}"
ICONSET="hieroglyph.iconset"
ICNS="hieroglyph.icns"
MASTER=1024          # largest size macOS asks for (icon_512x512@2x)

[[ -f "$SRC" ]] || { echo "error: source not found: $SRC" >&2; exit 1; }
command -v magick >/dev/null 2>&1 || {
  echo "error: ImageMagick is required (brew install imagemagick)" >&2; exit 1; }

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# 1. Rasterise the SVG at full height. sips insists on a real file extension, so the
#    source is copied into the temp dir first.
cp "$SRC" "$tmp/src.svg"
sips -s format png --resampleHeight "$MASTER" "$tmp/src.svg" --out "$tmp/tall.png" >/dev/null

# 2. Square it: height untouched, width padded/cropped to match, centred, transparent.
magick "$tmp/tall.png" \
       -background none -gravity center -extent "${MASTER}x${MASTER}" \
       "$tmp/master.png"

# 3. Emit every representation macOS expects, downscaled from the square master.
rm -rf "$ICONSET"
mkdir -p "$ICONSET"

emit() {  # emit <filename> <pixels>
  magick "$tmp/master.png" -resize "${2}x${2}" -strip "$ICONSET/$1"
  printf '  %-22s %sx%s\n' "$1" "$2" "$2"
}

echo "building $ICONSET from $SRC"
emit icon_16x16.png        16
emit icon_16x16@2x.png     32
emit icon_32x32.png        32
emit icon_32x32@2x.png     64
emit icon_128x128.png      128
emit icon_128x128@2x.png   256
emit icon_256x256.png      256
emit icon_256x256@2x.png   512
emit icon_512x512.png      512
emit icon_512x512@2x.png   1024

# 4. Assemble. iconutil refuses the set if any expected member is malformed.
iconutil -c icns "$ICONSET" -o "$ICNS"
echo "wrote $ICNS"
echo
echo "To make it the application icon, electron-builder reads electron/assets/icon.icns:"
echo "    cp $ICNS ../../electron/assets/icon.icns"
