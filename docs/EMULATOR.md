# The Mac's Fold 8 emulator

Oma builds are tested on Android emulators on the Mac Pro, not on Boss's phone. Oma Dev (`dev.omalauncher.dev`)
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
  Also install Arch Linux from Soma's setup (about 1 GB): the phone bridge (`oma-phone`, plans) only starts with
  Linux, and phone-speed's plan half needs it. Each walk also needs `adb root` for screens that aren't exported (news).
- **One per lane** (2026-10-26): Fold8-B and Fold8-C are copies of Fold8 (same config.ini), on ports 5556 and 5558
  (`SOMA_EMU_LANE=B tools/oma-emu start`). soma-macbuild walks lane B/C there, each with its own lock, when they exist;
  otherwise every lane shares Fold8. Each needs the same one-time setup (first-run, tile, overlays, keyboard).

## The pool: an emulator per walk (2026-10-30)
A walk with no emulator named gets its own (tools/walkthrough/README.md, "One emulator per walk"; code:
`tools/walkthrough/emu_pool.py`). Fold8, Fold8-B, Fold8-C and any other AVD stay as they are, for whoever names them.

- **Base image**, one per Mac: AVD `Fold8-Golden` (7.8 GB), Fold8's config.ini, first-run done, overlays, Soma keyboard, Screen
  Record tile, Arch Linux, Soma Notes with the walks' notes, News with a stream already fetched. Nothing runs it but this
  setup; it isn't Git-tracked data, so each Mac makes its own:

      tools/oma-emu pool golden --apk APP-DEBUG.apk      # about 15 minutes, most of it Arch
      tools/oma-emu pool golden-add --apk NOTES-DEBUG.apk --seed dev.omalauncher.notes:$PWD/tools/walkthrough/fixtures/soma-notes.tar
      tools/oma-emu pool golden-add --open dev.omalauncher.boss/dev.omalauncher.news.NewsActivity:180

  Its Soma needn't be current: each walk installs its own build over it. `--seed` and `--apk` add more to it later.
- **Slots:** six, AVDs `Fold8-Pool-1..6`, ports 5574-5584 (5572 is the base image's). Each exists only while claimed.
- **Clean:** a claim makes an APFS clone of the base (instant, and only what the walk writes takes disk), cold boots it, and
  deletes it at release; after a crash a watcher does that within seconds, or the next claim of the slot does. A file, a
  setting and app data left by one claim were checked to be gone at the next. It costs **15-25 s** per walk (29 s at worst with
  six booting at once); the install adds 1-5 s.
- **No netsimd:** pool emulators run without the simulated radios (`-feature -WiFiPacketStream,...`). That one daemon is
  shared by every emulator on a Mac, and on 2026-10-30, 18:20:55, with 11 up, it reset all its clients ("Buffers full") and
  they all crashed, including emulators of other threads. Wi-Fi still shows connected, ping and News work; Bluetooth and UWB
  simulation are gone from the pool, and no walk uses them. Named AVDs are still on it.

**How many** (Mac Pro, M2 Ultra, 24 cores, 192 GB; each run also had 2 other threads' emulators up, walking; `agents-page`, 28
steps, all at the same moment):

| walks together | emulators up | boot, s | walk, s | fails | CPU idle, average |
|---|---|---|---|---|---|
| 1 | 3 | 18 | 90 | 0 | 60% |
| 3 | 5 | 21-24 | 89-90 | 0 | 56% |
| 4 | 6 | 21-24 | 90-95 | 0 | 49% |
| 6 | 8 | 22-29 | 95-98 | 0 | 35% |
| 9, on netsimd | 11 | 27-35 | - | crashed | - |

So the Mac Pro's limit is **8 emulators up, anyone's**: about 8% slower than alone, with 8 of 24 cores left for builds and agents.
Over 8 was not tried again without netsimd, so the machine may take more. A claim also waits with
under 20 GB of memory or 15 GB of disk free. The Mac Pro had 98 GB of its 926 GB free on 2026-10-30.
The MacBook Pro (M5 Max, 18 cores, 128 GB, 1.2 TB free) is set to 4, by proportion: not measured under load.

**The MacBook Pro as overflow.** When the Mac Pro is full, walk.py asks it (and again each minute while waiting). All of a walk
can run there: walk.py, the script, the builds and the fixtures are sent, the output comes back. What can't:
- It takes nothing while **on battery** (`SOMA_EMU_OVERFLOW_BATTERY=1` overrides), or asleep, or full: the walk waits on the Mac Pro.
- `oma-emu claim` stays on the Mac it ran on: its later adb steps need that Mac.
- With the **Mac Pro down**, overflow can't help a walk started on it. A thread on the MacBook walks on the MacBook's own pool
  and overflows the other way; phone builds wait for the Mac Pro, since soma-macbuild runs there.

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
