# The Mac's Fold 8 emulator

Oma builds are tested on an Android emulator on the Mac Pro, not on Boss's phone. Oma Dev (`dev.omalauncher.dev`)
is retired as of 2026-10-17: Boss wants nothing extra running on his Fold. The real launcher on the phone
(`dev.omalauncher.boss`) is updated only when he says so (`oma-try` on the phone).

## What it is
- An Android Virtual Device named `Fold8`, Android 17 (API 37.0, Google APIs, arm64), shaped like his phone:
  inner screen 2448×1848, cover screen 1248×1972, both 420 dpi, a hinge, and 16 GB of RAM.
- Plain Android, no One UI. Samsung's own screens are not there; Boss is stripping most of them off the phone anyway.
- SDK: `/opt/homebrew/share/android-commandlinetools` (Homebrew). AVD files: `~/.android/avd/Fold8.avd`.
- It holds its own test data only: no logins, none of Boss's pages.
- **To make it again** (as on the MacBook Pro, 2026-10-26; values read off the Mac Pro's on 2026-10-22):
  `avdmanager create avd -n Fold8 -k 'system-images;android-37.0;google_apis;arm64-v8a' -d pixel_9_pro_fold`, then in
  `~/.android/avd/Fold8.avd/config.ini`: `hw.lcd.width=2448`, `hw.lcd.height=1848`, `hw.lcd.density=420`; cover
  `hw.displayRegion.0.1` 1248x1972 at 0,0; `hw.display1-3` unused; `hw.multi_display_window=no`; hinge
  `hw.sensor.hinge=yes`, areas `1224-0-0-1848`, defaults `180`, postures `1,2,3` at `0-30, 30-150, 150-180`,
  `fold_to_displayRegion.0.1_at_posture=1`, `resizable.config=1`; `showDeviceFrame=no`; `hw.initialOrientation=portrait`;
  `hw.keyboard=yes`; `hw.ramSize=16384`; `hw.gpu.enabled=yes`, `hw.gpu.mode=host` (the Mac's GPU: about 5x less CPU
  than SwiftShader, which stalled and crashed an emulator with three walking, 2026-10-26). Then `oma-emu start`, `fold-development/fold8-overlay/install.sh`, reboot,
  `oma-emu fold-bars` (cover status bar 110 px like his phone), install the app and make the Soma keyboard the input
  method: `ime enable`/`ime set dev.omalauncher.boss/dev.omalauncher.keyboard.OmaKeyboardService` and
  `settings put secure show_ime_with_hard_keyboard 1` (hw.keyboard=yes otherwise hides it); `oma-emu start`/`install` do both.
  Then, once, finish Soma's first-run (default theme, no Arch, start) so walks begin at home, and add Soma's Screen
  Record tile to Quick Settings (`cmd statusbar add-tile` with its TileService): a fresh AVD has neither, and the search,
  screen-record and chat walks fail on that (MacBook Pro, 2026-10-26).
- **One per lane** (2026-10-26): Fold8-B and Fold8-C are copies of Fold8 (same config.ini), on ports 5556 and 5558
  (`SOMA_EMU_LANE=B tools/oma-emu start`). soma-macbuild walks lane B/C there, each with its own lock, when they exist;
  otherwise every lane shares Fold8. Each needs the same one-time setup (first-run, tile, overlays, keyboard).

## Using it
`tools/oma-emu` wraps everything and aims adb at the emulator only (`emulator-5554`), never the phone:

    tools/oma-emu start              # boots in about 20 s; --headless for no window
    tools/oma-emu install            # app/build/outputs/apk/debug/app-debug.apk, made the home app
    tools/oma-emu unfold | fold      # inner screen / cover screen
    tools/oma-emu shot inner a.png   # or: shot cover b.png
    tools/oma-emu adb shell ...      # anything else
    tools/oma-emu stop

- Build first with `./gradlew :app:assembleDebug`. In a `.claude/worktrees/*` checkout copy
  `app/src/main/jniLibs/` from the main checkout before building, or Linux cannot start; `oma-emu install`
  refuses an APK without `libproot.so`.
- Screenshots need a display: a plain `screencap` with two displays returns text, not a picture. `oma-emu shot`
  picks the right one.
- The debug build's package is `dev.omalauncher.boss`, the same as the phone's, so everything addressed to the
  real launcher works here too.

## What it shows, and what it cannot
Seen working on 2026-10-17: first-run setup on both screens, folding and unfolding, home as a two-page spread,
Linux setup downloading and unpacking Arch (about 1 GB) and starting proot. Not yet watched: Linux setup to the
end (the terminal tile still said "installing…" 10 minutes in), voice input, the agents.

Cannot show: Samsung's own screens, the real phone's memory, battery and heat, touch feel, and Boss's own pages.
Say so when reporting, then ask Boss before promoting a batch to the real launcher.
