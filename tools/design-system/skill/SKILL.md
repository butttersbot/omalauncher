---
name: soma-design
description: Run before designing, mocking up, theming or building any screen or visual for Soma, herd, Soma Canvas, Hunch, Soma Messages, Soma Notes or any other Soma app, and before any "use our design system" or "theme it like Soma" request. It says which look Boss's phone wears (Manga today, not Console), where its tokens and the generated bundle are, and what to check before sending.
---

# Soma's design: the look Boss wears

Soma is themeable: each look is a pack (`app/src/main/assets/packs/<name>/pack.toml` in omalauncher, plus fonts).
**Console** is the base every other pack is written from. It is *not* what Boss uses, and it is not the default to
design in. His phone wears the look he picked, and Soma Canvas wears it too. Agents keep designing in Console because
it is first and most complete. Don't.

## Before you design anything

1. **Which look.** Run `soma-theme`. It asks the herd (`herd --json`: the Fold's machine reports `soma.look`, written
   by Soma whenever a look is picked), then the phone (`oma-phone looks`, on the Fold). Use exactly that look.
2. **Canvas first.** Soma Canvas has every app's Shipped (installed), Approved (the spec) and Proposed screens. Start from
   the app's cards; never redesign from scratch: `soma-canvas get APP/PAGE` and `soma-canvas render APP/PAGE`. The
   server and token are in `~/.config/soma-canvas`; `soma-theme` prints both. Show Boss the canvas as a Markdown link in
   your reply (it opens in Soma's artifact panel), never in a browser on the phone.
3. **Build from the generated bundle.** omalauncher's `design/soma/` is generated from the active pack by
   `tools/design-system/build.py`: start every mockup from `design/soma/template.html`, which inlines `soma.css`
   (tokens, the look's fonts as data URLs, and the panel / [ action ] / tab / row / field / option / badge classes). Its
   first lines say `soma-pack: NAME`: if that isn't the look from step 1, rebuild it (`python3 tools/design-system/build.py`)
   or use `soma-theme css --embed > look.css` (the look as `--soma-*` custom properties, fonts inlined) until it is.
   The rules are in `docs/DESIGN-SYSTEM.md`.
4. **Both Fold screens.** Design for the cover (1248x1972 px at 420 dpi, about 475x751 CSS px) and unfolded
   (2448x1848, about 932x704), and the side panel (about 466x704). No fixed heights that can come out zero, no
   scale-to-fit, nothing that needs hover, nothing that overlaps when text wraps.
5. **Look before you send.** On the Fold, `soma-look FILE.html` renders it at the cover, unfolded and side-panel sizes
   and reports errors (empty, overflow, clipped, overlapping text, network references). Fix every error, then open the
   PNGs and look at them yourself. On a computer, render it at those three sizes in a headless browser and look, or mail
   the file to a phone builder (`oma msg send phone:C ...`) to soma-look it. Say in your message that you looked, and at
   which sizes. `soma-canvas propose` refuses an HTML file without a clean soma-look receipt.

## Soma's grammar (every look)

Bracketed text actions (`[ Open ]`, `[ Skip ]`, `[ Approve ]`), spaced mono captions, flat bordered boxes,
display-face headings in the accent, honest states (a reading that isn't there is "—", never 0). The look decides the
colours, faces, lines and corners; the grammar stays.

## Don't

- Don't theme from `packs/console` because it's first or complete; it's the base, not the choice.
- Don't infer the look from the Omarchy desktop theme; that only feeds Console's palette.
- Don't invent a palette or copy hex by hand; use the bundle or `soma-theme css`.
- Don't mock up from memory when Canvas has the real screen.
- Don't approve on Boss's behalf: approving is his alone, on the canvas.

## When Boss changes looks

Nothing to edit: Soma reports the new look itself, and `soma-theme` follows. Rebuild `design/soma/` with
`tools/design-system/build.py` (on the Fold it asks the phone) so the bundle matches.
