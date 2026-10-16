# Open bugs and the new-phone plan (written 2026-10-14, 04:40 PDT, by the agent on the old Fold)

**Still misrouted (2026-10-15 19:51):** "maybe we could do the rotating ring … What do you think? … Which do you think's
better?" about the keyboard work was answered by the front agent (Haiku), with rule (6) already installed; Boss's
"Yeah, let's do it" then reached the builder without the front agent's answer it agreed to, so the ring was missed
until he said so. Two gaps: rule (6) doesn't catch "what do you think / which is better" reliably, and a hand-over
after a front-agent answer should carry that answer as context.
   **Fixed 2026-10-15 ~20:20, promoted:** forTheBuilder (FrontAgent.kt) sends a view/choice question past the front
   agent while a builder works or worked in the last 30 min, on the chat and the voice path; AgentTasks.direct puts the
   front agent's exchanges since that builder last heard from him before anything sent to it directly (pictures, a
   screenshot, those questions). FrontAgentTest pins both. Not yet seen on the phone.

**Whisper voice input (2026-10-14 ~23:40), built, not yet seen on the phone.** whisper.cpp 1.9.5 (CPU-only, vendored in
app/src/main/cpp/whisper.cpp) runs inside Oma: the fast base.en model reads the audio twice a second while Boss
speaks (words two readings agree on are settled), and small.en reads the whole once he stops. ui/Speech.kt uses it
whenever files/whisper has the models (pinned SHA-256; fetched on Wi-Fi on first use, copied in by hand on the Fold)
and falls back to Android's recognizer otherwise. The keyboard mic writes the words into the field as they come.
To check on the phone: words appear while speaking (hold-to-talk overlay and keyboard), the end text is better than
the live one, how long the end takes, and phone heat on a long dictation. Next: faded unsettled words in the overlay,
the Mac's bigger model for long dictation.

Boss moves to a new, higher-RAM phone tomorrow (2026-10-14/15). Everything below is on GitHub
(`phone/fold-development`); the newest state is in `docs/PHONE-HANDOFF.md` (checkpoint 2026-10-14) and the top of
`docs/PHONE-TEST-RESULTS.md`. Fix these on the new phone. Highest first.

## Banked plan (Boss, 2026-10-14 night: "bank your recommended steps")
From the review of everything built so far, in order:
1. Finish the simplification: launcher code work goes to the Mac `omalauncher` thread (tc57ef960); the phone builder
   only for phone-only work (oma-phone, install); then retire the second phone builder and its worktree machinery.
2. One real end-to-end pass on the new phone, by voice: hold-to-talk with tabs/Mac/＋, a long job on a locked phone,
   the Tailscale and phone-control self-heals, two jobs at once, the Claude→Codex→Cursor fallback.
3. Restore rehearsal on the new phone (app data: pages, notes, chats); decide where the signing key's copy lives.
4. Fold the five overlapping docs into two (a current-state handoff and this bug list).
5. Back to the launcher itself: the design prototypes and the right panel.
Also known: nothing stops two runs of the same session (seen twice tonight); routing log never reviewed.

