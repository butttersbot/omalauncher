# Fold development

Editable checkout: /root/Projects/omalauncher, phone/fold-development.
Use oma-build to run the app tests, assemble the APK and sync to GitHub.
Use oma-sync for unfinished source edits. The Mac mirror follows this branch at
/Users/jackkim/Projects/omalauncher-fold about once a minute when clean.

## Update the working launcher

Boss requested updates to the existing app, retaining Linux, login and settings.
The private Fold configuration now builds dev.omalauncher.boss with its matching
signing key and an advancing version code. Keys are not in source/GitHub.

- oma-install validates package/signature/version and schedules an update in
  10 seconds. An independent Android installer completes it and reopens Oma.
- oma-install --no-open updates without explicitly reopening.
- oma-install --status checks the pending update after reopening.
- oma-try builds, syncs, then schedules the working-launcher update.

Android restarts Oma during the update, stopping its current agent run. Install
as the last task step after syncing; do not uninstall or clear app data. Wireless
debugging must be enabled on Wi-Fi. No new Linux or agent login setup is required.
The earlier separate preview workflow is superseded.

## Verification

Actual Fold APK compilation including native CMake and 83 app tests previously
passed. Claude itself edited source docs, built, synced and installed the separate
preview. Working-launcher in-place update verified on 2026-10-11: actual Fold build passed
83 tests, matching certificate verified, Claude scheduled install, detached Android
installer returned INSTALL_SUCCESS, installed version advanced10→11, same app
remained default, Claude login remained active, source and phone control survived.
No new Linux or login setup was needed. Next build code12 is stored privately.
