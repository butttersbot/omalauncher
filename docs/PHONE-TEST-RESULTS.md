# Phone test results

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
