# Desktop HTML engine walk

The artifact panel's Chromium (docs/CHAT-ARTIFACT-WORKSPACE.md, "Desktop HTML engine") in a real window, driven with real
input (java.awt.Robot), screenshots taken from outside. On Hyprland (MG1):

```sh
tools/desktop-web-walk/run docs/question-form/proposal.html /tmp/walk-q JCEF_NATIVES.jar tools/desktop-web-walk/question-form.walk
```

`JCEF_NATIVES.jar` is the pinned linux-x64 file named in `tools/soma-release/engine.json` (checked against its sha256 first).
The walk installs it as the update pill does, starts the engine as Soma does (uiScale 2 by default; `-PsomaWebScale`), shows
the page in `ArtifactWeb`, runs the steps and writes `log.txt`, `answers.txt` (what the native Send bar delivered) and one
PNG per `shot`. Steps, one a line: `click X% Y%`, `press X% Y% MS` (a mouse hold), `key K`, `hold KEY MS`, `wait MS`,
`shot NAME`; `shot fullscreen` toggles the window's fullscreen instead (the page must follow).

`question-form.walk`: picks "Beside chat" and "Cover", checks that only the native Send bar sends, and that the page shows
what was sent. Walked on MG1 2026-10-29: PASS (answers.txt `phone:A | layout: Beside chat checks: Cover`). The same day the
herd design review (game mode: state keys, hold-to-approve, back in) and a viewport page (941x1030 at devicePixelRatio 2 in
a 941x1030 panel) were walked: PASS. The viewport page followed the window into fullscreen (1920x1080 at 2x on the 4K
screen) and back (941x1030). With the GPU, WebGL renders on the RTX 5090 (ANGLE); with SOMA_WEB_SOFTWARE=1 pages render the
same and WebGL is unavailable, as in Chromium with the GPU off. Positions are percentages of the window, so a step list fits
one window size.
