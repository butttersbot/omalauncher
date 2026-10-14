# Phone test results

## Latest: simplification pass + Critic fixes (2026-10-14, about 03:10 PDT, commit 02f3280) — NOT promoted

- Front agent has two jobs (answer, or which project): marks only `<<build>>`, `<<mac ID>>`, `<<mac-new ID>>`;
  stop / restart / clean are tab-menu rows (Restart only when stalled or dead). Haiku trial: 36/36 (old prompt 31/36).
- One `oma` guest command: `oma threads [new … --kind]`, `oma keep …`, `oma status`, `oma help`; old names alias it.
- Critic on the real launcher (e2657e5): UI items passed; FAILED "＋ always the Mac" (a ＋ chat was answered on the
  phone, with Bash) and Back in the tab menu closed the chat. Fixed (9aa78da): ＋ chats get web tools only, work is
  held for the Mac (project question, or "Mac unreachable — this chat is for the Mac." + try again), never THIS PHONE;
  Back steps through the menu; the strip scrolls to the open tab; dots and tiles are one set.
- Critic in Oma Dev on 9aa78da found a regression (renamed empty ＋ chats vanished); fixed in 02f3280 with one draft
  per tab and no keyboard pop on tab taps. Critic in Oma Dev on 02f3280: PASS.
- Not seen yet: any of it on the real launcher with the Mac reachable; the 12 h archive path; "MAC · WAITING FOR A
  PROJECT" caption.

## Earlier: the front agent has two jobs; stop, restart and clean are on the tab (2026-10-14) — NOT promoted, NOT seen on a screen

Boss: "Shrink the front agent to two jobs: answer, or say which project. Stop, restart and clean move to buttons on the
tab's long-press menu. The tabs and ＋ are explicit routing; the model should only guess when you don't choose."
Compile-checked and unit tested only (1096 tests green); no gradle build run.
- Marks: only `<<build>>`, `<<mac ID>>`, `<<mac-new ID>>`. `<<build A|B>>`, every `<<stop…>>`, `<<restart…>>` and
  `<<clean>>` are gone from the prompt and the reader; one that still arrives is a plain `<<build>>`, never carried out.
  `borneOut`, `wordsOfStopping`, `wordsOfMending`, `halt`, `mend`, `restartPick`, `bareStop` are removed.
- `<<build>>` goes to the one phone builder that finished by asking him a question (`askingBuilder`), else to whichever is
  free (`pickBuilder`); with both asking, the free rule. Hand-over context is unchanged.
- Asked to stop, restart or clean up, it answers: long-press its tab and choose Stop or Restart.
- Tab menu: Restart (red, after Stop) on a phone builder's tab while that builder is stalled or dead and waits for no
  approval (`tabMend`; `BuilderWatch.restart` checks again); "Clean up leftovers" on Phone tabs while leftover builder
  processes run (`BuilderWatch.clean`). Opening a phone tab's menu takes a fresh look. What was done shows as the notice line.
- Prompt 5,328 → 4,169 characters with a Mac. Real Haiku, 12 requests × 3: new prompt 36/36 as intended; old prompt
  31/36 by its own marks (sent "thanks" to builder A twice, FF4 work to builder A once, a restart for "is it frozen?").
- To see: the Restart and Clean rows appearing only when they should, a restart from the menu, "yes" after a builder's question.

## Latest: promoting the night's batch, then simplifying (2026-10-14, Boss: "Yeah, let's do it")

Promoted at Boss's word: chat dots at the right, ＋ new chat (always a Mac session), auto-named tabs, tab long-press
menu, italic placeholder, Builder B's tile timers and editable voice words, Mac reply names, Claude→Codex→Cursor
fallback. Unit tests green (1106); NOT seen on a screen before the update.
Next, in order (agreed with Boss after the herdr discussion: no herdr; simplify instead):
1. After the update: the Critic (Fable, foreground, `subagent_type: critic`) actuates the batch on the real launcher.
2. Mac builds Oma: request written at the end of MAC-RUNNER-SPEC.md (toolchain, an `omalauncher` thread; the
   signing key stays on the phone, so install stays a phone step).
3. Shrink the front agent to two jobs (answer, or which project); stop/restart/clean become tab-menu buttons; drop
   the builder A/B, restart and clean marks.
4. One `oma` command for the small guest tools (threads, keep, status).

## Earlier: Codex and Cursor when Claude is at its limit; ＋ is always the Mac (2026-10-14) — NOT promoted, NOT seen on a screen

Boss: "if we hit our limit on claude code … we should have codex available … or cursor", then "The plus button should
always open up a Mac session. If I need to do anything on the phone, I'm gonna be using the hold to talk." Compile-checked
and unit tested only (1106 tests green); no gradle build run, nothing sent to the Mac.
- Fallback (assist/MacKinds.kt): a Mac turn that ends with `error {code:"limit"}` is sent again, the same words with the
  same context block and worktree note, to a new thread on the same project of the next agent in claude → codex → cursor
  that /health `kinds` lists ("FF4 better menu mod · Codex"). The entry gets the step "Claude hit its limit · sent to
  Codex" and the receipt "→ FF4 better menu mod · Codex"; with none left it reads "Every agent on the Mac is at its limit
  (Claude, Codex, Cursor)." with `[ try again ]`. Only a `limit` error does this. A limit is remembered 15 minutes
  (in memory): meanwhile a request for a thread of that agent goes to a free thread of the next agent on the project,
  or a new one, saying "Claude is at its limit · using Codex"; new threads start on the first agent not at a limit.
