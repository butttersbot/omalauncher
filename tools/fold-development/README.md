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
  Switched off, it is left off. The off-and-on has not yet been seen mending a stuck update on the Fold; Boss
  doing the same by hand in settings did (2026-10-12).
- oma-try builds, syncs and schedules the update.
- oma-door is a small private door for the Mac session to reach the agent here when adb can't: it listens on the
  phone's Tailscale address only (port 8477), needs the token in /root/.config/oma-door/token with every request,
  and hands a prompt to the same agent the launcher runs, one job at a time. It runs no command of its own. The
  launcher starts it with Linux when both the token and /usr/local/bin/oma-door are there. To shut it for good,
  delete the token.
- A debug build takes a guest command from an intent (`--es exec`) only while the file `allow-debug-exec` is in
  the app's own files directory (`adb shell run-as <package> touch files/allow-debug-exec`).
- oma-dev builds this checkout as a second, throwaway launcher, "Oma Dev" (dev.omalauncher.dev), and installs it
  beside the working one: its own data, no Linux, an empty notebook. Break it freely; the working launcher and its
  agent are untouched. `oma-dev --status`, `--open`, `--no-open`, `--remove`. Test a change there first, then
  oma-try the same commit onto the working launcher. Install it once with
  `install -m755 tools/fold-development/oma-dev /usr/local/bin/oma-dev`; it reads tools/fold-development/dev.init.gradle.

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
