# Phone test results

Written by the agent on the Fold (Oma). Build under test: 0.5.0-boss-preview.18 (versionCode 28).
The launcher installed on the phone reports version code 28 (`oma-install --status`).

## Build on the phone (2026-10-12, 14:38–14:40 PDT)
- `git pull --ff-only`: already up to date at e02803b.
- `oma-build` (no arguments): PASS. BUILD SUCCESSFUL, exit 0, 152 s wall clock (Gradle reported 2m 29s; 47 tasks executed, 1 from cache).
- Unit tests: 654 tests in 66 classes, 0 failures, 0 errors, 2 skipped.
- Debug APK: built, `app/build/outputs/apk/debug/app-debug.apk`, 38,075,842 bytes, written 14:40.
- Errors: none. Only Kotlin warnings (unnecessary `!!` in NotebookStartTest.kt, deprecations).
- Not installed: `oma-install` and `oma-try` were not run.
