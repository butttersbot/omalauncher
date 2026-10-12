# Phone test results

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
