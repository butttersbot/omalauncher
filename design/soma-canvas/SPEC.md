# Soma Canvas: build spec (S1–S4)

From Phone C, 2026-10-25. Boss approved the mockup ("yeah go"). The approved design is
`soma-canvas-approved-v5.html` (sent with this spec to ~/Downloads/From Fold on the Mac Pro). It is the
design to build. Open it in a browser at 880×790 (the unfolded Fold) and 390×860 (the cover) and match it.
Don't invent a different UI; when something in it can't be built as drawn, say so in your report instead of
changing it.

## Why

Agents keep ignoring mockups (Hunch's Market screen shipped as a bare list instead of the approved design) and
mock up new features from scratch instead of from what's installed. Soma Canvas is the one place every app's
screens live, each as **Shipped** (what's on Boss's Fold now), **Approved** (the spec) and **Proposed** (waiting
for his call). Agents read it before mocking up or building, and builds publish to it.

## Where

- New project `~/ClaudeSync/projects/soma-canvas`, its own git repo, **seeded by copying from Pond**
  (`~/ClaudeSync/projects/pond`) at its current HEAD. Record that commit in the README.
- **Don't touch live Cove**: not its code, its data (`pond/data`), its LaunchAgent `com.jackkim.pond`, or :5858.
  NEETbux still uses it.
- Serve on **:5860**, bound to 127.0.0.1 and the Mac Pro's tailnet address (100.120.219.46) only. LaunchAgent
  `com.jackkim.soma-canvas`. Don't change `tailscale serve`: Cove sits at its root.
- Same Node runtime Cove uses (`~/.local/share/node-security/`, Node 26.12.1), same tldraw version and licensing
  as Pond.

## Keep from Pond

- tldraw canvas plus tldraw sync, Fastify, SQLite per board.
- Boards and pages (`rooms.ts`, `boards.ts`, the page work in Board.tsx).
- HTML cards: `WidgetShape` and its sandboxed iframe runtime, so an HTML mockup is live and tappable on the canvas.
- Images (`ImageShape`, `assets.ts`, `imageThumbnails`/`imageCache`), for build screenshots.
- The board write API and agent token (`boardApi.ts`, `x-pond-token` → rename the header to `x-canvas-token`,
  token in `data/agent-token.txt`).
- Typed artifacts (`artifactCatalog.ts`, `artifacts.ts`, `artifactRoutes.ts`) with retry identity and revision
  conflicts.
- The layout work so agents can see a board and place things properly: `artifactLayout.ts`, `geometry.ts`,
  placement verification, `renderer.ts` + `RenderPage.tsx` (render/capture a page to PNG), `undo.ts`/`runDiff.ts`.
- Their tests.

## Strip

Everything decorative or unrelated:
- paper materials, decals, dress/frames, paper wash/band, imperfection, plates, dye rings, stickers, notes;
- `kits/`;
- NEETbux galleries, review mode, captions, public gallery/HOTDOG;
- Drift, driftSort, the Jev/classifier plan, semanticMap;
- art bank, daily tasks, places, slips, deskDeals;
- the production* law/decision/document/source modules, project records;
- X posts, unfurl extras;
- Apple sign-in, accounts and members beyond the single owner key;
- push, transcribe;
- the old Home/shelf/desk chrome and the composer with its in-board agent chat (agents use the API instead).

If unsure whether something is load-bearing, keep it out of the UI and list it in your report.

## Data model

- **Board** = an app (Hunch, Soma Messages, Launcher, …). It has a name, the installed version, and pages.
- **Page** = a feature or area of the app (Soma Messages: "Chats", "Phone calls"). Each page is its own tldraw
  page, like Figma's.
- **Flow** = a titled row of screens on a page, in the order they're used (the island with the ink tab in the
  mockup).
- **Screen card**: id, name, plus up to three states, `shipped` / `approved` / `proposed`. Each state is
  `{kind: html|image, asset, version, source (who/what posted it), at}`. Also:
  - `drift`: true when a shipped state was published while an approved one exists and they differ. Set by the
    publisher (S4); allow setting it by hand in S1.
  - `waiting`: a proposal Boss hasn't acted on.
- **Comment thread**: anchored to a card and a point on it (in the card's own 300×560 pixels, so it moves with
  it), with messages `{who, agent: bool, text, at}` and resolved/reopened.
- **NEEDS YOU** = cards with drift, cards waiting, and unresolved threads whose last word is an agent's. Count it
  per board and per page.

## UI: as the approved mockup

