# Working on Oma from the phone alone

For the agent on the Fold (Oma), when Boss works with no Mac in the loop. Written 2026-10-12 by the Mac session.
Boss talks to you through Oma (voice or the Oma chat). Be short: he is often reading on the move.

## Where things stand (checkpoint 2026-10-12, about 18:00 PDT, written by the agent on the Fold)
- This checkout: `/root/Projects/omalauncher`, branch `phone/fold-development`. `git pull --ff-only` first.
- Installed: the real launcher `dev.omalauncher.boss` and the throwaway `dev.omalauncher.dev` ("Oma Dev": every entry says Dev,
  magenta icon). You are running inside the real one. Oma Dev has test notes, a sample chat and test stickers in it.
- The goal of the project: make the launcher match the agreed prototypes in `design/` one-to-one. Status and
  information surfaces are placeable widgets, not always-on chrome. No Oma button in the dock.
- On the real launcher since 17:06 (bundle 1), seen working there: notes placed clear of tiles and `oma-phone board`; notes
  and stickers carried across pages; note, sticker and text keys in the drawing tools (nine keys); the status bar follows the
  look; `oma-phone click`, `read` after an action, `page` turning the cover screen, swipes up to 5 s.
  On it but NOT yet tried by anyone: the voice confirm step ("Send this?" with Send, Add more, Cancel) and follow-ups sent
  while a task runs (they queue behind it). Boss is to try these.
- Being promoted as this checkpoint is written (bundle 2; the result is not known here, check `oma-install --status`):
  Markdown in the Oma chat (oma/Markdown.kt, MarkdownText.kt), picture stickers (widgets/annotations/StickerPictures.kt,
  StickerBitmaps.kt), and the chat scrolling to a message just sent. All three were seen only in Oma Dev or not at all;
  docs/PHONE-TEST-RESULTS.md says which.
- Stickers: 79 pictures on the phone in the guest's `~/.config/omalauncher/stickers/` (cove 12, pepe-blanket 20, donut-frog
  26, rabbit-shark 21), copied from ClaudeSync `projects/pond` (data/art-bank and public/stickers). They are on the phone
  only, not in Git. The folder is inside the Linux root: reinstalling Linux empties it; stickers already placed keep their
  own copies in filesDir/annotations/stickers.
- Changed on the phone for Boss, to put back when he says: screen timeout is set to never (`settings put system
  screen_off_timeout 2147483647`; it was 30000).
- Tailscale on the phone does not come back by itself after a restart; opening the Tailscale app reconnects it. Without it
  `oma-context` times out, which looks like the Mac being down.
- `oma-dev` moves the built APK away, so `oma-install --status` says "No working-launcher APK found" until the next `oma-build`.

## How a change reaches the phone
1. Edit and commit here. `oma-sync` commits and pushes to the shared branch (the Mac pulls from it).
2. `oma-build` runs the unit tests and builds; it must be green before anything else. About 2.5 minutes.
3. `oma-dev --no-open` builds the same source as Oma Dev and installs it beside the real launcher (under a minute).
   Then `oma-dev --open`, look at it with `oma-phone screenshot` / `read`, tap with `oma-phone tap X Y`, and
   `oma-phone home` to come back. Oma Dev has no Linux, no agent and an empty notebook: it shows that the build
   starts and how home, the looks, the app list, the overview, settings and the drawing tools look. It cannot show
   voice, the answer card, or Boss's own pages.
4. Promote to the real launcher ONLY when Boss says so in that conversation: `oma-try` (it builds, syncs and
   schedules the update). The update restarts the launcher and ENDS YOUR OWN RUN, so make it the last thing you do,
   and tell Boss first what to check afterwards. Never uninstall or clear the real launcher.

## Rules
- This is Boss's daily phone. Do not open banking, payment, messaging or email apps, do not type into other apps, do
  not change Android settings, do not approve anything on his behalf.
- Do not move, resize, rename or delete his tiles, pages, notes or pinned apps unless he asks for exactly that.
- `oma-phone click SNAPSHOT NODE` and a `read` straight after an action work on the launcher's own screens since the
  2026-10-12 bundle. System dialogs still ignore `tap`; use `oma-phone back` there.
- House rules in the code are enforced by tests that fail on purpose (`PackSourceCheckTest`, `MotionSourceCheckTest`,
  tests that pin a look's values). Fix the code; change a pinned value only when the look was changed deliberately.
- Match the code around you: comments in plain sentences that say why, no TODOs, no dead code.
- Say plainly what you saw working and what only passed tests. If a step failed or was skipped, say so.

## Next, in order (ask Boss which, if he hasn't said)
1. Check bundle 2 on the real launcher: a Markdown answer in the chat (tap a link; a long answer; the inner screen), a
   sent message scrolling into view above the keyboard, a picture sticker from each pack placed, resized, turned, carried to
   another page, and listed by `oma-phone board`.
2. After each promotion, look at `oma-phone status`: after bundle 1 the phone-control service stayed disconnected and was
   listed under "Crashed services" until the phone restarted. Not yet known whether the new service crashes on its first bind.
3. Have Boss try the voice confirm step and a follow-up while a task runs, and fix what he finds. A follow-up waits for the
   task to end; one that interrupts a running task would be a deeper change (see the report in PHONE-TEST-RESULTS.md).
4. Move the sticker library out of the Linux root so it survives a reinstall (`linux/Proot.kt`, `LauncherData.kt`, a bind
   like the widgets folder). Cove's papers, images and backgrounds (17 in the art bank) have no place here yet.
5. Small: the sticker picker's dim does not reach under the system bars when opened from the drawing tools; MarkdownText.kt
   is not in PackSourceCheckTest's converted list; a carried note leaves a shrinking ghost on the page it left.
6. Compare the cover display with `design/oma-scrapbook-prototype.html` side by side (never print lines 53-54 of that file
   whole: they are huge embedded images). The cover-screen checks pass, but that comparison has not been done.
7. Later, parked by Boss: first-run setup offering looks instead of Omarchy themes (`ui/Setup.kt`); text sizes on the
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
- Promotion: he does not want the real launcher updated after every small change. Bundle fixes and features, test them in
  Oma Dev, and ask once when a batch is ready.

## When you finish a piece of work
Write what you did, what you saw, and what is next at the top of `docs/PHONE-TEST-RESULTS.md`, then `oma-sync`.
The Mac session reads that file.
