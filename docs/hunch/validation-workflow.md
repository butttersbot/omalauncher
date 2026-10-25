# Hunch validation cadence

Boss changed the workflow on2026-10-25: walkthroughs are batched at the end of
each slice and run/reviewed by the critic. Do not stop for an emulator walk on
every small feature, wording adjustment, or minor bug fix.

During implementation, use proportionate compile/static/unit checks; accumulate
walk steps covering the slice and reported bugs. At the slice checkpoint, give
the critic the build, changed screens, accumulated walk coverage, and known
issues. Critic reports actual PASS/FAIL, reviews screenshots and records any
real-phone checks not performed. Deferred checks are not claimed passed.

For the0.5.1 branding-only patch, build16/16 unit tests, asset/source/hash checks
and reviewed screenshots are sufficient under the new cadence. Extra queued
copy/rotation walkthroughs no longer gate that patch. Keep the recurring wallet
portrait-rotation accessibility failure for the next slice-end critic review;
it has not been declared fixed. No wallet/funding/order behavior changed.

This supersedes the older per-change walkthrough gate in earlier Hunch records.
Keep actual order tests with Boss, as already requested.
