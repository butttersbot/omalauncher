# Fold development

This is the editable Oma Launcher checkout on the Fold:
`/root/Projects/omalauncher`, branch `phone/fold-development`. It is separate
from the Mac checkout. Check `git status` before editing and preserve any local
changes; do not reset to the public branch.

## Building

Use `oma-build` for both unit tests and APK assembly. It wraps the build with
the phone's private SDK and ARM64 NDK.

## Preview APK

- Output: `app/build/outputs/apk/debug/app-debug.apk`
- Application ID: `dev.omalauncher.boss.folddev`

The working launcher and the preview are different Android packages.
`oma-build` builds the debug APK under the phone-local application ID
`dev.omalauncher.boss.folddev`, so Android treats it as its own app with its own
data, and installing it sits alongside the working launcher instead of
upgrading or overwriting it. It is signed with the debug key Android generates
on this phone; no keys or credentials are copied in or exported.

Installing the preview and choosing it as home are separate steps; do not
replace the working launcher unless asked.

## Installing on the phone

- `oma-install` installs the built APK as the Oma Fold Dev preview and opens it.
- `oma-install --no-open` installs it and leaves the current screen alone.
- `oma-try` builds, then syncs the source to GitHub, then installs and opens
  the preview.

These only target `dev.omalauncher.boss.folddev`. They install over wireless
debugging, which must stay enabled while the phone is on Wi-Fi.

The preview keeps its own app data, separate from the working launcher. Agent
logins are not transferred automatically, so the preview may need its own
optional Linux/agent setup. None of these commands change the default home app.

## Source synchronization

- GitHub branch: `phone/fold-development`
- Mac mirror: `/Users/jackkim/Projects/omalauncher-fold`

After a successful build, `oma-build` automatically commits the source edits
and pushes them to the GitHub branch `phone/fold-development`. Use `oma-sync`
to save unfinished edits the same way without building; describe untested work
as such. If the push fails because the phone is offline, the commits stay local
and the next `oma-sync` retries it.

The Mac mirror fast-forwards to the branch about once a minute and preserves
any local edits made there.

## Status

Verified on the Fold on 2026-10-11 (Boss client date): `oma-build` on `phone/fold-development`
(at `39b5961`) finished with `BUILD SUCCESSFUL`.

- Native code: `configureCMakeDebug[arm64-v8a]` and `buildCMakeDebug[arm64-v8a]`
  ran with the ARM64 NDK and produced `libwlbridge.so`, packaged next to the
  prebuilt `arm64-v8a` libraries.
- Tests: `testDebugUnitTest` passed, 83 app tests across 17 suites with no
  failures, errors or skips.
- APK: `app/build/outputs/apk/debug/app-debug.apk` (about 30 MB), application ID
  `dev.omalauncher.boss.folddev` per `output-metadata.json`.

On 2026-10-11 (Boss client date), `oma-install --no-open` updated the existing Oma Fold Dev
preview from that phone-built APK without a rebuild. The preview has not been
set as home, and nothing has been published.
