# Working on Oma from the phone alone

For the agent on the Fold (Oma), when Boss works with no Mac in the loop. Written 2026-10-12 by the Mac session.
Boss talks to you through Oma (voice or the Oma chat). Be short: he is often reading on the move.

**Moving to a new phone (2026-10-14): read `docs/OPEN-BUGS.md` first — open bugs, unseen features, and what is not in git.**

## Where things stand (checkpoint 2026-10-14, about 03:20 PDT, written by the agent on the Fold)
- Branch `phone/fold-development` at 6446e93 and after; promoted right after this checkpoint (the batch below).
  The day's detail is at the top of `docs/PHONE-TEST-RESULTS.md`; the Mac side is `docs/MAC-RUNNER-SPEC.md`.
- **How Boss talks to agents now.** One chat, Oma. A front agent (Haiku, `assist/QuickLane.kt`) answers or routes
  with three marks only: `<<build>>` (phone builders A and B, `assist/Builders.kt`; launcher and phone work),
  `<<mac ID>>` / `<<mac-new ID>>` (project threads on the Mac Pro through `tools/mac-runner/oma-runner`, reached over
  Tailscale with the key in `~/.config/omalauncher/mac-runner.json`). Stop / Restart / Clean up are tab-menu rows,
  not the model's. Hand-overs carry the recent front exchanges as context; a hidden note asks parallel agents to
  use their own worktree.
- **Chat:** header dots per thread at the right, "▾" folds a tab strip (Oma, Phone A/B, Mac threads, ＋). ＋ is
  always a Mac session (web tools only for the front agent there; never asks which project: a named thread's folder,
  else a new thread in `~/Projects`, or says the Mac is unreachable). Tabs auto-name, long-press: Rename, Pin, Stop, Restart, Copy last answer, Archive, Archived (N).
- **Home:** page "Agents" holds live thread tiles (only that page is ever written); idle ones leave after 12 h.
- **Mac runner:** Claude, Codex and Cursor threads; a Claude limit falls back to Codex then Cursor (15 min memory).
- **Guest tools:** `oma threads | keep | status | help` (old names `oma-thread`, `oma-keep`, `oma-agents` alias it).
  `oma keep run NAME -- CMD` is the only way a process outlives an agent's turn.
- **Standing rules learned this week:** bundle promotions; no sticky notes unless asked; the Critic only for long
  tasks, big batches or escalations (it is slow and takes over his screen); a background subagent dies when a new
  message restarts the builder, so run long subagents in the foreground; Tailscale on the phone does not come back
  by itself after a reboot (the Mac reads unreachable until he opens the Tailscale app — it was unreachable at this
  checkpoint); `assist/TailscaleHeal.kt` turns it on only if it has not been up since boot, so his manual off stands.
- **Agreed next:** the Mac builds and tests Oma (request at the end of MAC-RUNNER-SPEC.md; the signing key stays on
  the phone, so the install stays a phone step). Open question for Boss: should a named-but-empty ＋ chat let ＋
  start a fresh one?
- Not seen on the real launcher yet: the simplification pass, ＋ with the Mac reachable, the 12 h archive path.
- Changed on the phone for Boss, still to put back when he says: screen timeout never (was 30000).

## Earlier checkpoint (2026-10-12, about 23:20 PDT, written by the Mac session)
- This checkout: `/root/Projects/omalauncher`, branch `phone/fold-development`. `git pull --ff-only` first.
- Installed: the real launcher `dev.omalauncher.boss`, versionCode 45 (commit a4700de), and the throwaway
  `dev.omalauncher.dev` ("Oma Dev": every entry says Dev, magenta icon), older. You are running inside the real one.
- The goal of the project: make the launcher match the agreed prototypes in `design/` one-to-one. Status and
  information surfaces are placeable widgets, not always-on chrome. No Oma button in the dock.
- Seen working on the real launcher today, from the Mac over adb or run by you: Markdown in the chat (a link, a long
  answer, a code block scrolling sideways), the chat scrolling to a sent message, picture stickers from all four
  packs placed, resized, turned and carried, the voice confirm step (Send, Add more, Cancel), follow-ups (Boss),
  plain words on the oma tile and in the task notification, numbered lists in a column, the sticker picker's scrim
  over the bars, no hint on an empty page. NOT seen: the answer card's plain words, a voice request over seven lines,
  the "phone control disconnected" state of the oma tile. Seen on the inner screen too (20:30–20:50): home, the
  chat with Markdown, the answer card in plain words, the listening screen, the nine drawing keys, the sticker
  picker with its scrim, and the sticker, page and look commands.
