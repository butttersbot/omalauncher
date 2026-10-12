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
- oma-try builds, syncs and schedules the update.

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
