# Open bugs and the new-phone plan (written 2026-10-14, 04:40 PDT, by the agent on the old Fold)

Boss moves to a new, higher-RAM phone tomorrow (2026-10-14/15). Everything below is on GitHub
(`phone/fold-development`); the newest state is in `docs/PHONE-HANDOFF.md` (checkpoint 2026-10-14) and the top of
`docs/PHONE-TEST-RESULTS.md`. Fix these on the new phone. Highest first.

## Bugs not fixed
1. ~~**Mac background jobs die when their turn ends.**~~ FIXED 2026-10-14 by the Mac session: a Claude thread keeps
   its process while background work runs and the next message goes into it ("As built: background work outlives
   the turn" in `docs/MAC-RUNNER-SPEC.md`). Installed on the Mac runner.
2. **Codex in auto mode can read the runner key** (its sandbox allows reads), so it could make or message threads
   without a card. Interim, 2026-10-14 (2b4a4ae): Codex threads are manual unless auto is asked for by name. Moving
   the file does not help (Codex reads anything this Mac user can); the fix is a place it cannot read (Keychain).
3. **`oma-phone fill` on the Oma chat field: cause unknown.** It said ok and the text never landed (a placeholder child
   inside the field matched the label; the field already resolved to its editable parent, so the likelier cause is
   the per-tab draft overwriting a text set from outside). Since 41eeb49 fill reads the field back and answers
   `ok: false`. Still to do: make text set by accessibility stick in the composer (update the tab's draft from the
   field's own value) and confirm on the phone.
4. **`type`/`click` say "Screen changed" while the chat streams**: the target's bounds move between read and act.
   Needs a re-find of the same control by id+label at act time, without ever hitting a different control.
5. **Phone control does not rebind by itself after most launcher updates**; `oma-install` heals it (BOUND / HEALED).
   Find why the accessibility service is not rebound on package replace.
6. **Builder health read "alive: false" for the running builder** on the first status after an update; right a
   minute later. A false "dead" must never invite a restart.
7. **Front agent misroutes, rare:** a plain question handed to a builder (about 2 in 100); "thanks" while a builder
   works sometimes handed over. Harmless but slow.
8. **The Oma prompt still names `oma-agents status`** (works as an alias; should say `oma status`).
9. ~~**`note` events from the runner** (background-task notices) reach the phone and show nothing yet.~~ BUILT
   2026-10-14 by a Mac session, **not yet seen on a screen** (Boss was changing phones; nothing installed). A `note`
   during a turn is a step line of its entry; after the answer, or between his messages, it is a quiet `·` line under
   his newest entry in that thread. Claude Code's own report between his messages (its text, steps and any card it
   asks for) lands there too instead of being dropped; words sent to the thread from elsewhere are marked "Sent from
   elsewhere: …" so their answer does not read as his. `background` from `/threads` shows on the tile and dot label as
   "running on the Mac" with "running on the Mac: copy the games" as its line, and as a quiet line at the bottom of
   the thread's tab. Unit tested (MacRunnerTest, MacThreadsTest, AgentThreadsTest, ChatHistoryTest). To check on the
   new phone: start background work from a Mac tab, watch the "Still running…" line, the tile label, and the report.
10. **Mac work is not on the oma tile, ring or lock widget** (they read the phone builders only).
11. **A thread tab shows only what the phone sent**: "Earlier work on the Mac is not shown". Fetch the thread's events.
12. **The Claude→Codex→Cursor limit memory (15 min) is lost on a launcher restart.**
13. **A ＋ chat needs the phone's Linux to send** (the front agent runs there) although its work is the Mac's.
14. **Design question for Boss:** a ＋ chat he named but never wrote in blocks ＋ (＋ reopens it instead of a new one).
15. **Two phone builders share one checkout**; the "use your own worktree" note is advice, not enforced.

## Built but never seen on a screen (check on the new phone)
＋ starting in `~/Projects` without a question; the Tailscale self-heal (after a reboot) and its respect for a
manual off; "The Mac ended this turn without an answer." + try again; Restart / Clean up rows in the tab menu; tile
captions "MAC · NOT STARTED" / "OUT OF REACH"; the 12-hour archive of idle chats; Mac threads in auto mode (needs
`oma-runner install` on the Mac first); two phone builders working at once with the worktree note.

## Setting up the new phone (what is NOT in git)
- **Signing key**: `/root/.config/oma-development/working-debug.keystore` on the old phone (and `working-cert.sha256`).
  The Mac's backup copies Oma's data including it. Put it back at the same path on the new phone's Linux before the
  first `oma-build`, or in-place updates there will be refused (a fresh install with a new key also works, but then
  that new key is the one to keep). Never commit it.
- **Oma app data** (pages, notes, stickers placed, chats, looks): in the Mac's Oma backup; restore per the backup
  thread's instructions. Sticker pictures can also be re-copied from ClaudeSync `projects/pond`.
- **Mac runner key**: `~/.config/omalauncher/mac-runner.json` — the Mac session writes it again.
- **Phone grants Boss gave by hand on this phone**: Accessibility (phone control), WRITE_SECURE_SETTINGS over adb
  (lock-screen paper), battery Unrestricted (asked, not confirmed), wireless debugging, Tailscale logged in.
- **Changed for Boss on this phone, not carried over**: screen timeout "never".
- After the first build on the new phone: `oma-build`, `oma-dev --no-open`, then `oma-try`; then `oma status`,
  `oma threads`, and the list above.
