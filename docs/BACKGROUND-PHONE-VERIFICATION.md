# Background phone prototype: hardware verification

## 2026-10-21 — Luna

**Result: PARTIAL.** The screenshot's plan was retrieved from the Fold at
`/root/handoff/phone-background-plan.md`. Its lock-screen and screen-off cases
must be distinguished. The prototype works on a separate display with a visible
secure lock screen; full interaction after the main panel sleeps is not achieved.

- Mac checkout: `/Users/jackkim/Projects/omalauncher-bgdisplay`, branch `codex/background-display`.
- Phone helper source: `/root/Projects/soma-bgdisplay`; canonical tracked copy: `tools/background-display/`.
- Installed during testing: Soma 117, signed on the phone with its existing key.
- Original Soma 114 APK backed up at `/data/local/tmp/soma-before-background-114.apk`
  and `/root/Projects/soma-bgdisplay/before.apk`. App data and logins preserved.
- Mac candidate 118 includes the clearer sleep limitation and foreground-window
  guard correction. Check handoff/current install before assuming it was installed.

## Verified

`:app:testDebugUnitTest :app:assembleDebug` passed with **1,710 tests, 0 failures,
0 errors, 6 skipped**. Build cache isolated in `/private/tmp/soma-bg-gradle` after
another daemon held the shared journal lock. The native Linux executable libraries
were compared byte-for-byte with the installed APK before phone-local signing.

The shell helper created independent displays 6–11; Settings and Calculator were
launched there without moving onto main. Authentication from Soma's real UID
passed using the loopback-only HMAC protocol. Unix sockets across shell/app UIDs
were denied in either direction, so they are not the production transport.

Real Linux CLI test (`oma-phone --display bg`) passed app launch, batch
`screen`/`expect`, accessibility `screen`/`read`, and native screenshot capture.
Phone evidence is under `/root/Projects/soma-bgdisplay/evidence/`.

A bounded visible-lock-screen test returned, before and after its background read:
`locked=true, awake=true`, with a readable hidden Calculator. Main-display reads
while locked were refused with `Phone is locked. Unlock it yourself.`

With `locked=true, awake=false`, hidden screenshots still succeeded, but app
controls could not be retrieved. Both Settings and Calculator reproduced this,
including after reopening Calculator on the hidden display. A shell UiAutomation
probe (with DONT_SUPPRESS_ACCESSIBILITY_SERVICES) also got null app roots. The main
screen remained protected; no fallback touches were issued to main.

## Not achieved / not verified

- Full app interaction after the real screen sleeps: **failed on this Fold**.
- Complete current scrcpy display flags also reproduced the failure; group ID
  remained 0 despite OWN_DISPLAY_GROUP. That is observed, not a proven root cause.
- `cmd virtualdevice help` says creation requires insecure keyguard. It was not
  invoked, and no lock or permission settings were weakened.
- Take over/Stop notification UI has not had a complete owner-driven hardware
  journey. Pause/locked/pending-approval policy paths have unit coverage.
- Final visible-lock-screen Calculator arithmetic test log must be checked in
  `/root/.cache/oma-keep/bg-verify-lockscreen.log`; do not infer its result from launch.

The helper is kept as `background-display` through the phone's own paired adb.
It must be restarted after a reboot; a launcher restart can also end the keeper.
Later builds must preserve this source branch or deliberately retire the prototype.
