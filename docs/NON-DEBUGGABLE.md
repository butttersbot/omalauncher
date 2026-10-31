# The working launcher isn't debuggable

Boss, 2026-10-30: build it, then trial it. Same code, on the emulator: Soma chat opens cold in about 280 ms instead of
about 1150 ms, and home's page swipes went from 13-24% janky frames to 2-10%. A compiled profile on a debuggable build
only reached about 950 ms. There is no R8: it adds little, and shrinking is its own risk.

`-PsomaDebuggable` gives a debuggable build on an emulator, for a debugger, run-as, or `tools/background-display/Probe`.

## What changed for tools

- **No `run-as` on the phone.** Android answers `package not debuggable`.
- **`oma-mac` works as before,** through `ShellDoor`, not a key in `run-as`. `BuildConfig.DEBUG` is false here;
  `BuildConfig.WORKING_BUILD` is what says "the working launcher" (`--es theme`, owl-check's `.themes` previews).
- **The old door is gone:** the receiver and intent that took a guest command from any app with the key.
- **Agents working inside his Linux are unaffected:** `oma`, `oma-phone`, `tmsg`, the phone bridge and the keeper use no
  `run-as`. Only tools coming in over adb were touched.
- **Walks:** emulators are `adb root`, which still reads app files. A walk on the preview app still has `run-as`: that
  stays debuggable.

## ShellDoor: exactly what it does

A content provider, `content://dev.omalauncher.boss.door`, in the working build only (disabled in the public build's
manifest, and refusing there anyway). Every call first checks the caller is Android's shell user (uid 2000): not root, not another app, not the app
itself, so not its Linux. It is off until opened.

| Ask | Needs the door open | Does |
|---|---|---|
| `open`, `close` | no | The switch. Closing empties the folder below. |
| `status` | no | Says open or closed. |
| `phone-control-wanted` | no | Whether he wants phone control on: one yes or no, for oma-install. |
| `run ID` | yes | Runs `/root/.cache/oma-mac/ID.sh` in his Linux; ID is 16 hex digits. |
| `wait ID` | yes | Waits up to 5 s for `ID.code` in that folder, and says what it holds. |
| read, write, delete `guest/NAME` | yes | One plainly named file in `/root/.cache/oma-mac`, and no other: a link is refused, and so is the folder if it is one. |

It reads no setting, login, chat or file outside that folder. A script it runs is as powerful as any guest command,
and so can reach what his Linux reaches: this is what the old door could do, and what `oma-mac` is for. So keep it closed
when not working. adb is the proof of who's asking, as it was for `run-as`: someone with adb on an unlocked phone could
already install apps.

## Going back

**An older version can't be installed over a non-debuggable app.** Android refuses it whole: nothing is half installed
and the launcher stays as it was, through adb (`INSTALL_FAILED_VERSION_DOWNGRADE`) and through Oma itself ("not newer").
oma-install says so. To go back, build the old commit with a higher version code. A kept old APK can't go back on.

The signing key never leaves the phone and lives inside his Linux, and `run-as` no longer reaches it. If a build is so
bad the launcher can't start Linux, nothing can sign a fix. So:

### Before the first non-debuggable install

The trial build has version code N. Phone A does these, on the phone, in this order:

1. Have the commit he is on now built with code N+1 (it predates this, so it's debuggable and has `run-as`). This is the
   rescue. `oma-macbuild --take` it first, because `--take` holds one build at a time and the next one replaces it.
2. Don't install it. Copy the signed `app/build/outputs/apk/debug/app-debug.apk` to `/sdcard/Download/soma-rescue-N+1.apk`,
   and to the Mac and the Pocket. Check `apksigner verify --print-certs` shows the working certificate.
3. Then `--take` the trial N, and `oma-install`. That leaves `next-version-code` at N+1.
4. **Set `/root/.config/oma-development/next-version-code` to N+2.** Nothing else keeps a routine build from taking the
   rescue's number, and once installed, a build at N+1 kills the rescue silently.
5. A rescue only works while its code is above what's installed. Once N+2 goes on, it's spent: make a new one above that
   while the trial still needs one.

### If the trial is bad

- **Soma works but something's wrong:** copy the rescue over `app/build/outputs/apk/debug/app-debug.apk`
  and `oma-install`, or `adb install -r soma-rescue-N+1.apk` from the Mac over Wireless debugging, or tap the APK in Files.
- **Soma won't start:** Phone A is down with it, since it runs in Soma's Linux. `adb install -r soma-rescue-N+1.apk` from the Mac, or
  Boss taps the file in Samsung's Files app. Linux, logins and settings stay: tested on the emulator from non-debuggable to
  debuggable and forward again, data intact each way.
- **After a rescue put on by hand** nothing has moved `next-version-code`: set it above what's installed (N+2), or the next
  build is refused as old.
- **On the rescue, `oma-mac` needs the old version:** `git show RESCUE-COMMIT:tools/fold-development/oma-mac`. It says so if you
  forget. Install heal has `run-as` as its fallback there.
- **Never uninstall, clear data or use `-d`:** uninstalling loses his Linux and logins.

## What only the real phone can prove

How much faster it really is at 120 Hz, memory and heat, Samsung's installer dialog, and his logged-in agents and phone
control after the update. The emulator proves install in place, Linux, the keeper, the bridge and the door.