- Reply names come from the event's `kind`/`model`: Codex and Cursor by name, a Claude thread by its model ("Opus").
- ＋: a new chat never reaches a phone builder. Its first words go to Oma (told one extra line: "This is a new chat, for
  work on the Mac: phone work is not taken here."; real Haiku, 13 tries, 12 as intended). A thread's mark makes a new
  thread on that project. `<<build>>`, a builder's mark, a mark for no thread, a mending mark or Oma failing all make the
  launcher ask in the tab "Which project on the Mac? ffiv-ds-better-menu-mod, pond, …" (folders of the threads the phone
  knows); his next words there are matched to a folder or its threads' names, case-insensitive, else asked again.
  Images, or no Oma to read it, are refused in a new chat. `[ send to build ]` is not offered under a new chat's answers.
- Removed: `AgentTasks.startChat`, the wait for a free builder (`takeFresh`, `cancelFresh`, `dropFresh`, `FreshWait`),
  `Builder.startFresh` and `owner`, `AgentTasks.owners`, the "ended" tab (`SendTo.Ended`, `ENDED`, `ENDED_HINT`),
  `SendTo.ChatOn`, `FRESH_WAITING`, `NewChat.builder` and `chatHandOver`. A chat saved with a builder loads unbound; a
  builder's saved "chat" pref is no longer read. Voice (hold to talk) is unchanged.
- To see: a real limit falling over to Codex, the question and an answer to it in a new chat, the reply names.

## Latest: chat header dots, ＋ new chat, tab menu (2026-10-13, late) — NOT promoted, NOT seen on a screen

From Boss's look at the real launcher. Compile-checked and unit tested only (1091 tests green); no gradle build run.
- Header: the dots sit together at the right, just left of "▾", 16 dp centre to centre (7 dp between the 9 dp dots);
  shape, colours and pulse unchanged. "[ new thread ]" and the hint line under the log are gone; the field's
  placeholder is italic (the pack's body face, `FontStyle.Italic`).
- ＋ after the last tab (strip open with ▾): a new chat, "New chat" until named, field focused; an empty one is
  reopened rather than a second made. Its first message: Oma (in a front conversation of the chat's own) answers,
  or hands it to a FRESH session — a free builder started without `--resume` (both busy: waits for the first free
  one, the entry says so), or a NEW Mac thread on the project named (`POST /threads`, always, never the busy
  thread's queue). The tab is then bound: later words go straight there. A builder that takes a new chat serves
  it from then on; its earlier tab (or older chat) stays readable and reads "ended", field read-only. Entries
  carry `chat` (key written only when set). Hand-over context comes only from the same chat (or the Oma stream).
- Auto-name: after the first answer (or 20 s into a long first task) `claude -p --model haiku --tools ''` from
  /tmp/oma-title, once; fallback the first 4 words. Trials: "Dock icon size and spacing", "Seattle Tomorrow
  Weather Forecast", "FF4 magic menu crash fix", "Calendar widget with countdown", "Git worktree commands".
- Long press on a tab (haptic tick): Rename, Pin/Unpin, Stop (while it works), Copy last answer, Archive, and
  "Archived (N)" as the last row of every menu, where an archived tab is restored. Oma's tab: Copy and Archived
  only. Pinned tabs come first after Oma and their tiles never leave by themselves; archived ones leave the strip,
  the dots and the Agents page (a tile he carried elsewhere stays), and come back when something is sent to them
  or they need his OK. Kept in prefs "oma-tabs" (TabBook.kt).
- To see on the phone: the dots' spacing, the ＋ square's size beside the tabs, the menu, a new chat's first
  message on each route, the title appearing.

## Latest: the big bundle on the real launcher (2026-10-13, about 22:35 PDT, commit f132cbe)

- `oma-install --status`: verified; phone control had to be healed by the installer (PHONE_CONTROL_HEALED), then connected.
- Seen: the page "Agents" was made, with tiles `thread:phone`, `thread:mac:t7d995963`, `thread:mac:t4f497a23`
  (`oma-phone board Agents`); the chat header shows a dot per thread ("Phone: working", "Scratch: done",
  "Phone proof: done", "FF4 better menu mod: idle") and "▾" (read from the screen, not looked at as a picture);
  `oma-agents status` answers with the builder's health and the Mac threads; `oma-keep run probe` started a process.
- The first status file said `"alive": false` for the running builder (verdict still "working"); a minute later
  `true`. Watch for a false "dead".
- Still to see: the tiles drawn, a tile opening its tab, two builders at once, `probe` still kept in a later turn,
  a fresh Mac thread, the AI names on replies.

## Earlier: fresh Mac threads and the worktree note; promoted (2026-10-13, about 23:45 PDT, commit 9b7db8a)

- `<<mac-new ID>>`: separate or urgent work on a project whose thread is busy makes "NAME 2" on the same folder
  (`POST /threads`) and hands his words to it; idle thread -> as `<<mac ID>>`; at most 3 working per folder, then it
  queues. Real Haiku: 47 of 48 as intended (one "separately…" queued on the busy thread instead).
- When another phone builder, or another thread on the same folder, is working, the receiver gets a hidden note to
  work on its own branch in a git worktree and stop on a merge conflict. Not shown to Boss.
- `quickMacRules` length pin raised 1,400 -> 2,000 (the new rule is about 600 characters).
- `oma-build` green; promoted at Boss's word with everything since versionCode 50: Agents page and tabs, names,
  oma-keep, two builders, builder health, fresh threads. Nothing of it seen on a screen before the update.

## Earlier: four pieces in parallel, merged (2026-10-13, about 22:30 PDT, commit 69f3470 and after) — NOT promoted, NOT seen on a screen

At Boss's word ("fan out a bunch of sub agents"), four agents in separate worktrees (/root/wt/*), merged by a fifth;
`oma-build` green on the merge.
- Ended at his word: the previous builder run (pid 29095, 48 min old, still alive after its turn) and its child.
- Names on replies: `ChatTask.answeredBy` — "Haiku" for the front agent, the model Claude Code reports for the
  builder ("Opus"), "Claude" for Mac threads until the runner sends the model (asked in MAC-RUNNER-SPEC.md).
- `oma-keep run NAME -- CMD`, `list`, `log`, `stop`: a keeper daemon started with Linux (like oma-door) keeps
  processes past an agent's turn. Unix socket only. Its line is in the guest CLAUDE.md template, which this phone's
  edited /root/CLAUDE.md will not pick up by itself.
- Two phone builders (A, B): new work goes to a free one; follow-ups to their own (`<<build A|B>>`, `<<stop A|B>>`).
  Approval titles now name the builder ("Allow X for Builder A?"). Both share one home and repo.
- Builder health: board line and `oma-agents status` for the front agent; `<<restart>>` (stalled or dead only,
  never with an approval waiting) and `<<clean>>` (leftover builder processes), each only when his words ask.
- Mac side of "never bottlenecked" (fresh threads, Codex/Cursor on limits, model on events): written for the Mac
  session at the end of MAC-RUNNER-SPEC.md. Not built.

## Earlier: Agents page and chat tabs (2026-10-13, about 21:00 PDT, commit b450d76) — NOT promoted, NOT seen on a screen

Built from `design/oma-threads-mockup.html`, which Boss approved ("it looks good"; tabs folded behind "▾", state
dots in the header). `oma-build` green.
- Chat: header = name + one dot per thread + "▾"; tabs Oma, Phone, then Mac threads; a thread's tab shows its
  entries only and its field goes straight to it (front agent skipped, no context block); "→ NAME" opens the tab;
  `OmaChatActivity.EXTRA_THREAD` ("phone" / "mac:<id>"). Mac work started at the Mac is not fetched yet.
- Home: a page "Agents" is made the first time a tile is needed; native tiles of kind "thread" (`ui/AgentTile.kt`,
  `widgets/AgentTiles.kt`, `widgets/AgentTileKeeper.kt`) only on that page, 3x3, keyed "thread:phone" /
  "thread:mac:<id>". Never leaves while working, queued or waiting for his OK; idle or failed, fades in the 12th hour
  and goes. A tile he drags elsewhere is his. Pinned: only the Agents page is ever written (AgentTilesTest).
- No tape or alt fill on the tiles: ScrapFrameTest pins that only widget tiles are dressed.
- The failed-thread rule above was changed by me after the subagent (it kept red tiles forever).

## Earlier: second routing bundle on the real launcher (2026-10-13, about 19:40 PDT, versionCode 50, commit 812a96c)

- Promoted at Boss's word; `oma-install --status` verified, phone control bound by itself.
- Seen: `oma-thread list` against the real runner; `oma-thread new` made "FF4 better menu mod" (t5e121ba2) on
  `~/ClaudeSync/projects/ffiv-ds-better-menu-mod`. Two messages sent one after the other in the chat: the second
  read "Oma will read this next" with `[ cancel ]`, then was answered ("15% of 240 is 36.", with `[ send to build ]`).
  The first was handed to the Mac: the entry read "→ Scratch".
- The Mac answered that request "Failed to authenticate: OAuth session expired and could not be refreshed", shown
  as "needs attention". So the hand-over, the stream and the error path work from the chat; a real answer and an
  approval card from the Mac are still NOT seen. The Mac's Claude Code login has to be renewed there (second time
  today: worth asking the Mac session whether the runner under launchd can reach the refreshed login).
- Not tried: "stop" (the builder was this run), a spoken request while the builder works, `[ cancel ]`, a locked-phone run.
- Next, Boss's request: a thread view in the Oma chat (see every thread, open one, prompt it directly).

## Earlier: context on hand-over, never-blocked input, stop marks (2026-10-13, about 19:45 PDT, commit b6a3ff4) — NOT promoted, NOT seen on a screen

Two batches by subagents, each built green by `oma-build` afterwards. The real launcher is still versionCode 49 (75f0cf8).
- A hand-over now carries the last front exchanges ahead of his words, marked "[Earlier, between Boss and Oma's
  quick answers; context only, not a request] ... [Boss now says]". Entries, notifications and the log keep his words
  only. `AgentTasks.followUp` gained `earlier` and `screen` (defaults keep old callers as they were) and
  `cancelFollowUp`.
- The board gained a "Last to speak" line when a Mac is set up; a "yes" after two questions went to the right thread
  6 of 6 (was 3 of 9) in the subagent's trial.
- `MacThreads.open` is called from `Runtime.start()`; `oma-thread list | new NAME PATH` is installed in the guest
  from `assets/oma-thread.sh` (key only on curl's stdin). Not run against the real runner.
- Input is never blocked: the front agent has a queue of its own (8), waiting entries say where they wait and carry
  `[ cancel ]` (not for the Mac's queue), the listening screen no longer has a Busy phase (pinned tests for it were
  changed on purpose; the approval phase is as it was).
- `<<stop>>` and `<<stop mac ID>>`: carried out only when his own words hold a stopping word, because real Haiku
  wrote `<<stop>>` for "thanks" with the builder working. Known weak case: a bare "stop" with the builder and a
  thread both working stopped the builder 1 time in 3 instead of asking which.
- Hello Phone (`/root/Projects/hello-phone`, `dev.boss.hello`): written, built in 36 s, installed over adb, its
  text read off its screen, then removed at Boss's word. Building other apps here works.
- Found: a background subagent dies whenever a new message is handed to this builder (the process restarts). Run
  them in the foreground.
- Not built: Mac work on the tiles, ring and lock widget.

## Earlier: the routing bundle is on the real launcher (2026-10-13, about 18:30 PDT, versionCode 49, commit 75f0cf8)

- Promoted at Boss's word: quick lane, automatic routing, Mac threads, keep-awake. `oma-install --status`: verified,
  phone control bound by itself.
- Seen on the real launcher: the front agent answering ("can we build Android apps here", "why is wireless
  debugging off") and handing over to the phone builder three times; its board listing the Mac's two threads as
  reachable; `PARTIAL_WAKE_LOCK 'omalauncher:agent-working'` held in `dumpsys power` while the builder worked.
- NOT seen yet: a hand-over to a Mac thread from the chat, a Mac approval card, `[ send to build ]`, a task
  finishing with the phone locked. The launcher is not exempt from battery limits (`deviceidle whitelist` has no
  line for it): Boss sets Unrestricted himself.
- Found: the builder is handed his words only, not what he and the front agent said just before, so "put it on our
  list" and "look into it" arrived with nothing to refer to. Read from the front agent's transcript by hand this
  time. To fix: hand over the last few front exchanges with the request, marked as context.
- Wrong report of mine: "wireless debugging is off". It was on (`adb_wifi_enabled` 1); its port had changed
  (34043 to 36141) and the saved one was stale. `oma-dev --status` refreshes it.

### The list, after the tries (Boss, 2026-10-13)
1. Builder gets the front conversation as context on a hand-over (above).
2. Building other Android apps on the phone and installing them, all locally. The SDK is in /opt/android-sdk and
   Oma and Oma Dev are already built and installed from here, so it is the same route: not tried for a new app.
3. Making a Mac thread from the phone (the FF4 mod thread); Mac threads picked up at launcher start; Mac work on
   the tiles, ring and lock widget.
4. The misroute seen in trials: a "yes" meant for a Mac thread going to the phone builder.

## Earlier: Mac threads wired into Oma (2026-10-13, about 17:45 PDT, commit 2390694 and the one after) — NOT promoted, NOT seen on a screen

- New `assist/MacRunner.kt`, `MacRunnerEvents.kt` (the client) and `MacThreads.kt` (the phone's view of the Mac:
  thread list, cursors, followers, approvals). With no `~/.config/omalauncher/mac-runner.json` nothing changes
  (pinned). With one, the front agent's board lists the Mac's threads and it may reply `<<mac ID>>`; his words go to
  that thread unchanged, the entry shows "→ NAME" and fills from the stream. `AgentTasks.kt` untouched.
- Approvals from the Mac: a list of their own beside the phone builder's, the same card in the chat, a notification
  with its own receiver (`MacApprovalReceiver`, not exported) and ticket scheme; the nonce stays in the process.
  Only his tap answers. While one waits, `oma-phone` actions are held as they are for the phone builder's
  (`phone/PhoneService.kt`, one expression). No existing assertion was changed.
- `oma-build` green: 921 tests. One new test had a mistake of its own (an all-digit id "uppercased") and was fixed.
- The key is on the phone (Boss, through the Mac session); `/health` and `/threads` answer from the guest in 0.04 s.
  The Kotlin client has NOT spoken to the real runner: that needs the launcher itself, so a promotion.
- Routing with three Mac threads on the board, real Haiku, 12 requests x 3: 33 of 36 as intended. Wrong: "yes do
  the magic menu too" went to the phone builder once (the FF4 thread had asked); "write a script that renames my
  photos" went to the phone builder twice instead of asking which project.
- Not built: making a thread from the phone; picking Mac threads up at launcher start (after a restart nothing
  follows the Mac until the chat or the listening screen opens); Mac work in the tiles, ring and lock widget.

## Earlier: the front agent timed and its routing counted (2026-10-13, about 17:10 PDT) — no code changed

Run in the guest with the real instructions from `quickSystemPrompt()`, a board, and the real flags (scripts in /tmp, not kept).
- Model is `claude-haiku-5-5`. Time to a decision, fresh or resumed: 1.4 to 2.6 s, of which 0.8 to 1.4 s is Claude
  Code starting. The 5 to 7 s reported earlier was measured while the subagent itself was working on the phone.
  So keeping the process alive would save about a second; not built.
- Reasoning: none on a plain question; a short thinking block before a tool call. `--effort low`,
  `MAX_THINKING_TOKENS=0`, `alwaysThinkingEnabled:false` and `CLAUDE_CODE_DISABLE_THINKING=1` did not remove it.
- Routing, 12 requests x 2 boards x 3 runs, three times over (216 runs): 5 wrong, every one a plain question
  ("capital of France", "thanks in Korean") handed to build; no build request was ever answered by the front agent.
  One added sentence to the instructions did not lower it, so the instructions are as they were.
- Still not seen on a screen, still not promoted.

## Earlier: one Oma, routed automatically (2026-10-13, about 16:20 PDT, commit f95ba46) — NOT promoted, NOT seen on a screen

Boss's word: he will not pick a lane; he talks to one agent and it must route itself.
- Every typed request, and a spoken one with no screenshot, goes first to the front agent (`claude -p --model haiku`,
  looks only, as the quick lane was). It answers, or replies `<<build>>` and the launcher hands Boss's own words to
  `AgentTasks` unchanged. The `[ quick ] [ build ]` row is gone. `assist/FrontAgent.kt` is new; `AgentTasks.kt` untouched.
- A front answer has a small `[ send to build ]`; using it sends the words to build and adds a line to
  `files/oma-routing.jsonl`. Images, a busy or failed front agent, and requests over 4000 characters go straight to build.
- `oma-build` green (unit tests and APK). Oma Dev installed with it (versionCode 29). The phone was locked, so
  nothing was looked at: not the chat without the lane row, not a hand-over, not `[ send to build ]`, not voice.
- Routing tried for real by the subagent with the real command and a stub `oma-phone`: 10 of 10 as intended after two
  rewordings of the prompt (it first handed over "what are you working on"). 5 to 7 s to a decision, about 3.5 s of
  it Claude Code starting.
- The pin in `QuickLaneTest` on "can look, not act" was reworded on purpose (it named the lanes); the pins on
  permission-prompt-tool, Edit, Write and "dangerously" are as they were.
- Open, for Boss: a held-button request over another app carries a screenshot and so always goes to build; the
  listening screen still says busy while build works; a hand-over to a working builder is queued.
- Also new: `docs/MAC-RUNNER-SPEC.md` (550e66d), for the Mac session. Nothing in it is built.
- Next: see it in the real launcher when Boss says promote; then project threads (two builders on the phone).

## Earlier: a quick lane beside the build lane, built, not promoted (2026-10-13, about 15:30 PDT)
Boss wants quick questions answered without waiting for the heavy model or for a running build; agreed plan: a fast front lane first (fast Claude, his pick over Grok), per-project threads next, automatic routing last.
- New assist/QuickLane.kt and a lane choice in the Oma chat (oma/ChatComposer.kt, ChatHistory.kt, OmaChatActivity.kt): `[ quick ] [ build ]` above the field, kept in prefs. Build is unchanged. Quick runs `claude -p --model haiku` in its own process and session beside AgentTasks, streams its answer into its own entry (caption "quick · time", own stop), text only.
- It can look and not act: tools are Read, WebSearch, WebFetch and Bash limited to `oma-phone status|screen*|read|pages|board*|looks|stickers` and `date*`; `--permission-prompts none`, so anything else is refused without asking and it can never raise or answer an approval. No notification, tile or lock widget follows it. Tests pin that its command has no write tool and no permission prompt.
- `oma-build`: PASS, 831 tests, 0 failures. Commit 02492a4.
- Checked on the phone: `claude -p --model haiku` answers here (claude-haiku-5-5), about 6 s for a one-line answer, mostly start-up. The agent that wrote it ran the real command: `date` allowed, `touch` refused, `--resume` continued.
- SEEN in Oma Dev, inner screen: the chat opens, the lane row shows, picking quick turns it accent with "answers now, looks only" and the hint "ask something quick…".
- NOT seen: a quick question answered in the chat (Oma Dev has no Linux), one asked while a build runs, a web search, an `oma-phone` look from it. First real test is after promotion.
- Not in this step: voice still goes to build; no automatic routing; one quick question at a time; it runs in /root and can Read any file in the guest.

## Latest: promoting the overview backdrop, tap-to-close and the note instruction (2026-10-13, about 15:05 PDT)
- Boss saw the overview in Oma Dev, asked for less blur (now 12dp under a 40% wash, commit 12ecc62), said it looks good and to promote. Running `oma-try` last; the result is NOT known from this run.
- Goes with it: 08b5600 (no sticky note unless asked). To check after: `oma-install --status`, the overview on the real launcher in city pop on both screens, a tap on empty space there.

## Latest: the overview closes on a tap of nothing, and stands on the look's wallpaper (2026-10-13, about 15:00 PDT)
Asked by Boss from the phone, looking at "all pages" in city pop on the inner screen.
- Change (commit e23e3d5, ui/Overview.kt): a tap that no page and no word takes leaves the overview as Back does; in a look with its own page the backdrop is `PagePaper` (the look's paper or wallpaper) blurred 22dp under a 55% wash of the paper's color, in place of the flat paper. The prototype has plain paper; this is Boss's word. Console is as it was.
- `oma-build`: PASS. Not promoted (bundled with 08b5600, the note instruction).
- SEEN in Oma Dev, inner screen, city pop: the overview shows the pool wallpaper out of focus under blue, the pages and their names read clearly; a tap on empty space returned to home; a tap on a page still opens that page.
- NOT seen: the cover screen, a paper look (riso, bujo: a blurred pattern under a wash, likely near plain), a phone below Android 12 (no blur there, only the wash).
- Oma Dev is left in city pop.

## Latest: no sticky note after every task (2026-10-13, about 14:30 PDT)
- Boss: the agent need not leave a note after everything it does. The instruction came from the launcher (phone/PhoneBridge.kt INSTRUCTIONS, and oma-phone's help), not from the agent.
- Changed (commit 08b5600): the sentence now reads "Only when the user asks for a sticky note ... Do not leave one to say what you did."; the help text likewise; PhoneNotesTest's pinned sentence changed on purpose, at his word. `oma-build`: PASS.
- Not promoted (bundled): the installed launcher still sends the old sentence with each message; the agent on the phone ignores it from now on.

## Latest: swipe gestures in place of the three buttons, at Boss's word (2026-10-13, about 14:20 PDT)
- Boss wanted swipe-to-go-back in apps as on an iPhone. Cause: the phone was on button navigation (`settings get secure navigation_mode` 0), which has no back swipe. He said to switch it.
- Changed in Settings > Display > Navigation bar by `oma-phone press "Swipe gestures"`; read back `navigation_mode` 2, and the screenshot shows it selected with a handle at the bottom. (The phone locked itself once mid-way; he unlocked it.) To undo: the same screen, "Buttons".
- Checked through Oma on the cover screen, all PASS: swipe up from the bottom edge in Settings goes home to Oma; a swipe in from the left edge closes the app list; from the right edge closes Oma's settings; a swipe across the middle still turns pages both ways; a swipe up from mid-screen still opens the app list; in Android Settings a swipe from either edge goes back a level and then out to home; `oma-phone back`, `home` and `status` still work.
- Seen: home is taller now (no button bar); tiles and the dock moved to fit, nothing covered. Closing Oma's settings by an edge swipe went straight to home, the app list under it closed too.
- NOT checked: the X app itself, the recents gesture, the inner screen, a page swipe that starts at the very edge (the system would take it as back).
- "Oma watchlist" lock widget: Boss placed it himself after the promotion of d3d5d16; I have not seen it.

## Latest: a watchlist widget for the lock screen, built, not promoted (2026-10-13, about 13:25 PDT)
Asked by Boss from the phone: the watchlist on the lock screen, small, the top three gainers, in the active look.
- New "Oma watchlist" app widget (assist/OmaWatchWidget.kt, res/xml/oma_watch_widget.xml, a manifest receiver), drawn by theme/LookWidgets.kt like "Oma status": a scrap of the look with "top gainers" in small capitals and three lines, ticker in the display face, change at the right in the look's green or red. No prices. LookWidgets' scrap frame is now one helper shared with the status widget.
- Data: it reads the home widget's kept quotes (files/widget-state/watchlist.owl.json). That widget only fetches while home shows, so when the quotes are over 2 minutes old the lock widget asks TradingView's scanner itself for the same symbols (on a tick or when the screen comes on) and keeps its own copy. Past 15 minutes the caption says the age.
- `oma-build`: PASS, 815 tests (7 new in OmaWatchWidgetTest), 0 failures. Commit 7cdd03a.
- Checked on the phone: the real launcher's state file is there under that name with 13 symbols; by the widget's rule it would show NEET +6.5%, BOT +3.3%, ENA +2.6% now. Look in use: city-pop.
- NOT seen: the widget itself. It cannot be placed until promoted, and placing is in LockStar's App Widgets editor. Its own fetch has never run.

## Latest: checkpoint, and bundle 2 is being promoted (2026-10-12, about 18:00 PDT)
- Boss has his laptop and will continue from there. The state of everything is in docs/PHONE-HANDOFF.md ("Where things stand" and "Next"), rewritten now.
- Boss said "promote it". Running `oma-try` as the last step of this run; the result is NOT known from this run. Bundle 2: Markdown in the chat, picture stickers, the chat scrolling to a sent message. Last green build: 760 tests.
- First thing for whoever is next: `oma-install --status` and `oma-phone status` (see "Next" item 2).

## Latest: Cove's three sticker packs are on the phone too (2026-10-12, 17:52–17:56 PDT)
- Boss remembered a Pepe sticker and others. They are not in the art bank but in ClaudeSync projects/pond/public/stickers (packs.json: rabbit-shark, pepe-blanket, donut-frog; sheets in studio/sticker-sheets).
- All 67 PNGs were read with `oma-context` and saved on the phone under ~/.config/omalauncher/stickers/: pepe-blanket (20), donut-frog (26), rabbit-shark (21); 8.0 MB. With the 12 in cove/ the library holds 79 pictures, 18.9 MB, none over 5 MB. The packs' manifest.json files were not copied.
- SEEN in Oma Dev on the cover screen, with the library copied in while it ran: the picker scrolls through cove, donut-frog and pepe-blanket under their captions, five across; all twenty Pepe stickers drew with clean edges. rabbit-shark was not scrolled to. None of these was placed on a page this time.
- The real launcher shows none of them until the bundle with picture stickers is promoted.

## Latest: Cove's stickers are on the phone (2026-10-12, 17:47–17:50 PDT)
- The Mac was never down: Tailscale was not running on the phone after its restart for the security update (no tun0, no process). Boss said to start it; opening the Tailscale app brought it up connected, with no switch to press.
- Cove's art bank is ClaudeSync projects/pond/data/art-bank/catalog.json (29 assets: 12 stickers, 8 papers, 5 images, 4 backgrounds), bytes in projects/pond/data/assets/<sha256>.<ext>.
- The 12 stickers (sky-journal and Blush Journal sets) were read with `oma-context`, each checked against its sha256, and saved as ~/.config/omalauncher/stickers/cove/<name>.png on the phone. 10.9 MB in all, the largest 3.8 MB. Papers, images and backgrounds were not copied.
- SEEN in Oma Dev on the cover screen, with the same twelve copied in while it ran: all twelve show under "cove" in the picker with their transparency; the butterfly landed on Page 1 in its own shape, selected. The 3.8 MB stamp decoded without trouble.
- The real launcher will show them once the bundle with picture stickers is promoted.
- The copies pushed to /data/local/tmp/cove for Oma Dev were removed afterwards; nothing is left there.

## Latest: the chat scrolls to a message just sent (2026-10-12, 17:45 PDT)
- Boss: a sent message ended up under the field and the keyboard. Cause: the thread is a reversed LazyColumn with keys and no scroll handling, so adding the new task kept the old newest one in view and put the new one below the edge.
- Change (commit a180b2d, oma/OmaChatActivity.kt): the list has a state, and whenever the newest task changes it scrolls to it. Covers a message sent from the field, a follow-up, and one that came in by voice while the chat is open.
- `oma-build`: PASS. NOT seen running: Oma Dev has no agent, so nothing can be sent there. In the next bundle, not promoted.

## Latest: rich text in the Oma chat and picture stickers, for the next bundle (2026-10-12, 17:26–17:42 PDT)
Commit 0eee452. `oma-build`: PASS, 760 tests, 0 failures. Not promoted; the real launcher is on the earlier bundle.
- Markdown in the chat (oma/Markdown.kt, MarkdownText.kt new; OmaChatActivity.kt): the agent's answers are parsed and drawn from the pack: headings, bold, italic, strikethrough, inline code, numbered and nested bullet lists, quotes, fenced code that scrolls sideways, pipe tables as a grid, links in the accent colour. His own messages, step lines, errors and "not sent" lines stay plain.
  - SEEN in Oma Dev on the cover screen, with a chat history file put in place by hand: every one of those drew as it should; `snake_case_name` in code and "2*3*4" stayed literal. NOT seen: tapping a link, a live streaming answer, a very long answer, the inner screen.
- Picture stickers (annotations/StickerPictures.kt, StickerBitmaps.kt new; Annotations.kt, AnnotationStore.kt, AnnotationBook.kt, ui/Stickers.kt, Notes.kt, HomeMenu.kt, Ink.kt, phone/PhonePages.kt): every PNG, WebP, JPEG or GIF in the guest's ~/.config/omalauncher/stickers (one folder down is a group) is in the sticker picker after the six drawn ones. Placing one copies its bytes to filesDir/annotations/stickers/<hash>.<ext>; the page names only that copy. A page with a picture sticker cannot be read by an older build.
  - SEEN in Oma Dev on the cover screen, with a 192x96 test PNG put in stickers/cove/ by hand: the picker showed it under a "cove" caption; tapped, it landed on Page 1 in free space, selected, in a frame of its own 2:1 shape with turn/delete. After the library folder was deleted and Oma Dev restarted, the sticker was still drawn from its copy. No crash.
  - NOT seen: resize, turn, carry to another page, many pictures (scrolling), a corrupt file, `oma-phone board` listing one.
  - Found: a launcher with no Linux removes files/arch at start, so the library cannot exist there; and the library is inside the Linux root, so reinstalling Linux empties it (placed stickers stay). Moving it out needs Proot.kt/LauncherData.kt.
- Cove's own art: not copied. The Mac did not answer over Tailscale (ssh timeout to 100.120.219.46) at 17:20. On the real phone ~/.config/omalauncher/stickers now exists and is empty, waiting for it.
- Oma Dev is left with the sample chat and the test sticker.

## Latest: the cover-screen checks, on the promoted bundle (2026-10-12, 17:23–17:26 PDT)
Boss closed the phone. Screenshots 1248x1972, look riso.
1. PASS — one page, label "Page 1  1/2", dock is four app icons then "↑" and "✎".
2. PASS — "↑" opens the app list: "ALL APPS", "CLOSE ↓", four apps across, search and the gear at the bottom.
3. PASS — the page label opens "all pages": both pages on plain paper with handwritten names and a dashed "+".
4. PASS, changed on purpose — "✎" replaces the dock with a centred row of NINE square keys (pen, highlighter, eraser, note, sticker, text, undo, redo, ×), shrunk to fit edge to edge; "×" brings the dock back.
5. Not repeated here (looks were tried on the inner screen today).
6. PASS — heavy display numbers and spaced capitals, as on the inner screen.
7. PASS, fixed — `oma-phone page "Page 2"` now really turns the cover screen to "Page 2  2/2" (it only changed the reported page before), and `page "Page 1"` turns back.
- Status bar: dark and readable in riso on the cover screen after the fold.
- Not compared pixel by pixel with design/oma-scrapbook-prototype.html; that comparison is still open.
- Boss also decided: Cove's stickers are saved locally on the phone for now. Two agents are at work: Markdown in the Oma chat, and picture stickers from ~/.config/omalauncher/stickers. The Mac is unreachable over Tailscale right now, so Cove's art itself cannot be copied yet.

## Latest: the bundle is on the real launcher (2026-10-12, 17:06–17:15 PDT)
- `oma-install --status`: "In-place launcher update verified."
- FAIL (new, after the update): phone control came back disconnected. `oma-phone status` said active false, connected false; `dumpsys accessibility` listed dev.omalauncher.boss/.phone.PhoneService under "Enabled services" and "Crashed services" with nothing bound, for at least a minute. The earlier promotions today reconnected by themselves. No crash was in the log by then (it had rolled over), so whether the new service crashed on its first bind or Android just did not rebind it after the package update is not known. The phone then restarted itself for a security update, and after that it was connected and active. Watch this on the next promotion.
- PASS — `oma-phone click` on the dock's "All apps" node opened the app list; on the list's gear node opened settings; on the close node closed it. These did nothing before.
- PASS — `oma-phone read` straight after each of those returned the new screen ("ALL APPS | CLOSE ↓ ...", then "settings ...", then home), not the previous one.
- PASS — a 3000 ms `oma-phone swipe` is accepted (the limit was 2000).
- NOT seen yet: `page "Page 2"` turning on the cover screen (phone is open), the voice confirm step and a chat follow-up (Boss's to try), the status bar after a fold, the cover screen's nine drawing keys.
- End state: home, riso, no menu open. Screen timeout is still set to never (was 30000 ms).

## Latest: the bundle is being promoted (2026-10-12, about 17:10 PDT)
- Boss said "promote". Running `oma-try` as the last step of this run; the result is NOT known from this run.
- Next, in a new request after Oma reopens: `oma-install --status`; then try what could not be seen before promotion: `oma-phone click` on the dock's "All apps" and `read` straight after a tap, `page "Page 2"` on the cover screen, a swipe longer than 2 s, the voice confirm step, a follow-up sent while a task runs, the status bar after a fold. Then discuss next steps with Boss (Cove's picture stickers are one candidate).
- Screen timeout is still set to never (was 30000 ms).

## Latest: drawing-tools keys and follow-ups in the Oma chat, added to the bundle (2026-10-12, 16:50–17:05 PDT)
Commit add2e27. `oma-build`: PASS, 723 tests, 0 failures. Nothing promoted.
- Drawing tools row (ui/Ink.kt, ui/Notes.kt, annotations): nine keys now: pen, highlighter, eraser, sticky note, sticker, text, undo, redo, close. New `NoteRole.Text` ("text"): a note with no paper, in the page's ink. A page file with a text note cannot be read by a build older than this one. The keys shrink together when nine do not fit the row (cover screen). Adding any of the three ends drawing, since nothing on the page can be moved or typed in while a finger draws.
  - SEEN in Oma Dev, inner screen: nine keys in the row; "T" opened a typing box titled "note" (now "text") with no paper, and the words landed on Page 1 in free space in the page's blue; "★" opened "stick on this page" with the six stickers, the heart landed selected with its turn/delete bar and the tools closed.
  - Not seen: the sticky note key itself (same path as text), the cover screen's smaller keys, the console look's word row.
  - Seen, cosmetic: the sticker picker's dim does not reach under the status and navigation bars when opened from the tools row.
- Follow-ups while a task runs (oma/FollowUps.kt new, ChatHistory.kt, ChatComposer.kt, OmaChatActivity.kt, assist/AgentTasks.kt): the chat's bar keeps the field and shows send beside stop; a follow-up waits in a queue of at most five and is sent as the next request when the task ends, in the same Claude session; stop, a failed task, or the app being closed drops what waits and the thread says so. It never touches the running process, so it cannot answer an approval. Writing into Claude's stdin mid-turn was not done: the end of a task is told by the first `result`, and that could not be checked without running it.
  - SEEN: Oma Dev's chat screen opens without a crash. NOT seen: a follow-up sent while a task runs; Oma Dev has no agent, so this needs the real launcher.
- Cove's sticker drawer: it is pond's "art bank" (src/client/components/bench/ArtBankShelf.tsx, shapes/StickerShape.tsx): image files per board from Cove's server, placed as pictures. This app's stickers are six drawn shapes. Bringing Cove's in needs picture stickers and a source for the art; not in this bundle, waiting on Boss.

### The bundle waiting for one promotion
Carrying notes across pages and past the last page; note, sticker and text keys; voice confirm step; follow-ups in the chat; status bar follows the look; phone control (read, click, page turn, 5 s swipes). First real test of the voice, chat and phone-control parts is after promotion.

## Latest: a bundle from four agents in parallel (2026-10-12, 16:30–16:50 PDT)
Boss asked for the open items to be done in parallel and bundled; nothing here is promoted. Commit 4c8934a. `oma-build`: PASS, 712 tests, 0 failures.
- Voice confirm step (assist/ListeningRequest.kt new, ListeningScreen.kt, OmaAssistant.kt): after he stops talking the words wait under "SEND THIS?" with Send, Add more, Cancel; no timer, nothing sent until Send; Add more appends with one space; Cancel or a tap on the paper sends nothing. Tests: ListeningRequestTest (10), including Cancel sends nothing (handoff item 4). NOT seen running: Oma Dev has no assistant; needs the real launcher.
- Phone control (phone/PhoneService.kt, PhoneControlPolicy.kt `PhoneTouch`): `read` clears Android's node cache and waits out 350 ms after this service's own action; `click` resolves on fresh nodes and falls back to a tap at the node's centre when ACTION_CLICK is refused; swipe may last 5000 ms. On theory only, not confirmed; `oma-phone` talks to the real launcher, so none of it can be tried before promotion. PhonePagesTest's pinned count of access checks went from 2 to 3 on purpose: the delayed read asks again, as the screenshot does.
- `page` not turning on the cover screen: cause found in ui/Home.kt, the effect keyed on `requestedPage` cleared its own key before `animateScrollToPage`, which cancelled the scroll a frame in (since c64a8d0). Fixed by launching the scroll in the composition's scope. Also affects turning from the overview. Not seen running yet.
- A note sent while another page shows going to the page the request came from: by design (PRD AC-29), no change.
- Status bar (ui/Theme.kt, PackAdapter.kt `barsOnLight`, LauncherState.kt, MainActivity.kt): cause was `enableEdgeToEdge` re-applying the bars from activity start on every fold or rotation. Bars are now set directly from the look, the app list and any open panel. SEEN in Oma Dev, inner screen: bujo has dark, readable clock and icons; city pop has white ones on its blue sky. Not seen: after a fold, panels in city pop and instrument.
- Carrying notes (ui/Notes.kt, HomeGrid.kt, annotations): past the last page a new page is offered and withdrawn if nothing lands; a note is no longer left on a page that turned away; a full page says so once. SEEN in Oma Dev, inner screen: the note on Page 2 held at the right edge now lands on Page 3 as the page turns (it stayed behind before). Not seen: the offered page (needs a hold longer than the 2000 ms the installed launcher's swipe allows), the cover screen, a full page.
- Decisions Boss made today are in PHONE-HANDOFF.md.
- Oma Dev is left in riso with the test notes on Pages 1 and 3.
- Next in this bundle: note, sticker and text buttons in the drawing tools row.

## Latest: notes and stickers can be carried to another page (2026-10-12, 16:17–16:26 PDT)
Asked by Boss: a long press lifts a note, but it could not be dragged from one page to another.
- Change (commit 0c1ec9a): `carryScrap` in widgets/annotations/Annotations.kt (the rule: off one page, onto another at a place, the owner's and on top, a new id if the page has one like it, refused if that page is full), `AnnotationBook.moveScrap`, and in ui/Notes.kt the carry now follows the finger in screen coordinates and changes page through a `Crossing` that HomeGrid.kt gives it: side by side the finger picks the page; resting at a side edge for PAGE_TURN_MS turns the page, as for a tile.
- `oma-build`: PASS, 684 tests (5 new in AnnotationsTest), 0 failures.
- Seen in Oma Dev on the inner screen (a note selected by long press, then dragged with `oma-phone swipe`):
  - PASS — dragged from Page 1 to Page 2 of the open pair: it left Page 1, landed on Page 2 under the finger, still selected with its edit/delete bar and handle.
  - PASS — a note on Page 3 held at the left edge: the page turned to "Page 1 + Page 2" and the note landed on Page 1.
  - As built — a note on Page 2 held at the right edge: the page turned to Page 3, but the finger was then over the empty "+ new page" half, so the note stayed on Page 2 (a tile has nowhere to land there either).
- NOT tested: the cover screen (one page at a time), stickers by hand, a long press and drag in one touch (`oma-phone swipe` cannot hold first; it only drags a note already selected), and a full target page.
- Not promoted: the real launcher does not have this yet. Asked Boss.
- Oma Dev is left with two test notes ("edge test" on Page 1, "drag test" on Page 2).
- Limit found: `oma-phone swipe` refuses a duration over 2000 ms ("Failed requirement.").

## Latest: the closed-screen checks, run on the OPEN inner display (2026-10-12, 16:13–16:16 PDT)
Boss opened the phone and asked for the inner-display tests. ON-PHONE-TESTS.md lists 1–6 for the cover screen only, so these are the same checks adapted to the inner screen (2448x1848), build 29 with note placement, look riso. The cover-screen versions are still not done as a set.
1. PASS — home shows both pages side by side, label "Page 1 + Page 2  1/2"; the dock is one row of ten app icons, then "↑" and "✎".
2. PASS (seen 14:41 on build 28, not repeated) — app list: "ALL APPS", "CLOSE ↓", eight apps across on this screen, search and gear at the bottom.
3. PASS — tapping the page label opens "all pages": Page 1 and Page 2 as small pages on plain paper with handwritten names, and a dashed "+". Back closes it.
4. PASS — "✎" replaces the dock with a centred row of six square buttons (pen, line, eraser, undo, redo, ×); "×" closes it and the dock returns.
5. PASS — a long press on an empty spot of Page 2 opens the menu; its last row is "LOOK". Tapped bujo, then city pop: home redrew in each behind the open menu (paper, tiles, dock buttons, city pop's wallpaper). Scrolled the row and tapped riso: home is back in riso.
6. PASS — "12" and "using Bash…" in the heavy display face; "MONDAY", "TUE", "WED", "OMA" in spaced capitals.
7. PASS (commands) — `page "page two"` answered Page 2 and `pages` showed it; "holiday" answered no match; turned back to Page 1. Both pages are on screen here, so nothing visible changes.
9. PASS — the "oma" tile read "using Bash…" throughout.
- Seen: in bujo the status bar's clock and icons are white on white paper and cannot be read (only the battery "93" shows); in city pop they are faint. In riso they are dark and fine.
- Seen: on the inner screen the note in cells 3,8 touches the OMA tile's shadow but covers no tile.
- COULD NOT TEST — dragging a note. The code has it (ui/Notes.kt: tap opens the text, a long press lifts the note to drag, a long press that does not move selects it with a resize handle). `oma-phone swipe` moves from the first instant, so it cannot hold and then drag. Boss asked for notes to be draggable; he needs to try a long press and say whether it fails.
- End state: home, riso, Page 1, no menu open. Nothing of Boss's moved or changed.

## Latest: old notes removed; Oma Dev told apart by name and colour (2026-10-12, 16:07–16:12 PDT)
Asked by Boss from the phone.
- Notes: deleted the three notes left by the old build over widgets (one on Page 1 over the today tile, two on Page 2 over the terminal), each by tapping it and "delete" in the note menu. `oma-phone board` afterwards: Page 2 has no notes, Page 1 has only the one in the free strip. The later `oma-phone note` replaced that one in place (`replaced:true`, cells 3,8 3x1, `clear:true`).
- Change (commits aa0f13e, 4f9ba4c): a `labelSuffix` manifest placeholder, empty by default, on the three other app-list entries ("Oma", "Phone control", "Oma assistant"); the accessibility service is now "${appLabel} phone control". dev.init.gradle sets the suffix to " Dev", names the app "Omalauncher Dev" and adds `tools/fold-development/dev-res` (a magenta icon background) to the debug resources.
- `oma-build`: PASS, 679 tests, 0 failures. The working launcher's APK keeps its labels: "Omalauncher Boss", "Phone control", "Oma assistant", "Oma". It was built (versionCode 31) but NOT installed.
- `oma-dev --no-open`: installed. Its APK's labels: "Omalauncher Dev", "Phone control Dev", "Oma assistant Dev", "Oma Dev".
- Seen on the phone: searching "oma" in the app list shows "Oma" with the dark icon and "Oma assistant Dev", "Oma Dev", "Omalauncher Dev" with magenta icons. The dock's Oma Dev icon is magenta too.
- FAIL (phone control, new): on the cover screen `oma-phone page "Page 2"` answered ok and `pages` said showing "Page 2", but the screen stayed on "Page 1  1/2". A swipe turned it. And `oma-phone note` sent while Page 2 was showing went to Page 1.
- Screen timeout is still set to never (was 30000 ms).

## Latest: note placement on the real launcher — PASS (2026-10-12, 16:04 PDT)
- `oma-install --status` after the restart: "INSTALL_SUCCESS ... In-place launcher update verified." Linux, login and this agent came back.
- `oma-phone board` (Page 1, cover screen, grid 6x9): five tiles, one old note on cells 0,0 3x2 (over the today tile, left by the old build), free: one rectangle, col 3 row 8, 3x1.
- `oma-phone note ...`: `{"ok":true,"page":"Page 1","replaced":false,"cells":{"col":3,"row":8,"w":3,"h":1},"clear":true}`. Board afterwards lists it at x 0.5064 y 0.8901 w 0.44 h 0.0992 and free is now empty.
- Screenshot: PASS. The new note sits in the strip under the "OMA" tile, right of the owl tile, above the dock, and covers no widget. Its top edge meets the OMA tile's pink shadow; its text is cut to one line ("Update landed. This note w...") because the spot is one cell high.
- Still there: the old note over the today tile (not moved; Boss's page). Page 1 now has no free cells, so the next note should answer clear:false.
- Seen: the dock shows two Oma-style icons (one is presumably Oma Dev). Not touched.
- Screen timeout is still set to never (was 30000 ms).

## Latest: promotion started (2026-10-12, about 16:03 PDT)
- Boss said "promote it". Running `oma-try` as the last step of this run; the result of the update is NOT known from this run.
- Next, in a new request after Oma reopens: `oma-install --status`, `oma-phone board`, one note on Page 1, screenshot, check it sits in the free area and covers no widget. Screen timeout is still set to never (was 30000 ms).

## Latest: Oma Dev through setup to home (2026-10-12, 15:58–16:01 PDT)
- Boss said the download is fine (unlimited hotspot data) and asked to verify where the note lands.
- Setup "start" (default theme tokyo night, Linux box left unticked): PASS. Home came up on the cover screen: "Page 1  1/3", the today tile, the fetch tile (Android 17, 69 apps), the "OMA ready" tile, a terminal tile saying "needs Linux ... install", and the dock with only "↑" and "✎". No crash in the crash log.
- Android then asked "Set Oma Dev as your default home app?". Not accepted: `oma-phone tap` on Cancel did nothing (system dialog), `oma-phone back` dismissed it. HOME still resolves to dev.omalauncher.boss.
- COULD NOT TEST in Oma Dev: note placement. It is only reached through the agent's `oma-phone note` / `board` (phone/PhonePages.kt), and `oma-phone` talks to the launcher the agent runs in, which is the real one. Oma Dev has no Linux and no agent, so nothing there can leave an agent note.
- The real launcher is build 29 installed 15:03:03, before the note-placement commit (539db8a, 15:07:54), so it does not have the change: the notes left today overlap widgets, as Boss saw. Verifying needs promotion (`oma-try`), which is waiting on Boss's yes.
- Also changed at Boss's request (15:57): screen timeout set from 30000 ms to 2147483647 (never) with `settings put system screen_off_timeout` over local adb. Put it back to 30000 when he says.

## Latest: note placement as Oma Dev — starts (2026-10-12, 15:54–15:56 PDT)
- Installer is back: Boss turned wireless debugging off and on while on a hotspot; adb is "device" on 127.0.0.1:37779. So the earlier block was wireless debugging not serving once off its Wi-Fi, with the switch still showing on.
- `oma-dev --no-open` at c420089 (source 78ea5a9 plus results-file commits): exit 0, "Oma Dev installed: versionCode 29, 0.5.0-boss-preview.19. The working launcher was not touched." lastUpdateTime 15:55:14.
- `oma-dev --open`: PASS for "it starts". Process running (pid 32512), crash log empty, screenshot shows first-run setup on the cover screen (omalauncher title, THEME row, "also set up Arch Linux", start).
- Not done: setup was not completed (start downloads about 70 MB and Boss is on a hotspot), so home and note placement were NOT seen in Oma Dev. Note placement itself has only passed unit tests (679, 0 failures).
- Seen, matches "Next" item 3: setup still offers the Omarchy themes (tokyo night, vantablack, white...) and no look.
- Left on the working launcher's home. Asked Boss whether to promote; `oma-try` not run.
- Next after promotion, in a new request: `oma-phone board`, one note on Page 1, screenshot, check it covers no widget.

## Latest: note placement as Oma Dev — BLOCKED at install (2026-10-12, 15:42–15:47 PDT)
Asked by Boss from the phone: PHONE-HANDOFF.md, "Next" item 1.
- `git pull --ff-only`: fast-forward to 78ea5a9 (note placement, build 29, the handoff).
- `oma-build`: PASS. BUILD SUCCESSFUL in 1m 3s; 679 unit tests, 0 failures, 0 errors, 2 skipped (includes NotePlacementTest and PhoneNotesTest). This only passed tests; nothing was seen running.
- `oma-dev --no-open`: FAIL, exit 1. The build step succeeded, then: "Local installer is disconnected. Wireless debugging must be on (it needs Wi-Fi). Nothing was changed." `oma-dev --status` gives the same line. Oma Dev was not replaced and not opened, so "confirm it starts" is NOT done.
- What I saw: Wi-Fi is up (wlan0 192.168.0.187). The cached port 44421 is closed. A port scan of loopback finds 42939 open and adb connects to it, but the device stays "offline" (never "device") through adb kill-server, disconnect/connect and 20 s of waiting; the same over the Wi-Fi address. So wireless debugging is listening on a new port but is not accepting this checkout's adb key. An hour earlier (14:43) the same key worked on 44421.
- Not tried, because they are Boss's: toggling wireless debugging, pairing again, any Android setting.
- Next: Boss opens Developer options > Wireless debugging and checks it is on and that this device is still listed under paired devices (pair again if not). Then rerun `oma-dev --no-open`, `oma-dev --open`, screenshot, `oma-phone home`, and ask Boss about promoting.
- Update 15:50–15:53: Boss says wireless debugging is on and he is off home Wi-Fi, on Tailscale. Tried adb to 42939 on loopback, the Tailscale address (100.71.22.108) and the wlan0 address: each connects at TCP level and stays "offline". adb trace: we send CNXN and get no reply at all (no STLS, no AUTH), then "connection terminated: read failed". A scan of ports 1024-65535 on all three addresses finds no other lasting listener. Developer options shows the Wireless debugging switch on. The debugging rows are not in `oma-phone read` and taps on the row did not open its detail screen, so I could not read its port or paired devices; left alone.
- Note — `oma-install`/`oma-try` use the same local adb, so promotion is blocked by this too.

Written by the agent on the Fold (Oma). Build under test: 0.5.0-boss-preview.18 (versionCode 28).
The launcher installed on the phone reports version code 28 (`oma-install --status`).

## Build on the phone (2026-10-12, 14:38–14:40 PDT)
- `git pull --ff-only`: already up to date at e02803b.
- `oma-build` (no arguments): PASS. BUILD SUCCESSFUL, exit 0, 152 s wall clock (Gradle reported 2m 29s; 47 tasks executed, 1 from cache).
- Unit tests: 654 tests in 66 classes, 0 failures, 0 errors, 2 skipped.
- Debug APK: built, `app/build/outputs/apk/debug/app-debug.apk`, 38,075,842 bytes, written 14:40.
- Errors: none. Only Kotlin warnings (unnecessary `!!` in NotebookStartTest.kt, deprecations).
- Not installed: `oma-install` and `oma-try` were not run.

## On-phone checks (2026-10-12, 14:40–14:42 PDT)
Phone state: unlocked, OPEN (inner screen, screenshot 2448x1848), `oma-phone status` connected and active.
Look at start: riso. Page showing at start: Page 2 (home shows both, label "Page 1 + Page 2  1/2").

### If the phone is CLOSED (checks 1–6)
1. COULD NOT TEST — phone is open; folding needs Boss's hands.
2. COULD NOT TEST — same.
3. COULD NOT TEST — same.
4. COULD NOT TEST — same.
5. COULD NOT TEST — same.
6. COULD NOT TEST on the cover screen. Seen on the open screen only: the date "12" and "using Bash…" are in a heavy display face, "MONDAY", "TUE", "WED", "OMA" in spaced capitals.

### In either state (checks 7–9)
7. PASS — `oma-phone pages` lists "Page 1", "Page 2". From Page 1, `oma-phone page "page two"` answered `{"ok":true,"page":"Page 2"}` and `pages` then reported showing "Page 2". `oma-phone page "holiday"` answered "No page matches that name. Nothing was changed." with showing unchanged. Left on Page 2, as found. Limit: open, home shows both pages side by side, so the turn is only visible in the `showing` value, not on screen.
8. PASS — app list opened ("ALL APPS", "CLOSE ↓", eight apps across on the open screen, search field and gear at the bottom). Gear opened settings (look: riso). "look" showed nine looks as small pages: console, bujo, city pop, custard, dial-up, instrument, 1-bit, riso, tiny magazine; riso outlined in pink and marked "IN USE". Closed with ×; no look was changed.
9. PASS — the "oma" widget on Page 1 read "using Bash…" in every screenshot while working, never "ready".

### Seen along the way (not on the list)
- FAIL (phone control) — `oma-phone click SNAPSHOT NODE` returned ok but did nothing on the dock's "All apps" node (root.0.0.0.2.9) and on the app list's gear node (root.0.0.0.2.4); `oma-phone tap X Y` on the same spots worked.
- FAIL (phone control) — `oma-phone read` lags the screen: right after a tap it returned the previous screen's nodes (home after the app list had opened; settings after settings and the app list had closed), each time with a new snapshot id. Screenshots were correct. A second read a moment later was sometimes still stale.
- Note — opening the app list focuses search and raises the keyboard, which moves the gear from the bottom of the screen to mid-screen; a tap aimed at the gear's first position landed on the keyboard area (nothing was typed, the field stayed empty).
- Note — two × taps at the same spot (2365,200) went from the look screen to home with the app list closed too. Reads were stale, so I did not see the steps in between and cannot say which tap closed which layer.

### End state
Home, riso look, Page 2 showing, no menu, overview, app list or drawing tools open. One sticky note left on Page 2 by `oma-phone note` (the harness asks for one per finished task); delete it if unwanted. Nothing typed into any app, no tiles/pages/notes changed, no Android settings changed, nothing installed.

## Oma Dev, the second launcher (2026-10-12, 14:43–14:44 PDT)
- `git pull --ff-only`: fast-forward db7b921..1df463f (4 files: ON-PHONE-TESTS.md, fold-development README, dev.init.gradle, oma-dev).
- `install -m755 tools/fold-development/oma-dev /usr/local/bin/oma-dev`: exit 0, no output.
- `oma-dev --no-open`: PASS, exit 0, 38 s wall clock. Gradle: BUILD SUCCESSFUL in 32s, 42 tasks (16 executed, 26 up to date). No errors; warnings only (experimental aapt2 override, a manifest warning at AndroidManifest.xml:33, Gradle deprecations). Last lines of output, exactly:
```
42 actionable tasks: 16 executed, 26 up-to-date
Configuration cache entry stored.
Oma Dev installed: versionCode 28, 0.5.0-boss-preview.18. The working launcher was not touched.
```
- `oma-dev --status`: exit 0. Exact output:
```
Oma Dev is installed: versionCode 28, 0.5.0-boss-preview.18
```
- Working launcher untouched: PASS. `dumpsys package` afterwards: dev.omalauncher.boss versionCode 28, 0.5.0-boss-preview.18, lastUpdateTime 2026-10-12 14:27:21 (before this run), same process still running, still the HOME app. dev.omalauncher.dev versionCode 28, firstInstallTime and lastUpdateTime 2026-10-12 14:43:48. This session kept running, so the launcher was not restarted.
- Oma Dev was not opened (`--no-open`) and has not been tested yet.
- Note — Oma Dev's versionName is "0.5.0-boss-preview.18", the same as the working launcher's; only the package and label tell them apart.
- Note — side effect: oma-dev moves the built APK to /root/.cache/oma-dev/oma-dev.apk, so `oma-install --status` now answers "No working-launcher APK found. Run oma-build first." instead of the installed version (it said "Working launcher installed version: 28" just before). As intended for installing, but the status check is lost until the next oma-build.
- `oma-install` and `oma-try` were not run.