## Bugs not fixed
1. ~~**Mac background jobs die when their turn ends.**~~ FIXED 2026-10-14 by the Mac session: a Claude thread keeps
   its process while background work runs and the next message goes into it ("As built: background work outlives
   the turn" in `docs/MAC-RUNNER-SPEC.md`). Installed on the Mac runner.
2. ~~**Codex in auto mode can read the runner key**~~ CLOSED 2026-10-14 by the Mac session (0db3c21): the Mac keeps
   only the key's SHA-256 (converted in place on the runner's first start; the phone's key is unchanged), so the file
   opens nothing. Keychain would not have helped: an item the runner's python3 may read is open to Codex's python3
   too. Left: replace the key (old copies may sit in Mac transcripts) with `oma-runner rekey`, which Boss runs or
   allows; then Codex threads can default to auto ("As built: the key" in `docs/MAC-RUNNER-SPEC.md`).
3. **`oma-phone fill` on the Oma chat field: cause unknown.** It said ok and the text never landed (a placeholder child
   inside the field matched the label; the field already resolved to its editable parent, so the likelier cause is
   the per-tab draft overwriting a text set from outside). Since 41eeb49 fill reads the field back and answers
   `ok: false`. Still to do: make text set by accessibility stick in the composer (update the tab's draft from the
   field's own value) and confirm on the phone. **Fix built 2026-10-14 evening, not yet seen on a screen:** see #17.
4. **`type`/`click` say "Screen changed" while the chat streams**: the target's bounds move between read and act.
   Needs a re-find of the same control by id+label at act time, without ever hitting a different control.
   Built 2026-10-14 evening, **not yet seen on a screen**: an act whose control no longer matches the read exactly
   looks at the screen again and finds the same control by its app, view id, kind and words (one that says nothing
   and has no view id only at the same place in the tree). Exactly one match is acted on, and the answer says
   `moved: true` with its new `id` (a fill reads its words back there); none, or two (a second `[ copy ]` came in),
   still answers "Screen changed". Bounds alone never pick between two. Unit tested (PhoneFindTest, `PhoneRefind`).
   Check by clicking `[ stop ]` or typing into the composer from a read taken while an answer streams.
5. **Phone control does not rebind by itself after most launcher updates**; `oma-install` heals it (BOUND / HEALED).
   Find why the accessibility service is not rebound on package replace.
6. **Builder health read "alive: false" for the running builder** on the first status after an update; right a
   minute later. A false "dead" must never invite a restart. Built 2026-10-14 evening, **not yet seen on a screen**:
   a task younger than 30 s with no builder process found yet, and any look that read none of the guest's processes,
   is now alive-unknown (`alive` left out of `oma status`, never Dead, so the tab menu offers no Restart and the
   front agent reads no "dead"); `BuilderFacts.alive`, `ALIVE_GRACE_MS`. Why the process was not found on that first
   look was not proven from the code. Unit tested (BuilderHealthTest).
7. **Front agent misroutes, rare:** a plain question handed to a builder (about 2 in 100); "thanks" while a builder
   works sometimes handed over. Harmless but slow.
   **The other way too (2026-10-14 ~21:40):** in the middle of the builder's keyboard work, Boss asked "how do we go
   from here? Do we need to tweak it more, more data, or build it so we review periodically…" and the front agent
   answered it itself, from its short context, instead of handing it to the builder whose work it was about. Boss:
   "that was supposed to go to the builder". A judgment question about the work a builder is doing belongs with
   that builder, even when it reads like a plain question.
   **Fixed 2026-10-14 ~22:00, built, not yet promoted:** rule (6) hands over "your view, a choice or what next about
   a builder's work", and the front agent's own questions are narrowed to how the work stands (is it done, how is it
   going). QuickLaneTest pins both; the prompt stays under its 2,500 limit (2,485).
   **Stop didn't stop (found the same night):** Stop called Process.destroy(), which only signals proot, and proot
   ignores SIGTERM; the agent inside ran on unseen (it kept building the keyboard after "let's run it" was stopped),
   and the next message started a second copy of the same conversation beside it, the two writing over each other's
   files. assist/RunEnd.kt now ends the run's whole tree (TERM, 3 s, KILL, proot last) for the builders, the front
   agent and chat titles, and a stopped builder reads as idle only once its process is gone. RunEndTest. Not yet
   seen on the phone: stop a builder mid-task and check `ps` shows no `claude -p --resume` left.
8. **The Oma prompt still names `oma-agents status`** (works as an alias; should say `oma status`). Built 2026-10-14
   evening, **not yet seen on a screen**: the prompt says `oma status`; both stay allowed (QuickLaneTest's wording pin
   changed with it).
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
10. **Mac work is not on the oma tile, ring or lock widget** (they read the phone builders only). Built 2026-10-14
    evening, **not yet seen on a screen**: with no phone builder at work the oma tile reads "Working on the Mac ·
    <thread>", "Running on the Mac · <thread>" (background work) or "Needs your OK · <thread>" (a Mac thread waiting
    for his OK outranks a builder at work); the Mac's approvals count in `approvals`, the ring and the Oma approvals
    tile; the ring spins for Mac work in hand but not for background work; the lock widget says "working on the
    Mac" / "running on the Mac" / "needs your OK" without the thread's name; the Agent activity tile follows the
    oma tile. A Mac out of reach shows nothing. `agentStatus` is unchanged and wrapped (`omaStatus`, `macGlance`).
    Unit tested (AgentStatusTest, OmaLockWidgetTest).
11. **A thread tab shows only what the phone sent**: "Earlier work on the Mac is not shown". Fetch the thread's events.
12. **The Claude→Codex→Cursor limit memory (15 min) is lost on a launcher restart.** Built 2026-10-14 evening, **not
    yet seen on a screen**: kept in the `mac-threads` prefs ("limits") with wall-clock ends, read back on restore;
    what is over is dropped, and a clock set back never holds an agent off longer than 15 min. Unit tested (MacKindsTest).
    Built 2026-10-14 evening, **not yet seen on a screen**: each time a Mac thread's tab opens it reads the thread's
    newest 200 events (`/threads/ID`, then `events?after=N` a page at a time; `MacThreads.earlier`, MacEarlier.kt),
    cuts them into turns and shows those the phone has no entry for above its own entries, read only: "on the Mac ·
    time", his words (without Oma's context), the steps in one line ("12 steps, the last: …"), the answer; captioned
    "Earlier on the Mac (its last 200 events), only shown here", or "Reading…" / "could not be read now". Nothing of
    it is saved, sent, followed or answered, and the place the follower reads from is not moved. Dedupe: only turns
    begun before the phone's first entry in that thread (later ones reached it by following), and none whose words
    are one of its entries'. A ＋ chat's thread began on the phone and reads nothing. Unit tested (MacEarlierTest).
    To check: open a thread begun at the Mac; open one the phone has sent to and see no turn twice.
12. **The Claude→Codex→Cursor limit memory (15 min) is lost on a launcher restart.**
13. **A ＋ chat needs the phone's Linux to send** (the front agent runs there) although its work is the Mac's.
    Built 2026-10-14 evening, **not yet seen on a screen**: a ＋ chat with no thread yet still goes to the front agent
    when it can be asked; with no Linux, no Claude Code as the front agent, words longer than it takes, or a front agent
    that fails to start, its words go straight to a new thread in `~/Projects` as they are (`Outgoing.ChatMac`,
    `MacThreads.startChatDirect`; the entry is `direct`, so nothing is put ahead of them). The thread is named by the
    tab's name or his first four words; the tab is named as before (ChatTitles falls back to the first words when
    no model can be asked). No Mac set up: says so; Mac out of reach: his words stay with `[ try again ]`, which
    makes the thread again. Images are still refused. Unit tested (ChatComposerTest, MacProjectsTest). To check:
    with Linux stopped, ＋, type, send.
