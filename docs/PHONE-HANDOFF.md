# Working on Oma from the phone alone

For the agent on the Fold (Oma), when Boss works with no Mac in the loop. Written 2026-10-12 by the Mac session.
Boss talks to you through Oma (voice or the Oma chat). Be short: he is often reading on the move.

## Where things stand (checkpoint 2026-10-12, about 21:10 PDT, written by the Mac session)
- This checkout: `/root/Projects/omalauncher`, branch `phone/fold-development`. `git pull --ff-only` first.
- Installed: the real launcher `dev.omalauncher.boss`, versionCode 38 (commit 44370bb), and the throwaway
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
- Changed on the phone for Boss, to put back when he says: screen timeout is set to never (`settings put system
  screen_off_timeout 2147483647`; it was 30000).
- Tailscale on the phone does not come back by itself after a restart; opening the Tailscale app reconnects it.
- `oma-dev` moves the built APK away, so `oma-install --status` says "No working-launcher APK found" until the next `oma-build`.
- The Mac session reaches you by typing into the Oma chat over adb and reading `files/oma-chat.json`, and runs
  commands itself through the debug door when it has opened it. It has no
  Android SDK: what it pushes is unbuilt until you run `oma-build`, and it says so in each commit.

## How a change reaches the phone
1. Edit and commit here. `oma-sync` commits and pushes to the shared branch (the Mac pulls from it).
2. `oma-build` runs the unit tests and builds; it must be green before anything else. Under a minute once Gradle is warm; about 2.5 minutes the first time.
3. `oma-dev --no-open` builds the same source as Oma Dev and installs it beside the real launcher (under a minute).
   Then `oma-dev --open` and look at it; `oma-phone home` to come back. Oma Dev has no Linux and no agent, and
   `oma-phone` talks only to the real launcher, so it shows that the build starts and how home, the looks, the app
   list, the overview, settings and the drawing tools look. It cannot show voice, follow-ups, the answer card, the
   phone-control or sticker commands, or Boss's own pages. It has test notes, stickers and a sample chat in it.
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
- Promotion: he does not want the real launcher updated after every small change. Bundle fixes and features, test in
  Oma Dev what it can show, and ask once when a batch is ready.

## When you finish a piece of work
Write what you did, what you saw, and what is next at the top of `docs/PHONE-TEST-RESULTS.md`, then `oma-sync`.
The Mac session reads that file.