- Phone control by what controls say (versionCode 35 on): `oma-phone screen`, `press`, `fill`, with
  `--in PACKAGE`, and `expect` (37 on). And by name on home (36 on): `stickers`, `stick`, `sticker ID turn|size|move|remove`, `page-add`,
  `page-rename`, `page-delete` (empty pages only), `looks` and `look NAME` (38 on); `board` gives every note and
  sticker an id, size and turn, and a changed sticker's answer says `clear:false` when it lies on something. Run by you on a
  throwaway page on 2026-10-12: all passed; 70–150 ms a sticker, page or board call, about 550 ms a press or fill. Use these, not taps aimed off a screenshot.
- Phone control after an update: Android can leave the service switched on and never bound. `oma-install`'s job now
  waits 20 seconds and turns it off and on if so; `oma-install --status` says BOUND, HEALED or STILL_DISCONNECTED.
  Seen healing a stuck update once (19:29). The launcher also tells: `oma-phone status` has `link`, and the oma tile
  reads "needs attention" with the remedy when it is disconnected between tasks (that tile state is not yet seen).
- Stickers: 79 pictures (cove 12, pepe-blanket 20, donut-frog 26, rabbit-shark 21), copied from ClaudeSync
  `projects/pond`. On the phone only, not in Git. Since versionCode 38 the library is the launcher's own
  `files/sticker-library`, bound into the guest as `~/.config/omalauncher/stickers`: it outlives a reinstall of
  Linux. The move was checked file by file (79 of 79, same checksums).
- Lock screen (versionCode 39 to 42, 2026-10-12 evening). Style > "wallpapers: look's paper" is ON: the launcher
  sets the lock and home wallpapers to the look's paper when a look is picked, with nothing drawn on it (the scraps
  it once drew were taken out at Boss's word, 44). It sets the display in use; since 45 it also asks for the fold's
  other display by Samsung's display mark, which has NOT been seen to happen: up to this checkpoint the launcher
  had only ever noted the cover's size (`lock_paper_sizes`), because the phone was not opened to home after 45.
  To prove it: open the phone to home, pick a look, and look for `setBitmap ... which = 19` in the launcher's log. Two widgets the launcher draws in the look: "Oma status"
  (on the cover lock screen now, placed through LockStar's App Widgets) and "Oma clock" (Boss does not want it on
  the lock screen; Samsung's clock stays). `oma-phone lockstar [capture NAME | apply NAME]` keeps and hands back
  LockStar's layout through Good Lock's share provider; an apply drops app widgets, so it is a backup tool, not
  how looks are followed. The launcher holds WRITE_SECURE_SETTINGS for it, granted by Boss over adb.
  `tools/fold-development/oma-locktidy` lays lock-screen widgets out on the home grid from the open editor; it has
  only been run from the Mac, on one widget. In Samsung's editor never press Back with a widget selected (it leaves
  and drops unsaved work), and never tap near Done.
- Changed on the phone for Boss, to put back when he says: screen timeout is set to never (`settings put system
  screen_off_timeout 2147483647`; it was 30000).
- Tailscale on the phone does not come back by itself after a restart; opening the Tailscale app reconnects it.
- The Mac session reaches you by typing into the Oma chat over adb and reading `files/oma-chat.json`, and runs
  commands itself through the debug door when it has opened it. Since 2026-10-17 it
  has the Android SDK and the Fold 8 emulator, so it can build and test what it pushes before you run `oma-build`.

## How a change reaches the phone
1. Edit and commit here. `oma-sync` commits and pushes to the shared branch (the Mac pulls from it).
2. `oma-build` runs the unit tests and builds; it must be green before anything else. Under a minute once Gradle is warm; about 2.5 minutes the first time.
3. Test it on the Mac Pro's Fold 8 emulator, not on this phone (see `docs/EMULATOR.md`). Oma Dev is retired
   (2026-10-17, Boss): never build or install it here, and remove it with `oma-dev --remove` if you find it. Boss wants
   nothing extra running on his phone. From the phone, push and write in `docs/PHONE-TEST-RESULTS.md`
   what the Mac should check; the Mac session installs the build on the emulator, drives it over adb and writes back
   what it saw. The emulator is plain Android 17 with both Fold screens and the fold, so it shows layouts on both
   screens, the looks, the app list, settings and the drawing tools; `docs/EMULATOR.md` keeps the list of what has
   been seen working there. It cannot show Samsung's own screens, the real phone's memory and battery behaviour, or
   Boss's own pages.
