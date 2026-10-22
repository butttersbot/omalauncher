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