14. ~~**Design question for Boss:** a ＋ chat he named but never wrote in blocks ＋ (＋ reopens it instead of a new
    one).~~ Decided for Boss (he asked for something sensible), built 2026-10-14 evening, **not yet seen on a
    screen**: a ＋ chat he renamed counts as his, as one he wrote in does, so ＋ makes a fresh chat beside it; an
    untouched "New chat" (or one whose name he cleared) is still reused (`emptyChat`; TabBookTest).
15. **Two phone builders share one checkout**; the "use your own worktree" note is advice, not enforced.
    Built 2026-10-14 evening, **not yet seen on a screen**: a Claude builder that starts while the other builder works
    first runs a shell step that makes or finds its own worktree of `/root/Projects/omalauncher` (`/root/wt/builder-a`
    or `-b`, branch `builder-a`/`-b`, from the shared checkout's current branch) and brings it up to that branch by
    fast-forward only when it is clean and holds nothing unmerged; a folder there that is not its worktree is never
    touched. The note it is sent then names that worktree and how to merge back (`git -C <main> merge --ff-only
    builder-x`, or a normal merge; stop and tell Boss on a refused merge or conflict) in place of the advice. No git
    checkout, a detached HEAD, or a failed step: the request goes as before, with whatever note it had. Still not
    enforced: the builder starts in /root, not in the worktree, since Claude Code finds the conversation it resumes
    by its starting folder (moving it would lose the builder's thread), so it is told to cd there; Codex builders
    keep the advice; work in other repositories still gets advice only. Unit tested (BuildersTest, against real git).

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

## Seen on the real launcher 2026-10-14 18:40 (after promoting 2f65ba5 / 7a49226)
- Phone control came back after the update by itself; the Mac accepts the phone's key after its key change (#2).
- #11 PASS: the "Oma runner upkeep" tab showed the thread's whole earlier Mac work (its steps and answers), though
  nothing in it was sent from the chat.
- #14 PASS: ＋ → long-press → Rename "Rename test" → ＋ again opened a fresh "New chat" beside it. (Both test chats
  archived afterwards.) `oma-phone fill` worked on the rename field too.
- #10 not seen: the oma tile said "using Bash…" because the phone builder (this agent) was working, which by design
  wins over Mac work; check it when no phone builder works. The lock widget was not looked at.
- `oma drop send` delivered a file to the Mac Pro's Downloads/From Fold; `oma threads new` made the "omalauncher" thread
  (tc57ef960) in ~/Projects/omalauncher-fold that the Mac session asked for.
- Still open: #7 (rare misroutes), #13 not exercised (needs Linux to be off), #4/#15 not exercised on screen,
  the phone-control self-heal not yet seen acting.

## Built but never seen on a screen (check on the new phone)
＋ starting in `~/Projects` without a question; the Tailscale self-heal (after a reboot) and its respect for a
manual off; "The Mac ended this turn without an answer." + try again; Restart / Clean up rows in the tab menu; tile
captions "MAC · NOT STARTED" / "OUT OF REACH"; the 12-hour archive of idle chats; Mac threads in auto mode (needs
`oma-runner install` on the Mac first); two phone builders working at once, the second given its own worktree (`/root/wt/builder-b`).

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