4. Promote to the real launcher ONLY when Boss says so in that conversation: `oma-try` (it builds, syncs and
   schedules the update). The update restarts the launcher and ENDS YOUR OWN RUN, so make it the last thing you do,
   and tell Boss first what to check afterwards. Never uninstall or clear the real launcher.

## Rules
- This is Boss's daily phone. Do not open banking, payment, messaging or email apps, do not type into other apps, do
  not change Android settings, do not approve anything on his behalf.
- Do not move, resize, rename or delete his tiles, pages, notes or pinned apps unless he asks for exactly that.
- Drive the phone with `oma-phone screen`, `press`, `fill` and `expect`, and home with the sticker and page commands;
  `read`, `click` and `tap` are for what those can't reach. System dialogs still ignore `tap`; use `oma-phone back` there.
- Some tests pin what may write to Boss's pages (PhoneNotesTest) and what may run while an approval waits. If one
  fails, stop and say so: it is not yours to loosen.
- House rules in the code are enforced by tests that fail on purpose (`PackSourceCheckTest`, `MotionSourceCheckTest`,
  tests that pin a look's values). Fix the code; change a pinned value only when the look was changed deliberately.
- Match the code around you: comments in plain sentences that say why, no TODOs, no dead code.
- Say plainly what you saw working and what only passed tests. If a step failed or was skipped, say so.

## Next, in order (ask Boss which, if he hasn't said)
1. The Mac session can run commands in this Linux through the debug door, with `tools/fold-development/oma-mac`
   on the Mac. The door needs a key since versionCode 37 and is shut unless the Mac has opened it; never create
   `allow-debug-exec` yourself. Since 38 it is a broadcast, so a command brings nothing to the front. Boss is
   holding the Jev pilot.
2. Built and on the launcher but not seen: a carried note no longer shrinking away on the page it left (unit test
   only); `clear:false` after a sticker is grown over another (unit test only; the one try on the phone did not
   overlap); the oma tile's disconnected state. The dock fills up with recently opened apps when fewer are pinned
   than fit (ui/Dock.kt), which is why btop led it after a test opened btop: as built, not a fault.
3. Cove's papers, images and backgrounds (17 in the art bank) have no place here yet.
4. Compared with `design/oma-scrapbook-prototype.html` in riso on 2026-10-12 (rendered on the Mac beside the
   phone's screenshots, by eye, not pixel by pixel). Alike: the dotted paper, tilted tiles with the blue line and
   pink offset, the handwritten page name top left, the dock's icons then "↑" outlined and "✎" filled, heavy display
   numerals, spaced capitals, the open pair with one dock across. Different: the prototype draws its own status
   line in the look's ink and a gesture pill, the phone has Samsung's status bar and three-button bar; the
   prototype's scraps are freer (overlapping, varied, tape on most), the phone's tiles sit on the grid and only the
   terminal has tape; the prototype's open dock has a terminal key in the look's style. Never print lines 53-54 of
   that file whole: they are huge embedded images.
5. Later, parked by Boss: first-run setup offering looks instead of Omarchy themes (`ui/Setup.kt`); text sizes on the
   prototype's scale.

## Decisions that are Boss's, not yours
Decided 2026-10-12, from the phone:
- Voice: a confirm step for now, no auto-send after he stops talking.
- App list: the settings gear stays. The LINUX/ANDROID headings stay until he has a reason to change them for something better.
- Drawing tools row: add sticky note and sticker buttons, and a text button for typing text onto the page. Cove already has a
  sticker drawer that can be pulled from; its drawing tools are tldraw, which this app does not use, so only the art and the
  idea carry over, not the code.
- Text sizes: leave as they are; come back only if it proves a problem.
- First-run setup offering looks: not now. To do later.
- Promotion: he does not want the real launcher updated after every small change. Bundle fixes and features, test on the
  Mac's emulator what it can show, and ask once when a batch is ready. (Was Oma Dev until 2026-10-17.)

## When you finish a piece of work
Write what you did, what you saw, and what is next at the top of `docs/PHONE-TEST-RESULTS.md`, then `oma-sync`.
The Mac session reads that file.
