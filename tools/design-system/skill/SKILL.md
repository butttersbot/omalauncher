---
name: soma-design
description: Run before designing, mocking up, theming or building any screen, widget or visual for Soma or a Soma app (herd, Soma Canvas, Hunch, Soma Messages, Soma Notes, home widgets), and before any "use our design system" request. The rule is Boss's: design in the active Soma theme, read at run time, unless he asks for a different style. It says how to find that theme, where its tokens and the generated bundle are, the widget sizes, and what to check before sending.
---

# Soma design

**The rule (Boss, 2026-10-29):** the active Soma theme by default, unless Boss asks for a different style. Never
hard-code a theme, and never design in one because it is first, complete or familiar. Soma is themeable: each theme is
a pack (`app/src/main/assets/packs/<name>/pack.toml` in omalauncher). The base pack the others are written from is not
the choice; the one Boss picked is. When he asks for another style for one piece of work, use it for that piece only.

## Before you design anything

1. **Which theme.** Run `soma-theme` (installed with this skill). It reads the theme Boss's phone wears from the herd
   (`herd --json`: the Fold's machine reports `soma.look`, written by Soma whenever he picks one), then from the phone
   (`oma-phone looks`, on the Fold). Use exactly what it prints. If nothing answers, ask Boss; don't guess.
2. **Canvas first.** Soma Canvas holds every app's Shipped (installed), Approved (the spec) and Proposed screens, and the
   design system's own board (app `soma-design-system`). Start from the app's cards; never redesign from scratch:
   `soma-canvas get APP/PAGE`, `soma-canvas render APP/PAGE`. Every device has its own Canvas agent token in
   `~/.config/soma-canvas` (`soma-theme` prints where). Show Boss the canvas as a Markdown link in your reply (it opens in
   Soma's artifact panel), never in a browser on the phone.
3. **Build from the generated bundle.** omalauncher's `design/soma/` is generated from the active pack by
   `tools/design-system/build.py`: start every mockup from `design/soma/template.html`, which inlines `soma.css` (the
   theme's tokens, its fonts as data URLs, and the panel / [ action ] / tab / row / field / option / badge classes). Its
   first lines say `soma-pack: NAME`; if that isn't the theme from step 1, rebuild it (`python3 tools/design-system/build.py`)
   or use `soma-theme css --embed > theme.css` (the theme as `--soma-*` custom properties, fonts inlined) until it is.
   The written rules are in `docs/DESIGN-SYSTEM.md`.
4. **Both Fold screens.** Cover 1248x1972 px at 420 dpi (about 475x751 CSS px), unfolded 2448x1848 (about 932x704), and
   the artifact side panel (about 466x704). No fixed heights that can come out zero, no scale-to-fit, nothing that needs
   hover, nothing that overlaps when text wraps.
5. **Look before you send.** On the Fold, `soma-look FILE.html` renders it at the cover, unfolded and side-panel sizes
   and reports errors (empty, overflow, clipped, overlapping text, network references). Fix every error, then open the
   PNGs and look at them yourself. On a computer, render it at those sizes in a headless browser and look, or mail it
   to a phone builder (`oma msg send phone:C ...`) to soma-look it. Say in your message that you looked, and at which
   sizes.
6. **Propose, don't approve.** Every mockup you show Boss also goes on Canvas (`soma-canvas propose`, same screen = same
   card), name, size, screen and version explicit. Approving is his alone.

## Widgets (home tiles)

Owner rules, 2026-10-28, kept here as the one set:

- **Sizes.** Always design small, medium and large, plus the expanded view when there is detail. Small = half of one
  page's content width, at most 180 CSS px tall; medium = the full width of one page, at most 180 px; large = the full
  width, at most 300 px and no more than half the usable page height (about 210-220 px wide for S, 430-440 for M/L at
  today's Fold sizes). These are content budgets, not grid facts.
- **Six views plus expanded.** Small/medium/large on the cover and unfolded, and the expanded view on both when it
  exists. Publish them as Proposed cards.
- **One page, one panel.** Unfolded shows two pages; a widget sits on one page and never crosses the fold. In a theme
  drawn in panels, fit the panel's inner rectangle (slant-safe insets) and never move a panel's lines; a tall narrow
  panel gets a narrow variant. Use a smaller content variant before clipping text or shrinking everything.
- **What goes where.** Small is glanceable and opens the detail; medium adds the most useful control; large adds
  context. Full lists, diagnosis and anything disruptive live in the expanded view, and anything disruptive asks first,
  naming what it affects.
- **Honest data.** Label mock data and simulated actions; never show a sample reading as live; a reading that isn't
  there is "—", never 0. Include unavailable, warning and confirmation states.
- **Check it on the phone.** After writing an `.owl`, `owl-check FILE` renders it with live data at its placed size;
  look at the PNG. `oma-phone tile resize WIDGET COLSxROWS` sizes a tile (in a panel theme it moves into a panel that
  size or refuses; panels never change).

## Soma's grammar (every theme)

Bracketed text actions (`[ Open ]`, `[ Skip ]`, `[ Approve ]`), spaced mono captions, flat bordered boxes, display-face
headings in the accent, honest states. The theme decides colours, faces, lines and corners; the grammar stays.

## Don't

- Don't design in a theme because it is the base, first in the folder, or the most complete; read the active one.
- Don't infer the theme from the desktop's colours; that only feeds the base pack's palette.
- Don't invent a palette or copy hex by hand; use the bundle or `soma-theme css`.
- Don't mock up from memory when Canvas has the real screen.
- Don't approve on Boss's behalf.

## When Boss changes themes

Nothing to edit: Soma reports the new theme itself and `soma-theme` follows. Rebuild `design/soma/` with
`tools/design-system/build.py` so the bundle matches.
