# Working on Oma from the phone alone

For the agent on the Fold (Oma), when Boss works with no Mac in the loop. Written 2026-10-12 by the Mac session.
Boss talks to you through Oma (voice or the Oma chat). Be short: he is often reading on the move.

## Where things stand
- This checkout: `/root/Projects/omalauncher`, branch `phone/fold-development`. `git pull --ff-only` first.
- Installed: the real launcher `dev.omalauncher.boss` and a throwaway copy `dev.omalauncher.dev` ("Oma Dev"), both
  build 29 (`0.5.0-boss-preview.19`). You are running inside the real one.
- The goal of the project: make the launcher match the agreed prototypes in `design/` one-to-one. Status and
  information surfaces are placeable widgets, not always-on chrome. No Oma button in the dock.
- Newest change, merged but on no device yet: sticky notes are placed clear of the page's tiles, and
  `oma-phone board` tells you what is where (`app/src/main/kotlin/dev/omalauncher/widgets/annotations/NotePlacement.kt`).
  The build you are running does not have it: your own `oma-phone note` still lands on widgets until it is promoted.

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
- `oma-phone click SNAPSHOT NODE` is unreliable on the launcher's own screens (it answers ok and does nothing): use
  `oma-phone tap X Y` from a screenshot. `oma-phone read` can lag one screen behind right after a tap: read again.
- House rules in the code are enforced by tests that fail on purpose (`PackSourceCheckTest`, `MotionSourceCheckTest`,
  tests that pin a look's values). Fix the code; change a pinned value only when the look was changed deliberately.
- Match the code around you: comments in plain sentences that say why, no TODOs, no dead code.
- Say plainly what you saw working and what only passed tests. If a step failed or was skipped, say so.

## Next, in order (ask Boss which, if he hasn't said)
1. Note placement: build it as Oma Dev and confirm it starts. Then ask Boss whether to promote it. After promotion
   (in a new request, since the update ends the run): `oma-phone board`, leave one note on Page 1, screenshot, and
   check it sits in the free area and covers no widget.
2. Phone-control bugs above (`click` on Compose nodes, stale `read`): `phone/PhoneService.kt`, `phone/PhoneBridge.kt`.
3. First-run setup still offers the old Omarchy themes and no look (`ui/Setup.kt`); you can see it in Oma Dev.
4. A unit test that Cancel on the listening screen sends nothing (`assist/OmaAssistant.kt`, `ListeningHost`).
5. The cover display has not been compared with the prototype since the dock changed: when the phone is closed,
   screenshot home, the app list, the overview and the drawing tools and compare with `design/oma-scrapbook-prototype.html`
   (never print lines 53-54 of that file whole: they are huge embedded images).

## Decisions that are Boss's, not yours
Auto-send after he stops talking, or a confirm step; the LINUX/ANDROID labels and the gear in the app list; note and
sticker buttons in the drawing tools row; text sizes on the prototype's scale; whether setup should offer looks.

## When you finish a piece of work
Write what you did, what you saw, and what is next at the top of `docs/PHONE-TEST-RESULTS.md`, then `oma-sync`.
The Mac session reads that file.
