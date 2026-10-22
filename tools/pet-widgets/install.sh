#!/bin/sh
# Writes the Pet home and Steps widgets into the launcher's widgets folder, the HGSS font embedded.
set -e
here=$(cd "$(dirname "$0")" && pwd)
font=$(base64 -w0 "$here/hgss.ttf")
for w in pet-home steps; do
  sed "s|FONT_B64|$font|" "$here/$w.owl" > "$HOME/.config/omalauncher/widgets/$w.owl"
done
