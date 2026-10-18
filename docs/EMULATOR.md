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
