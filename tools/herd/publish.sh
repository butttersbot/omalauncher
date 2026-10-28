#!/bin/sh
# One source for the herd pet (Boss, 2026-10-28: Omarchy's pet had no Tailshare tab; three copies had drifted): the page
# is omalauncher's app/src/main/assets/herd/pet.html, the Linux shell tools/herd/linux/. This copies them where the
# other installs read them: ClaudeSync's herd project (the Mac's pet), or a Linux pet's own folder.
#   tools/herd/publish.sh claudesync      on the Mac Pro: into ~/ClaudeSync/projects/herd/pet
#   tools/herd/publish.sh linux           on Omarchy (MG1): into ~/.local/share/herd/pet, then restarts herd-pet
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
case "${1:-}" in
  claudesync) dest="$HOME/ClaudeSync/projects/herd/pet" ;;
  linux) dest="$HOME/.local/share/herd/pet" ;;
  *) echo "usage: $0 claudesync|linux" >&2; exit 2 ;;
esac
mkdir -p "$dest/web" "$dest/linux"
cp "$here/app/src/main/assets/herd/pet.html" "$dest/web/pet.html"
cp "$here/tools/herd/linux/herd-pet" "$here/tools/herd/linux/install" "$dest/linux/"
chmod +x "$dest/linux/herd-pet" "$dest/linux/install"
echo "published to $dest from $(git -C "$here" rev-parse --short HEAD 2>/dev/null || echo '?')"
if [ "$1" = linux ] && command -v systemctl >/dev/null; then systemctl --user restart herd-pet.service && echo "herd-pet restarted"; fi
