# Omalauncher Boss — development context

Read README.md and docs/INSTALL.md before meaningful work. If the shared project
folder ~/ClaudeSync/projects/omalauncher exists, also read its CLAUDE.md, BRIEF.md,
CURRENT-STATE.md and latest CREW-LOG.md entries. Follow applicable local/parent
instructions. Shared notes and private runtime data are not bundled into the APK.

Check Git status before editing and preserve other agents' changes. For concurrent
Claude/Codex work use separate branches/worktrees. Keep source in its Git checkout;
record signed shared project updates in ClaudeSync when available. Personal session
handoffs belong in the agent's own home.

The Fold workflow is documented in docs/FOLD-DEVELOPMENT.md on the phone development
branch. Its helper source is tools/fold-development/. Phone edits synchronize to a
separate branch and Mac mirror; do not assume they merge into this active branch.
Private signing keys, agent logins and per-device setup must stay outside Git.
