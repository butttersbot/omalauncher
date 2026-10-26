# Soma Canvas (banked 2026-10-25)

The one source of truth for our app designs: an infinite canvas where every app's screens live as Shipped / Approved / Proposed, so agents build from approved designs and mock up new features from the real shipped screens (Hunch's Market was built bare instead of from its mockup; this is the fix).

## Mockup
Soma artifact "soma-canvas.html" v5 (copy: /root/handoff/canvas/soma-canvas.html on the Fold). Boss APPROVED v5 on 2026-10-25 ("yeah go").

## Decided with Boss
- Real infinite canvas (not a book view). Web is fine. Clean slate, designs only; no decorative features (a scrapbook would go on home instead).
- Manga-panel style; chrome minimal: breadcrumb chip (shelf icon · App / Page ▾ with a page index), NEEDS YOU (kept, Boss likes it: flies to what needs him across apps and pages), bottom pill (move, comment, zoom = fit).
- Shelf: manga pages of app panels, scrolls with no scroll bar; panels cut from shared lines so gutters are parallel and labels never touch a line.
- Pages per app (Figma-style): Phone calls is a page of Soma Messages, not an app. Flows = rows of screens on a page.
- Comments, Figma-style: pins on a screen spot, threads with replies, resolve/reopen; agent replies count toward NEEDS YOU.

## Build plan (not started)
- New project seeded from Cove/Pond (Mac Pro, ~/ClaudeSync/projects/pond; live Cove stays for NEETbux). Keep: tldraw canvas, boards/pages, HTML cards, board API + agent token, typed artifact create/read/update, placement/layout + render/capture so agents can see the board. Strip: paper materials/decals/stickers/notes, NEETbux galleries/review, Drift/Jev, daily tasks, places, art bank, public gallery, Apple sign-in, old shelf/chrome.
- Shipped screens come from each build's walkthrough screenshots (published by the build lane, tagged with version; promoted build = Shipped). HTML apps (Hunch paper, News) publish their HTML.
- Agent rules: fetch the card before mocking up a feature; a build task names its approved card; walk puts the new screenshot beside the approved card; mismatch blocks "ready". soma-artifact mockups land as Proposed.
- Build on the Mac Pro via this builder's lane (oma-macbuild --ask), not on the phone.

## Status
2026-10-25: spec written (/root/handoff/canvas/SPEC.md; sent with the approved mockup to the Mac Pro's ~/Downloads/From Fold). Handed to Soma build lane C (thread t7d2750c1, mail 20261025-160345-b10fbe): S1 core/API/CLI, S2 UI, S3 comments, S4 seed + publish; reports after each slice. New repo ~/ClaudeSync/projects/soma-canvas on :5860; live Cove untouched.

2026-10-25 later: S1 core (b4bc1f2, 86/86 tests) and S2+S3 UI+comments (f739004) done by lane C; live on http://100.120.219.46:5860 (empty until S4). Phone C checked the 8 mockup-vs-build side-by-sides: match. Additions: APPROVE bar under PROPOSED (owner only), owner key via ?key= link (Taildropped, never mailed), resolve owner-only. S4 (seed + publish, CLI + agent token to the Fold) asked 20261025-181227-13fadc.

2026-10-26 13:05: Mac Pro offline; no S4 report came back from it. S4 moved to the MacBook lane C (t6260301f, mail 20261026-130510-d0513d): pull synced repo into ~/Projects/soma-canvas-macbook, replayable seed script, publish step, temp serve on MacBook :5860 (100.105.137.74), owner link + CLI + agent token by Taildrop. Phone A told (mail 20261026-130456-6a0ad0).
