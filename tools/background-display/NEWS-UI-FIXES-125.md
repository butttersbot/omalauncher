# News 125: native bars, full-height pages and gesture back

Application commit `370e9e182b57b146bae31ea0c165d3b6af9be978`, 2026-10-22.

- **Implemented: top-strip overlap.** NewsActivity now sizes a FrameLayout host
  inside the real system-bar and display-cutout insets. Its WebView fills that
  safe viewport, rather than relying on CSS safe-area values. Insets update on
  rotation/window changes, and include Android's caption bar.
- **Implemented: remove the bottom book strip.** Removed the scrubber, time-left
  row and footer layout space. The pages extend to the bottom margin; swipes,
  edge taps and compact previous/next buttons in the top bar still turn pages.
  Current page numbers remain printed on the pages.
- **Implemented: gesture back.** NewsActivity now extends ComponentActivity and
  registers a lifecycle-aware OnBackPressedCallback. Both modern gestures and
  older back buttons call the same JavaScript routing: an article returns true
  after returning to the news stream, so the Activity stays open; on the stream
  it returns false and the Activity exits. A pending guard prevents two
  overlapping JavaScript back requests from skipping both levels at once.

The native changes follow the Android guidance for
[system-bar/caption insets](https://developer.android.com/develop/ui/views/layout/edge-to-edge)
and [back gesture callbacks](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture).

## Verification and artifact

- **PASS:** 26 browser checks, including no footer/progress bar, page height,
  forward/backward turns, all Aa sizes, and article-versus-stream back results.
- **PASS:** Mac build and 1,726 app tests, zero failures, six skipped.
- **PASS:** candidate received, library bytes compared and signed with the
  phone's existing key. Staged version 125:
  `/root/Projects/omalauncher/app/build/outputs/apk/debug/app-debug.apk`.
- **PASS:** browser preview inspected at
  `/root/Inbox/news-ui-370e9e182-125.png`; footer is gone and pages use its space.
  Named results: `/root/Inbox/news-ui-370e9e182-125.json`.
- **PARTIAL:** Android compilation covers the native integration, but actual
  Samsung bar placement and system gesture dispatch require owner promotion
  and a physical Fold check. The browser tests verify the JavaScript routing,
  not Android's gesture delivery or native insets. No install performed.

After promotion, check cover and landscape against Samsung's status/caption
strip; swipe system back from an article (news stream remains), then from the
stream (returns to launcher). No permissions or lock settings changed.