- **Canvas:** infinite, paper with a faint dot grid. Pinch zooms about the fingers' midpoint and pans with it.
  Lifting one finger keeps panning from where the other is, with no jump: this was a real bug in v4, see the
  mockup's gesture code. Lines never thinner than 1 screen px. Double-tap a screen flies to it.
- **Chrome, minimal** (nothing across the top):
  - top-left breadcrumb chip: shelf icon · App (mono, grey) / Page (display, bold) ▾. The page name opens the
    page index: numbered pages, screen counts, a red needs count, "+ New page".
  - top-right red NEEDS YOU with its count. It cycles through what needs Boss across all apps and pages, turning
    to the page and flying to the screen, and opens the thread if there is one.
  - bottom pill: Move, Comment, and the zoom % (which fits all).
- **Screen card:** a 300×560 frame, the name, the version, and the SHIPPED / APPROVED / PROPOSED switch (a red
  dot when waiting). A red outline plus a tilted "NOT BUILT AS APPROVED" flag when drift is set.
- **Comments, Figma-style:**
  - with the Comment tool, tap a screen to drop a pin and open a thread;
  - pins are speech balloons that stay the same size on screen, with a red dot when unread;
  - a thread has its header (page · screen, Resolve/Reopen, ✕), messages (agents' names in red), and a reply
    field.
- **The shelf:** manga pages of app panels.
  - Panels are cut by lines across the page, so gutters are parallel. Use the mockup's `LAYOUTS`/`quad()` code;
    the outlines are SVG drawn above the panels.
  - Labels sit in a strip parallel to the bottom edge and never touch a line.
  - It scrolls with no scroll bar, with a hand-lettered folio between pages.
  - On the cover (<640px) it is two plain columns, and names wrap to two lines.
  - Each panel shows a miniature of the app's newest approved screen, plus page and screen counts and the
    needs count.
- **Type:** the Manga look's fonts, served locally: Dela Gothic One (display), Schibsted Grotesk (body),
  JetBrains Mono, Patrick Hand. They're embedded in the mockup; also in the omalauncher repo under
  `app/src/main/assets/packs/manga/fonts`. Colours: paper #fdfdfb, ink #111114, muted #77777e, red #d7262d,
  hairline 1.15px, ink offset shadows.

## Agent API (token-gated, JSON)

- `GET /api/apps` and `GET /api/apps/:app` (its pages, flows, cards, states, needs).
- `GET /api/cards/:id` returns all three states, with HTML inline or an image URL, plus the comments.
- `POST /api/cards/:id/proposed` (HTML or PNG) and `POST /api/apps/:app/pages/:page/cards` (a new card in a named
  flow). Placement is automatic and verified: no overlaps; cards are added to the end of their flow.
- `POST /api/cards/:id/shipped` takes `{png or html, version, build}` and sets drift when an approved state
  exists.
- **Approve is Boss's alone, from the UI** (Proposed → Approved). The API refuses it to agent tokens.
- `GET/POST /api/cards/:id/comments` (agents reply as their name).
- `GET /api/apps/:app/pages/:page/render.png` returns the page as the agents see it.
- A small CLI, `soma-canvas` (bash or node, no deps beyond curl/jq), usable from the Mac and from the Fold's Linux:
  `get APP[/PAGE[/SCREEN]]`, `propose`, `ship`, `comment`, `needs`, `render`. Put it in the repo's `bin/`.

## Slices

1. **S1, core:** project seeded and stripped; the data model; the API and CLI; tests pass; `npm run build` passes.
2. **S2, UI:** canvas, chrome, page index, screen cards and shelf as the mockup.
3. **S3, comments.**
4. **S4, seed and publish:**
   - The **Soma Canvas** board: its own approved mockup as its first Approved card.
   - The **Hunch** board: the paper HTML from omalauncher `hunch/src/main/assets/paper/` as Shipped, and the
     HTML-parity mockups as Approved, with Market flagged drift if it still differs.
   - The **Soma Messages** board, pages Chats and Phone calls: shipped screens from the latest messages-maclink
     walk screenshots, version 0.8.4.
   - A `publish` step the lanes can call after a walk: walk screenshots → Shipped, tagged with the version.

Commit per slice. Report after each slice to this lane's caller (Phone C): what was done, the test and build
results, and screenshots at 880×790 and 390×860 put beside the matching mockup screens. Plainly list what
doesn't match the mockup, and what wasn't checked.

## Don'ts

- No change to Cove, its data, its port or `tailscale serve`.
- No external messages, no credentials in Git.
- Approve stays human-only.
- Don't build the Android launcher or touch the phone.
