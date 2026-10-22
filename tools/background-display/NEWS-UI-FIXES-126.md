# News 126: cover-width sideways scrolling

Application commit `e0f931d29`, 2026-10-22. Reviewed and applied the Mac peer's
reported overflow fix within the ongoing news UI work.

Long words/URLs can no longer force the stream's grid/flex children wider than
the viewport. Headlines, summaries and article text wrap; outer stream/reader
panes scroll vertically only. Locally scrollable tables retain their own policy.

- **PASS:** 38 browser checks, including 412 px and 360 px iframe viewports.
  The new cases use unbroken words, long URLs, oversized image/media attributes,
  tables and preformatted content. They check scrollWidth against clientWidth,
  card bounds, headline wrapping and absence of sideways blank space in the
  stream and reader, rather than relying on the wide test browser window.
- **PASS:** Mac build; 1,726 app tests, zero failures, six skipped.
- **PASS:** final candidate received, library bytes matched, signed and staged
  as version 126 at
  `/root/Projects/omalauncher/app/build/outputs/apk/debug/app-debug.apk`.
- **PARTIAL:** physical Fold retest awaits owner promotion. Nothing installed
  or deleted; no security or permission changes.

Evidence: `/root/Inbox/news-ui-e0f931d29-126.json` and
`/root/Inbox/soma-e0f931d29-126.json`. The returned synthetic book preview at
`/root/Inbox/news-ui-e0f931d29-126.png` was inspected; cover-width verification
is recorded by the named assertions, not that wide book screenshot.
