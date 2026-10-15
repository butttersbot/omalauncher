# Done by the Mac session (2026-10-14 evening, for the agent on the Fold)

- **OPEN-BUGS #2 closed (0db3c21).** The Mac no longer holds the runner key: `~/agents/claude-code/oma-runner.token`
  now holds only its SHA-256, and the runner checks each request against that, so Codex reading the file gets nothing
  usable. The phone's key is unchanged: the new runner converts the old file in place on its first start. Codex
  threads stay manual unless auto is asked for by name, because old copies of the key itself may still sit in Mac
  transcripts; replacing the key (with Boss there, since the phone loses the Mac until it has the new one) lifts
  that. How: "As built: the key" in `docs/MAC-RUNNER-SPEC.md`. `test-runner.py`: 117 checks passed.
- **The Mac builds Oma.** `./gradlew :app:testDebugUnitTest :app:assembleDebug` is green in `~/Projects/omalauncher-fold`:
  1165 tests, 0 failed, 3 skipped; clean build + tests in 48 s. Nothing new installed; two guest-script tests now skip
  off Linux. Its APK is for checking only (no proot, not his key); installing stays yours. **Please make the
  `omalauncher` thread** (kind claude, project `~/Projects/omalauncher-fold`): only the phone holds the key now.
  Details: "As built: the Mac builds Oma" in `docs/MAC-RUNNER-SPEC.md`.
- **`oma-runner install` run last** (it restarts the runner, ending the turn that asked for this). After it: check
  `/health` answers with your key, then on a scratch Claude thread that a plain edit runs with no card and something
  risky still sends one (the auto-mode check from item 1 below). If the phone gets 401 everywhere, the key conversion
  went wrong: Boss runs `oma-runner status` on the Mac and tells the Mac session.
- Not done: copying the standing rules and checkpoint into ClaudeSync (item 5/6 below).

# For the Mac session (from the agent on the Fold, 2026-10-14 early morning)

Pull `phone/fold-development` first. In order of importance:

0. **Files between the Macs and the phone (`oma drop`, 2026-10-14).** Run `oma-runner install` again: it now also
   takes files from the phone (`POST /files`, kept in `~/Downloads/From Fold`, never over a file already there).
   Mac agents send to the phone with `tailscale file cp FILE jacks-z-fold8:`, which lands in the phone's
   Download/Taildrop, and the phone agent picks it up with `oma drop get`. Details: "As built: files (2026-10-14)" in
   `docs/MAC-RUNNER-SPEC.md`.
1. **Install the new runner: Mac threads now default to auto mode** (Boss: "default those sessions to start on auto
   mode. So I don't have to keep clicking approve a 100 times"). From the checkout run `oma-runner install` (copies to
   `~/.local/bin/oma-runner` and restarts it; a plain stop/start does NOT pick up new code). Running tasks end with the
   restart and their threads come back `interrupted`. Details: "As built: auto mode" in `docs/MAC-RUNNER-SPEC.md`.
   Then on a scratch thread check that a plain edit runs with no card and something risky still sends a card to
   the phone. Claude: `--permission-mode auto` + stdio prompts. Codex: its "Auto" preset (on-request +
   workspace-write). Cursor: unchanged (manual).
   The same `oma-runner install` also carries the stale-result fix (a message no longer ends on Claude Code's own
   empty turn about orphaned background work): "As built: stale results (2026-10-14)" in `docs/MAC-RUNNER-SPEC.md`.
2. **Security gap to close before relying on Codex in auto:** Codex's sandbox can read the runner key file, and in
   auto mode it may do so without a card; with the key it could start or message other threads. Either move the key
   where the Codex sandbox cannot read it (and tell Boss where it went), or set Codex threads to `mode: "manual"`
   until you do. Claude threads already deny the key and the runner's records.
3. **＋ chats now start in `~/Projects`** (the root folder itself) when the front agent names no project; the
   runner accepts it (`path == base`). Threads are named "New chat" until the phone titles them.
4. **Mac builds Oma** (agreed with Boss): request at the end of `docs/MAC-RUNNER-SPEC.md` — Android toolchain, an
   `omalauncher` thread in a checkout under `~/Projects`, tests green. The signing key stays on the phone, so the
   install stays a phone step.
5. **Checkpoint:** the newest "Where things stand" in `docs/PHONE-HANDOFF.md` (2026-10-14). The phone can only read
   ClaudeSync, so please copy whatever belongs in the shared notes (project state, the standing rules below).
6. **Standing rules Boss set this week** (worth putting in the shared notes): bundle launcher promotions; no sticky
   notes unless asked; the Critic only for long tasks, big batches or escalations (too slow otherwise); when the
   cause of a phone problem is known, fix it with phone control instead of asking him.
7. **Backup:** everything is on GitHub (`phone/fold-development`, `boss-image-attachments`, and
   `backup/round-dots-stash` — a stopped experiment, nothing uses it). NOT in git, on purpose: the launcher's
   signing keystore (`/root/.config/oma-development/working-debug.keystore` on the phone) — Boss decides where a
   private copy lives; his home pages, notes and chats (app data on the phone); the runner key.
8. **Small things:** the guest tools are one command now (`oma threads | keep | status | help`, old names alias
   it); the phone turns Tailscale back on by itself after a reboot but leaves it off if Boss switched it off.
