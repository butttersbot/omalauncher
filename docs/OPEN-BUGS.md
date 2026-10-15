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
   field's own value) and confirm on the phone. **Fix built 2026-10-14 evening, not yet seen on a screen:** see #17.
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
   **Seen on the real launcher 2026-10-14 16:28–16:32 (thread "Background keep-alive check", t9f70c3fc), driven
   over adb because phone control was off:** PASS — the answer came back in the tab; "· Still running on the Mac:
   Wait 90 seconds then print done…" showed under the answer; the Agents tile read "RUNNING ON THE MAC" with "running
   on the Mac: Wait 90 seconds then print done", then "DONE" after it ended; the job survived its turn (the runner's
   log: note "completed (exit code 0)" 16:31:34, Claude Code's report "The 90-second timer finished without errors and
   printed "done"." 16:31:37). FAIL — that report never reached the phone (tab still showed "Still running…" at 16:32,
   no report); the follower seems to stop at the turn's result, so events between turns are only picked up later or
   not at all. FAIL — his own first message, sent from this phone's tab, shows as "Sent from elsewhere: say hi …".
   Not seen: a separate "still running" line at the bottom of the tab. Screens: /root/.cache/bgcheck/*.png.
   **Both FAILs fixed 2026-10-14 evening (unit tested with the events above; not yet seen on a screen):**
   - *Report never reached the phone.* Cause: the follower left as soon as the thread rested with nothing of his
     unfinished (after the turn's `result` and `status idle`), and `/threads` only re-followed threads at work or owed a
     request, so the report at 16:31:37 was never read. Now a thread his requests went to is kept followed while it
     lists `background` or its last turn was under 5 minutes ago (`macAwake`; listened to a minute at a time, then
     asked again), and each `/threads` refresh also follows such threads and any whose `last_event` passed the saved
     place (`macFollowed`), catching up what came while nobody listened. A note on that work other than "Still running…"
     (completed, reported, stopped) takes the "Still running…" line off his entry.
   - *His own message as "Sent from elsewhere".* Cause: the phone had never followed that thread, so its first
     follower read the thread's whole past from event 1. The past's `message` ("say THREE", sent by curl earlier) came
     while his entry was already Running (the Mac answered `taken: now`), so that turn's words and result were taken
     as his ("THREE" under his entry), his entry ended, and his real `message` then matched nothing. The runner echoes
     no id of the phone's on `message` (only the text; it keeps its own uuid for Claude Code), so matching stays by
     exact text. Now (a) a thread never followed is followed from the newest event the phone knew of when it sends,
     and (b) a `message` with other words while his running entry has heard nothing yet puts that entry back to
     waiting for its own words, and the other turn goes under his previous entry as "Sent from elsewhere" (or
     nowhere, if there is none).
10. **Mac work is not on the oma tile, ring or lock widget** (they read the phone builders only).
11. **A thread tab shows only what the phone sent**: "Earlier work on the Mac is not shown". Fetch the thread's events.
12. **The Claude→Codex→Cursor limit memory (15 min) is lost on a launcher restart.**
13. **A ＋ chat needs the phone's Linux to send** (the front agent runs there) although its work is the Mac's.
14. **Design question for Boss:** a ＋ chat he named but never wrote in blocks ＋ (＋ reopens it instead of a new one).
15. **Two phone builders share one checkout**; the "use your own worktree" note is advice, not enforced.

## Found 2026-10-14 afternoon (on the real launcher)
16. **Opening the chat on a thread by intent does nothing when the chat is already open**
    (`OmaChatEntry` + `dev.omalauncher.extra.THREAD` is delivered to the running activity; onNewIntent ignores it).
    Built 2026-10-14 evening, **not yet seen on a screen**: onNewIntent already set the asked-for tab (since 10-13), so
    the cause is not certain. Now it also calls setIntent, and both onCreate and onNewIntent read the tab through
    `askedTab`, which opens a ＋ chat's tab for the Mac thread it lives on (`mac:<id>` of such a thread was no tab, so
    it opened Oma). Unit tested (ChatTabsTest). Check with the chat open on another tab.
17. **The chat field shows no text to accessibility** (uiautomator: the field node's text is empty even with text in
    it). Likely the real cause of #3: `fill` sets text the field never reports back. Make the composer expose its
    value (semantics editableText) and update the tab's draft from the field. Built 2026-10-14 evening, **not yet
    seen on a screen**: the field states its words itself (`editableText`) and its own set-text action makes them the
    open tab's draft (`setDraft`; refused while dictating), so the next drawing shows them instead of the old draft.
    Why uiautomator showed nothing was not found from the code; check with `oma-phone fill` and a uiautomator dump.
18. **Phone control stays off after an update** ("switched off in Accessibility settings; left off") — see #5.
19. **`oma status` Mac threads go stale**: during the run above it still showed the previous request and "idle".
    Built 2026-10-14 evening, **not yet seen on a screen**: the status file was written only at a builder look. Now
    every change of what the phone knows of the Mac's threads (listing, a follower's look, a `status` event, reach)
    writes it again (`BuilderWatch.macChanged`, one look at a time), and each thread there carries `background`.

## Seen on the real launcher 2026-10-14 17:35 (after promoting e5f8d89 + 01eb8ed)
- Phone control came back after the update by itself (oma-install: BOUND, "reconnected by itself").
- #16 fixed: opening the chat by intent with `mac:t9f70c3fc` while the chat was open switched to that thread's tab.
- #3/#17 fixed: `oma-phone fill` on "to Background keep-alive check…" answered ok and the text was in the field.
- #9 fixed: a 60 s background job — "Still running on the Mac…" under the answer; at the end "Background command …
  completed (exit code 0)", Claude Code's report "The 60-second timer finished without errors and printed "done"."
  arrived in the tab, and the "Still running…" line was gone. His own words showed as his (no "Sent from elsewhere").
- #19 fixed: `oma status` showed the new request, `background: []` and idle right after.
- Not seen yet: the phone-control self-heal actually turning it back on (it never went off this time).

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
