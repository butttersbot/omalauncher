# Tests to finish on the Fold itself

For the agent running on the phone (Oma), while the Mac cannot reach the phone over adb.
Written 2026-10-12 by the Mac session. Build under test: 0.5.0-boss-preview.18 (versionCode 28).

## How to work
- This checkout is `/root/Projects/omalauncher`, branch `phone/fold-development`. Run `git pull --ff-only` first.
- Drive the phone with `oma-phone` (`oma-phone help`). Run `oma-phone status` first. It only works while the
  phone is unlocked and phone control is on. If the phone is locked, or status is not connected, do not try to
  get around it: write that down in the results file, sync, and stop.
- Read the screen (`oma-phone read`, `oma-phone screenshot`) before and after every action. Never claim a check
  passed because a command returned ok.
- Write results to `docs/PHONE-TEST-RESULTS.md`: one line per check, `PASS`, `FAIL` or `COULD NOT TEST`, with
  what you saw. Then run `oma-sync` so the Mac session can read them. Sync after each section, not only at the end.
- Do NOT run `oma-install` or `oma-try`: installing restarts the launcher and ends your own run. Do not build
  unless a step below says so.

## Rules: this is Boss's daily phone, and he may be driving
- Do not open banking, payment, messaging or email apps. Do not type into any app except Oma's own search field.
- Do not move, resize, rename or delete his tiles, pages, notes, stickers or pinned apps. You may add one page
  and delete that same empty page; add one sticky note and delete it.
- Do not change Android settings. Do not approve anything on his behalf.
- Leave the phone on home, in the look it was in, with no menu, overview, app list or drawing tools open.
- If anything asks Boss for a decision, stop that check and note it. Do not send him notifications to answer.

## Checks still open (only these; the rest were done from the Mac)
Do the ones the phone's state allows. `oma-phone status` and a screenshot tell you whether it is closed
(cover screen, about 1248x1972) or open (inner screen, about 2448x1848).

### If the phone is CLOSED (cover screen)
1. Home: one page, the page label reads like "Page 1  1/2", the dock is one row of app icons then "↑" and "✎".
2. Tap "↑": the app list opens with "ALL APPS" and "CLOSE ↓" at the top, four apps across, search at the bottom.
3. Tap the page label: the overview shows the pages on plain paper with handwritten names and a dashed "+".
4. Tap "✎": a centred row of six square buttons replaces the dock; the "×" closes it and the dock returns.
5. Long-press an empty spot: the menu's last row is the looks. Tap two other looks, check home redraws in each,
   then tap back the look it started in.
6. Widget text on Page 1: big numbers in a heavy display face, small captions in spaced capitals.

### In either state
7. `oma-phone pages` lists the pages; `oma-phone page "page two"` turns to the second page (new in this build);
   `oma-phone page "holiday"` answers that no page matches. Turn back to the page that was showing.
8. Open the app list, tap the gear, tap "look": nine looks as small pages, the one in use marked. Close it.
9. Look at the "oma" widget on Page 1 while you work: it should say what you are doing, not "ready".

### Not for you (needs Boss's hands)
Folding and unfolding, rotation, pinch gestures, restarting the launcher, the lock screen, approvals, and
whether things look right to him.

## Also useful if the phone is locked
- `oma-build :app:testDebugUnitTest` runs the unit tests on the phone's own toolchain and syncs. Report the
  totals and any failure in the results file. This does not install anything.
