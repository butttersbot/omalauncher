# Soma 122 verification: helper restored; owner steps pending

2026-10-21 UTC. Package manager confirms versionCode=122.

1. **PASS: helper restoration.** Started with
   `oma keep run background-display -- bash /root/wt/builder-b/tools/background-display/start.sh 127.0.0.1:38937`.
   Keeper log `/root/.cache/oma-keep/background-display.log` reports `DISPLAY 14`.
   `oma-phone --display bg status` reports connected=true, active=true,
   backgroundAvailable=true, backgroundControlsAvailable=true, locked=false,
   awake=true. The helper is left running.
2. **PARTIAL: sleep investigation.** Fresh awake dumps show display 14 ON,
   with OWN_DISPLAY_GROUP and ALWAYS_UNLOCKED flags but displayGroupId=0;
   there is only one display group. This repeats the earlier observation,
   but does not prove why accessibility roots disappear during sleep.
   Source still explicitly rejects background root reads while PowerManager
   is noninteractive, and status derives controls availability from that global
   state. Removing these guards would not establish a fix: earlier independent
   UiAutomation probes also returned null roots. No source behavior changed.
   A fresh locked/asleep test awaits the owner locking the phone and allowing
   the screen to sleep. Impossibility on this Fold remains unproved.
3. **FAIL: news UI pass not started.** Main display currently has Soma's
   OmaChatActivity resumed. Soma News belongs to that same package, so it was
   not launched on the hidden display while the owner uses Soma on main.
   Owner must switch main to another app before the side-by-side test.
   No news UI defects or screenshot evidence are claimed.

Raw awake evidence: `/root/handoff/background-122-evidence/` contains
`status-awake.json`, `display-awake.txt`, `power-awake.txt`, `window-awake.txt`.
No installation, security/permission changes, wake locks, insecure virtual
devices, or main-display control steps were performed.

Resume with a hidden-display test app, then ask the owner to lock/sleep for
fresh paired root/screenshot and dumpsys evidence. After the owner wakes the
phone and switches main away from Soma, run the news UI pass. Page navigation
and global back are currently refused for background; use reader controls and
report any widget-entry coverage that cannot be exercised in isolation.

## Timed owner-assisted sleep test

After the requested 30-second delay, status confirmed locked=true, awake=false.
The previous keeper was no longer registered and port 18746 refused connections;
the cause of its disappearance is unknown. ADB reconnected at the same port and
the helper was restarted under `oma keep`, creating display 15 while main slept.
Status then reported backgroundAvailable=true, backgroundControlsAvailable=false.

**FAIL: asleep controls.** Background Calculator launch reported inFront=false;
`screen` returned Soma's explicit sleep refusal. More significantly, independent
shell UiAutomation (the existing `/data/local/tmp/soma-a11y-probe.dex`, with
DONT_SUPPRESS_ACCESSIBILITY_SERVICES) returned only a System UI window on display
0, and no display-15 app window. An initial attempt incorrectly used the helper
DEX for that probe and aborted; only the separate probe DEX result is evidence.

`dumpsys activity/window` shows display 15 `mSleeping=true` with a `Display-off`
sleep token, and Calculator `isSleeping=true`, `state=STOPPED`,
visibleRequested=false. Thus framework sleep suppresses the app/window independently
of Soma's guard; merely removing the guard is not a demonstrated fix. The hidden
display still belongs to group 0. A universal impossibility claim or exact reason
for the ignored group request has not been established.

**PARTIAL: screenshot.** Capture succeeded at
`/root/.local/share/omalauncher/attachments/phone-screen-fa1eaaee-e6c5-47a2-9ef5-8e426c9cb2bb.png`,
but visual inspection shows a black image, not Calculator. This display was
created while already asleep; this does not establish what happens to retained
frames when an existing awake display sleeps.

Raw paired sleep evidence is in the same evidence directory: `status-asleep.json`,
`screen-asleep.json`, `a11y-asleep.txt`, `activity-asleep.txt`, `window-asleep.txt`,
`display-asleep.txt`, `power-asleep.txt`. Main remained locked/asleep throughout;
no wake/unlock/security changes were performed. Helper display 15 was left running.
News UI coverage remains pending an awake phone with the owner using another app.
