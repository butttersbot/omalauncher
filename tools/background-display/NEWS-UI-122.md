# Soma 122 hidden-display news UI pass

2026-10-21 UTC. No reader fixes or APK installs. All UI actions used
`oma-phone --display bg`. Screenshots were captured and visually inspected.
Evidence directory below is `/root/handoff/news-122-evidence/` (phone-local).

## Coverage and outcome

- **PASS: side-by-side isolation while awake.** Display 16 ran NewsActivity;
  read-only activity diagnostics before and after showed X's MainActivity still
  resumed on display 0. No main-display UI commands were used.
- **PASS: Page 3 widget and entry.** Opened Soma on bg, swiped from Page 1 to
  Page 2, read, swiped to Page 3, read; pressed the Daily+ headline. This opens
  the stream, not that particular article. Screens: `widget.png`, `stream.png`.
- **PASS: stream scrolling and opening multiple sources.** Opened SamMobile's
  Daily+ story, Android Police's update-date story, Ars Technica's FCC story,
  and SamMobile's Galaxy A08 story. Used each article's own back control.
  Native accessibility scroll succeeded; offscreen `press` sometimes refused,
  so the stream was explicitly scrolled until the headline was visible.
- **PASS: loading transition.** Android Police and Ars showed GETTING THE WHOLE
  STORY, then it disappeared and text expanded. Android Police read time changed
  from 1 to 3 min, Ars from 1 to 5 min. `loading.png` and saved open/loaded JSON
  document this. This verifies replacement with fuller extracted content, not
  its completeness or fidelity to every original article.
- **PASS with defects: large first letter.** Visible in cover and book layouts.
- **PASS with defects: landscape book, page turns, all Aa sizes, back.** Changed
  only hidden display 16 with `adb -P 5038 ... shell wm size 2448x1848 -d 16`.
  This exercises the app's landscape Fold-sized layout without rotating main.
  Ars advanced 1–2 of 11 to 3–4 of 11, then backward to 1–2; small produced 9
  pages, large 12, medium 11. Clicked the three Aa node IDs from fresh reads.
  The book's own back returned to stream. Physical hinge/rotation behavior was
  not tested. `book-*.json/png` holds evidence.
- **PASS with defects: text around a photo.** Galaxy A08's final spread shows
  text beside, then below the author photo: `photo-wrap.png` (3–4 of 4).
- **FAIL: asleep interaction**, as documented in VERIFICATION-122.md. This UI
  pass was awake; it does not establish screen-off capability.

## Observed issues (not fixed)

1. **Body lines lose words or word fragments at their right edge.** Reproduce:
   open Daily+ → wait for full text → swipe up once. The screenshot shows
   `Android` followed by `ty)` and `Samsung` followed by `n't explain`, missing
   the intervening fragments. Ars book pages and all Aa sizes show the same
   loss. Evidence: `/root/handoff/news-122-evidence/sammobile-body.png`,
   `/root/handoff/news-122-evidence/book-turned.png`. Root cause not established.
2. **Full-text extraction includes website furniture and misplaces the drop
   cap.** Reproduce: open Android Police update-date story → wait → swipe up:
   its body starts with Follow, Link copied to clipboard, publication metadata,
   and a long author biography. Ars book starts with a giant S for “Skip to
   content”; Galaxy full body starts with a giant A for the photo credit.
   Evidence: `/root/handoff/news-122-evidence/androidpolice-clutter.png`,
   `/root/handoff/news-122-evidence/book-initial.png`,
   `/root/handoff/news-122-evidence/galaxy-full.png`.
3. **Extracted text runs words together and flattens structure.** Reproduce:
   Daily+ → full text → scroll to quoted shutdown notice: “Sincerely,The” has
   no separating space; headings/notice text read as body paragraphs. Android
   Police also displays “Androidworks” and “howit”. Evidence:
   `/root/handoff/news-122-evidence/sammobile-body.png`,
   `/root/handoff/news-122-evidence/androidpolice-clutter.png`.
4. **Back loses the stream position.** Reproduce: scroll stream until the
   Android Police update-date card is visible → open it → press ‹. Stream
   returns to the Daily+ hero at the top. Before/after evidence:
   `/root/handoff/news-122-evidence/stream-scrolled.png`,
   `/root/handoff/news-122-evidence/stream-back-top.png`.
5. **Book chrome overlays page text.** Reproduce: Ars landscape → turn forward
   → tap the book center to reveal chrome. Toolbar covers page header; bottom
   progress panel covers the last body line and page numbers. It fades away,
   but text is obstructed while using controls. Evidence:
   `/root/handoff/news-122-evidence/book-toolbar.png`.
6. **Aa changes shift the reading position.** Reproduce: Ars → medium pages
   3–4 → choose small, then large. The spread stays numbered 3–4 but begins
   at different text (“have covered” vs “tic ethics”); medium had begun
   “tions or running”. No stable text anchor is preserved. Evidence:
   `/root/handoff/news-122-evidence/book-turned.png`,
   `/root/handoff/news-122-evidence/book-small.png`,
   `/root/handoff/news-122-evidence/book-large.png`.
7. **Control labels are ambiguous in accessibility.** Reproduce: open landscape
   book → `screen`: three controls all say Aa, and two back controls all say ‹.
   `press '‹'` refuses due to ambiguity; fresh snapshot/node click works.
   No accessible size names or selected state were present in read output.
   Evidence: `/root/handoff/news-122-evidence/book-toolbar.png`,
   `book-toolbar-read.json`, `book-backward-settled.json` in the same directory.
8. **Next-story card overlaps body in provisional book layout.** Reproduce:
   open uncached Galaxy A08 in landscape and capture before full content
   replaces the excerpt. The end marker and card start over the final lines.
   Evidence: `/root/handoff/news-122-evidence/galaxy-provisional.png`.
   This was observed during loading; the settled full final spread has no
   overlap (`photo-wrap.png`). Repeating requires an uncached article; no cache
   was deleted to force a replay.
9. **Page picker did not navigate on the attempted label press.** Before the
   news pass, opened “Page 1  1/6” → pressed “Page 3” in the picker. Picker
   closed, but a fresh screen still said Page 1; swiping twice did reach Page 3.
   One observation only; not isolated to the page picker versus the control
   bridge. Evidence:
   `/root/.local/share/omalauncher/attachments/phone-screen-a0598cc3-096e-4d7b-ad61-f1c3b158b700.png`.

## Helper lifecycle

The old keeper exited 255 and the default ADB daemon was absent at this turn's
start. Started a dedicated server using
`oma keep run background-adb -- adb -P 5038 nodaemon server`, then helper using
`oma keep run background-display -- env ADB_SERVER_SOCKET=tcp:localhost:5038 bash /root/wt/builder-b/tools/background-display/start.sh 127.0.0.1:38937`.
Both remained running for the entire pass (more than five minutes). Persistence
across another agent turn still needs a fresh status check; this is not a
demonstrated lifecycle fix. No device/security settings changed.

News remains on display 16 at Galaxy A08's final spread, landscape dimensions;
main remains on X. Background `stay` was explicitly refused without a main
action. No UI fixes made; only this report and helper run instructions changed.
