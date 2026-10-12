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

The APK has only been built. It has not been installed or set as home, and
nothing has been published.
