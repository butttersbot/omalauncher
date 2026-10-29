#!/bin/sh
# Installs the soma-design skill for Claude Code on this machine: ~/.claude/skills/soma-design (SKILL.md) and
# soma-theme on PATH (~/.local/bin). Run from an omalauncher checkout on each machine (Mac Pro, MacBook, MG1, Fold):
#   sh tools/design-system/skill/install.sh
# Idempotent; it replaces only what it installed. Nothing else is touched (no credentials, no settings).
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
skill="$HOME/.claude/skills/soma-design"
mkdir -p "$skill" "$HOME/.local/bin"
cp "$here/SKILL.md" "$skill/SKILL.md"
# soma-theme needs Python 3.11+ (tomllib). A Mac's /usr/bin/python3 can be older and is all a non-login shell finds,
# so the installed copy names the newest Python here that has tomllib.
py=""
for p in python3.14 python3.13 python3.12 python3.11 /opt/homebrew/bin/python3 /usr/local/bin/python3 python3; do
  if command -v "$p" >/dev/null 2>&1 && "$p" -c 'import tomllib' 2>/dev/null; then py=$(command -v "$p"); break; fi
done
[ -n "$py" ] || { echo "soma-theme needs Python 3.11 or newer; none found" >&2; exit 1; }
{ echo "#!$py"; tail -n +2 "$here/soma-theme"; } > "$HOME/.local/bin/soma-theme"
chmod 755 "$HOME/.local/bin/soma-theme"
{ echo "#!$py"; tail -n +2 "$here/soma-theme"; } > "$skill/soma-theme"
echo "installed: $skill/SKILL.md and $HOME/.local/bin/soma-theme"
command -v soma-theme >/dev/null || echo "note: ~/.local/bin is not on PATH here; run $HOME/.local/bin/soma-theme"
