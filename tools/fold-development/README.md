# On-phone development helpers

Checkpoint of the helpers verified on Boss's Fold. The actual source checkout is
/root/Projects/omalauncher, branch phone/fold-development. These are development
scripts, not code bundled into the Android app.

## Commands
- oma-build tests/builds the working .boss APK, then commits/pushes source via oma-sync.
- oma-sync saves source edits to the phone branch; never force-pushes.
- oma-install verifies package/signature/version and schedules a detached Android
  shell job to update/reopen the working launcher in 10 seconds.
- oma-install --status checks the installed version and detached job result.
- After a successful install the detached job waits for Android to bind phone control again. If it is still
  switched on in Accessibility and not bound after 20 seconds (an update can leave it so), the job turns it off
  and on, as Android's shell, and logs PHONE_CONTROL_HEALED or _STILL_DISCONNECTED; `--status` says which.
  Switched off, it is left off, and logs PHONE_CONTROL_OFF; switched off while the launcher's own record says
  Boss wants it on, it is left to the launcher, which puts it back at start (PHONE_CONTROL_LEFT_TO_LAUNCHER). The off-and-on has not yet been seen mending a stuck update on the Fold; Boss
  doing the same by hand in settings did (2026-10-12). The job's off-and-on was seen unbinding and rebinding a
  working service that day (18:47), when its first check misread two bound services as none.
- oma-try builds, syncs and schedules the update.
- oma-herd is the Fold's herd reporter (port 8479, like `herd node` on the computers): the phone and its builders, read
  from ~/.cache/oma-agents/status.json, answered only to the addresses tailshare-serve lists as Boss's devices. Run it
  with `oma keep run oma-herd -- /usr/local/bin/oma-herd`; the herd's gather() has to ask Android hosts for it to show.
- oma-macbuild builds on the Mac Pro instead (Boss, 2026-10-17: heavy work off the phone): it pushes HEAD and
  asks the Mac Pro's "Oma runner upkeep" thread to run soma-macbuild COMMIT VERSION, which builds and tests that
  commit in ~/Projects/omalauncher-fold-build with mac.init.gradle (no key) and Taildrops the app back.
  `oma-macbuild --take` checks it (HEAD's commit, the next version code, no test failed, its checksum, and proot
  libraries byte for byte the same as this checkout's), signs it with the phone's key, and leaves it where
  oma-install looks. Then oma-install as before.
  Build lanes (2026-10-24): each builder (A, B, C) asks a runner thread of its own, "Soma build lane A/B/C" (its id
  kept in /root/.config/oma-development/mac-lane-A etc.; found by name or made by `oma threads new` the first time),
  to run soma-macbuild --lane X, which builds in ~/Projects/omalauncher-fold-build-a/-b/-c, so the three build at
  once. The Mac gives each build its version code (tools/fold-development/soma-lanes, ~/.local/state/soma-macbuild):
  the phone's next one, or the first above it no other lane's build has, so two lanes never share a code; --take
  accepts any code from the next one up. Lanes wait for each other only to fetch (seconds) and to walk on the Mac's
  one emulator (after the app is sent). On the phone, --take never replaces another builder's newer build waiting for
  oma-install (taken.json), and only one oma-install runs at a time. The file mac-lanes-off (or SOMA_MAC_THREAD) sends
  every build to the upkeep thread as before; soma-macbuild without --lane builds exactly as before.
- oma-adb says in one line whether Wireless debugging is on and at what address (it takes a new port each time it
  is switched on, so a cached port proves nothing): the last good address, then adb's own list, mDNS, and every port
  at once; only a port that answers adb's opening and then runs `adb -s ADDR shell true` counts, and adb's other
  entries for the phone are disconnected. Exit 0 on, 1 off, 3 phone unreachable, 4 not authorized. On the Mac it
  looks at galaxy-z-fold8 over Tailscale (~1 s when the port is unchanged, 5-7 s when it has to look); here it checks
  the phone's own adb over loopback and writes the port to install-port for oma-install and oma-drop. Ask it rather
  than trusting install-port.
- oma-door is a small private door for the Mac session to reach the agent here when adb can't: it listens on the
  phone's Tailscale address only (port 8477), needs the token in /root/.config/oma-door/token with every request,
  and hands a prompt to the same agent the launcher runs, one job at a time. It runs no command of its own. The
  launcher starts it with Linux when both the token and /usr/local/bin/oma-door are there. To shut it for good,
  delete the token.
- The working launcher isn't debuggable (docs/NON-DEBUGGABLE.md). `oma-mac` (run on the Mac, over adb) reaches Linux
  through its shell door, a content provider that answers only Android's shell user: `oma-mac open`, `oma-mac phone
  board`, `oma-mac run CMD`, `oma-mac close`. Leave it closed when not working.
- oma-dev (retired 2026-10-17): it built a second launcher, "Oma Dev" (dev.omalauncher.dev), on the phone. Boss wants
  nothing extra running on his phone, so do not build or install it; only `oma-dev --remove` is still for use. Test
  changes on the Mac's Fold 8 emulator instead (docs/EMULATOR.md), then oma-try onto the working launcher when
  Boss says so.

Updating the app stops its current agent run. Install LAST; do not uninstall or
clear data. Persistent Linux, logins and settings survive. Android signing key must
match the installed .boss app; a newly generated phone debug key will not update it.

## Private runtime configuration
Install helper scripts in /usr/local/bin and make executable. Copy the template to
/root/.config/oma-development/fold.init.gradle. Restore these separately:
- device.json containing the actual device serial (from the authorized Android
  debugging connection), used to prevent installing on another phone.
- working-cert.sha256 containing the installed app's public certificate digest.
- working-debug.keystore containing the matching signing key, mode600.
- next-version-code containing an integer above the installed app's version.
- install-port caches the current paired loopback TLS port; fallback discovery is
  implemented but has not been exercised after a port change.
- GitHub repo-specific deploy key and pinned host key; local native ADB pairing.

Do not commit runtime configuration, keys, credentials or Linux data. The default
Android debug-key password in the template is public standard tooling data; no
actual key is included. Keys stay private on the device. Git ignores/excludes and
staged credential-pattern guards are in addition to careful review, not a guarantee
that arbitrary secrets can never enter a source commit.

The verified toolchain is Java27+17, Gradle9.8, API37/build-tools36, ARM64 NDK29,
CMake/Ninja and ARM64 resource tools. See docs/FOLD-DEVELOPMENT.md for verification.
SDK/tools and native proot libraries are installed separately, not in this folder.

## Mac mirror
mac-sync.py runs under the user's minute LaunchAgent and fast-forwards only a clean
fold-sync worktree at ~/Projects/omalauncher-fold, tracking fork/phone/fold-development.
It preserves the separate active Mac development branch. Local edits/divergence
pause updates. It writes status under ~/agents/luna/phone-source-sync.
