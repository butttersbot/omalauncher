# News UI fixes: candidate 124

2026-10-22. Application commit `ed77f21263cacf9114c6c9987d9258e95e39f27b`.
Implemented in builder-b, merged into phone/fold-development and synced.
No APK installed and no lock/security/permission settings changed.

## Changes against NEWS-UI-122.md

1. Pretext's lines no longer undergo a second browser wrap inside a clipped
   one-line box. Rendered glyph widths set justification; a rounding/font-width
   mismatch fits within the line instead of hiding text. Markup characters in
   article text are escaped before rendering.
2. Book and scrolling reader share content preparation. Publisher-declared
   article bodies take priority over whole-page wrappers. Navigation, headers,
   footers, known author/related widgets and duplicate hero size variants are
   excluded, including when reading older cached extracts. New extracts retain
   class information until cleanup, then store cleaned content. Replacement is
   accepted only if the cleaned article is longer than the feed excerpt.
3. BRs and paragraph boundaries stay separate. Headings retain their weight;
   quotes remain italic and can span pages. A drop cap starts a substantial body
   paragraph rather than a removed caption or skip link. Photo headings stay
   with their picture, and scrolling-reader margins no longer shift floated
   images out of the space reserved by the layout engine.
4. Returning from a story restores the stream's saved scroll position.
5. Book controls occupy their own layout rows outside the pages, even when
   toggled invisible. The duplicate outer back bar is removed in landscape.
6. Aa uses the paragraph/text cursor as its anchor, preserving it across repeated
   size selections instead of mapping a page fraction into unrelated text.
7. Controls have distinct accessible labels: Back to news stream, Small/Medium/
   Large text, Story progress, Previous/Next pages. Aa exposes aria-pressed.
8. The next-story card gets measured space after the final line, or its own page;
   it is no longer pulled upward into the body to fit at the foot of a page.
9. The overview's clickable page target includes both the preview and its name;
   pressing the separate label no longer falls through to dismiss the overview.

A browser regression also caught page turns remaining busy when the compositor
omitted transitionend. A bounded, idempotent completion fallback now finishes the
turn and releases Aa/navigation controls.

## Verification

- **PASS: JavaScript syntax, shell syntax and git whitespace checks.**
- **PASS: 23 browser regression checks on the Mac's existing headless Chrome.**
  Tests cover text preservation through hyphenation/pagination, photo exclusions,
  long quotes, heading/photo grouping, full-text loading, cleanup, toolbar bounds,
  page turns, all Aa sizes, accessible labels and stream-position restoration.
  No browser dependencies installed. Run `node tools/news-ui/check.mjs` on a
  machine with existing Chrome (or set CHROME_BIN). The Mac build runs it before
  Gradle and refuses to package on failure.
- **PASS: Mac Android build.** 1,726 app tests; zero failures, six skipped.
- **PASS: candidate taken and signed with the phone's key.** Bundled proot
  libraries matched the checkout byte for byte. Version 124 is staged at
  `/root/Projects/omalauncher/app/build/outputs/apk/debug/app-debug.apk` for owner
  promotion. The final candidate supersedes the earlier 4187f0166 version 124.
- **PARTIAL: Fold UI verification.** Requires owner promotion, then a fresh
  background helper connection. During this turn backgroundAvailable=false and
  the supplied wireless-debugging port 38937 refused reconnection. The kept ADB
  server survived, but the wireless transport dropped and helper exited 255;
  keeping the server does not prevent that failure. No main-display fallback.
  The overview change is compiled but awaits live accessibility/touch testing.

Returned evidence, inspected on the phone:

- `/root/Inbox/news-ui-ed77f2126-124.json`: all 23 named browser checks, no failures.
- `/root/Inbox/news-ui-ed77f2126-124.png`: browser preview, visually inspected;
  book text and controls do not overlap. This is a synthetic regression article
  in Mac Chrome, not an after-fix screenshot from Android WebView.
- `/root/Inbox/soma-ed77f2126-124.json`: exact source commit, version, checksum,
  test totals. Original Mac APK: `/root/Inbox/soma-ed77f2126-124.apk`.

Publisher cleanup is conservative and cannot guarantee every site's content
boundaries. Recheck the original SamMobile, Android Police and Ars stories after
promotion, plus Page 3 entry, overview label selection and Fold photo/book layout.
Screen-off accessibility remains a separate unresolved issue.
