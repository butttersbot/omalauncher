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
cp "$here/soma-theme" "$skill/soma-theme"
install -m 755 "$here/soma-theme" "$HOME/.local/bin/soma-theme"
echo "installed: $skill/SKILL.md and $HOME/.local/bin/soma-theme"
command -v soma-theme >/dev/null || echo "note: ~/.local/bin is not on PATH here; run $HOME/.local/bin/soma-theme"
