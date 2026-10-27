#!/usr/bin/env python3
"""
Soma's design system, generated from the active look's pack so it can't drift from what the phone draws.

Reads app/src/main/assets/packs/<pack>/pack.toml (the Manga pack by default) and writes:
  design/soma/soma.css            one inline-able stylesheet: the pack's tokens, its four faces as data URLs, components
  design/soma/template.html       the starter every mockup copies (soma.css already inlined)
  design/soma/design-system.html  the system shown as a page, for Boss and for Soma Canvas
  docs/DESIGN-SYSTEM.md           the written rules, its token tables filled from the pack

  python3 tools/design-system/build.py [--pack manga]

Needs fonttools (pyftsubset) to cut the faces to Latin; without it the faces are left out and it says so.
"""
import argparse, base64, os, re, shutil, subprocess, sys, tempfile, tomllib

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
FOLD = {  # The Fold's two screens, in CSS px at 420 dpi (2.625 px per CSS px), and how an artifact sits on each.
    "cover": (1248, 1972, 475, 751, "the artifact fills the cover screen"),
    "unfolded": (2448, 1848, 932, 704, "the artifact sits in the side panel, about half the width (≈466 × 704)"),
}
UNICODES = "U+0020-007E,U+00A0-00FF,U+2013-2014,U+2018-201D,U+2022,U+2026,U+00B7,U+2190-2193,U+25BE,U+2715,U+2713"


def color(value, palette, seen=()):
    """A pack colour: '#rrggbb', a palette name, or 'name@alpha' -> CSS."""
    m = re.fullmatch(r"([#\w]+)(?:@([\d.]+))?", value.strip())
    base, alpha = m.group(1), m.group(2)
    if not base.startswith("#"):
        if base in seen:
            raise ValueError(f"colour loop at {base}")
        base = color(palette[base], palette, seen + (base,))
    if alpha is None:
        return base
    r, g, b = (int(base[i:i + 2], 16) for i in (1, 3, 5))
    return f"rgba({r}, {g}, {b}, {float(alpha):g})"


def subset(src):
    """The face cut to Latin as a woff2 data URL, or None without fonttools."""
    if not shutil.which("pyftsubset"):
        return None
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "f.woff2")
        subprocess.run(["pyftsubset", src, f"--unicodes={UNICODES}", "--flavor=woff2", "--layout-features=*",
                        f"--output-file={out}"], check=True, capture_output=True)
        return "data:font/woff2;base64," + base64.b64encode(open(out, "rb").read()).decode()


def build(pack_id):
    pack_dir = os.path.join(ROOT, "app/src/main/assets/packs", pack_id)
    pack = tomllib.load(open(os.path.join(pack_dir, "pack.toml"), "rb"))
    pal = pack["palette"]
    tokens = {k: color(v, pal) for k, v in pal.items() if isinstance(v, str) and k != "mode"}
    tokens["spot"] = pack.get("palette", {}).get("extra", {}).get("spot", tokens["accent"])
    hair = pack["scrap"]["border"]
    focus_w = pack["scrap"]["focus_width"]

    faces, missing = [], []
    names = {"display": "Soma Display", "body": "Soma Body", "hand": "Soma Hand", "mono": "Soma Mono"}
    fallbacks = {"display": '"Arial Black", Impact, sans-serif', "body": '"Helvetica Neue", Arial, sans-serif',
                 "hand": '"Comic Sans MS", cursive', "mono": "ui-monospace, Menlo, monospace"}
    for voice, spec in pack["type"].items():
        url = subset(os.path.join(pack_dir, spec["file"]))
        if url:
            faces.append(f'@font-face {{ font-family: "{names[voice]}"; src: url({url}) format("woff2"); '
                         f'font-weight: 100 900; font-display: block; }}')
        else:
            missing.append(voice)

    mono = pack["type"]["mono"]
    css = f"""/* soma.css: Soma's design system, generated from the {pack['pack']['name']} pack by tools/design-system/build.py.
   Don't edit by hand: change the pack, then rebuild. Inline this whole file in a <style> (mockups are offline, single files). */
{chr(10).join(faces)}
:root {{
{chr(10).join(f'  --{k.replace("_", "-")}: {v};' for k, v in tokens.items())}
  --hair: {hair}px;            /* every panel line: a hairline of ink */
  --focus: {focus_w}px solid var(--spot);
  --radius: {pack['shape']['radius']}px;          /* panels are square */
  --font-display: "{names['display']}", {fallbacks['display']};
  --font-body: "{names['body']}", {fallbacks['body']};
  --font-hand: "{names['hand']}", {fallbacks['hand']};
  --font-mono: "{names['mono']}", {fallbacks['mono']};
  --mono-tracking: {mono.get('tracking', 0)}em;
  --gap: 12px; --pad: 14px; --gap-rows: 18px;   /* rows sit further apart than panels side by side */
}}
* {{ box-sizing: border-box; }}
html, body {{ margin: 0; background: var(--background); color: var(--foreground); font: 15px/1.4 var(--font-body);
  -webkit-text-size-adjust: 100%; overflow-wrap: anywhere; }}
.soma {{ padding: var(--pad); display: flex; flex-direction: column; gap: var(--gap-rows); min-height: 100vh; }}
h1, .display {{ font-family: var(--font-display); font-weight: 400; letter-spacing: -0.01em; line-height: 1.12; margin: 0; }}
header {{ display: flex; flex-direction: column; gap: 6px; }}
h1 {{ font-size: clamp(24px, 7vw, 34px); }}
h2 {{ font-family: var(--font-display); font-weight: 400; font-size: 19px; margin: 0; line-height: 1.15; }}
.caption, .label {{ font: {mono.get('weight', 700)} 11px/1.3 var(--font-mono); text-transform: uppercase;
  letter-spacing: var(--mono-tracking); color: var(--muted); }}
.hand {{ font-family: var(--font-hand); font-size: 18px; color: var(--muted); }}
p {{ margin: 0; }}
.muted {{ color: var(--muted); }}
/* Panel: paper in a hairline of ink; never a shadow, never a rounded corner. */
.panel {{ border: var(--hair) solid var(--border); background: var(--background); padding: var(--pad);
  display: flex; flex-direction: column; gap: 8px; min-width: 0; }}
.panel.ink {{ background: var(--alt); color: var(--on-alt); border-color: var(--alt); }}
.panel.tone {{ background-image: radial-gradient(circle, var(--foreground) 1px, transparent 1.2px); background-size: 6px 6px; }}
.panel > .title {{ display: flex; justify-content: space-between; align-items: baseline; gap: 8px; }}
.panels {{ display: grid; gap: var(--gap); grid-template-columns: repeat(auto-fit, minmax(min(100%, 200px), 1fr)); }}
/* Actions are bracketed words in the body face: one line, never wrapped. Primary = red, secondary = ink. */
.action {{ font: 15px/1.2 var(--font-body); color: var(--accent); background: none; border: 0; padding: 10px 2px;
  min-height: 44px; white-space: nowrap; cursor: pointer; }}
.action::before {{ content: "[ "; }} .action::after {{ content: " ]"; }}
.action.secondary {{ color: var(--foreground); }}
.action:focus-visible, .option:focus-visible, .field:focus-within {{ outline: var(--focus); outline-offset: 2px; }}
.actions {{ display: flex; flex-wrap: wrap; gap: 4px 16px; align-items: center; }}
/* Tab chips: mono capitals; the one showing is solid ink. */
.tabs {{ display: flex; flex-wrap: wrap; border: var(--hair) solid var(--border); width: fit-content; max-width: 100%; }}
.tab {{ font: 700 11px/1 var(--font-mono); text-transform: uppercase; letter-spacing: var(--mono-tracking);
  padding: 9px 12px; background: var(--background); color: var(--foreground); border: 0; min-height: 36px; }}
.tab + .tab {{ border-left: var(--hair) solid var(--border); }}
.tab.on {{ background: var(--foreground); color: var(--background); }}
/* List rows: a hairline between, the name in body bold, details in muted. */
.rows {{ display: flex; flex-direction: column; border-top: var(--hair) solid var(--border); }}
.row {{ display: flex; gap: 10px; align-items: baseline; justify-content: space-between; padding: 10px 0;
  border-bottom: var(--hair) solid var(--border); min-width: 0; }}
.row b {{ font-weight: 700; }} .row .muted {{ font-size: 13px; }}
/* Form field: a ruled box, label above in caption capitals. */
.field {{ display: flex; flex-direction: column; gap: 6px; }}
.field input, .field textarea {{ font: 15px/1.4 var(--font-body); color: var(--foreground); background: var(--background);
  border: var(--hair) solid var(--border); border-radius: 0; padding: 10px 12px; min-height: 44px; width: 100%; }}
/* Option buttons (a question with choices): ruled boxes, the chosen one solid ink. */
.options {{ display: flex; flex-direction: column; gap: 8px; }}
.option {{ text-align: left; font: 15px/1.35 var(--font-body); color: var(--foreground); background: var(--background);
  border: var(--hair) solid var(--border); padding: 12px 14px; min-height: 48px; width: 100%; }}
.option.on {{ background: var(--foreground); color: var(--background); }}
.option small {{ display: block; color: var(--muted); font-size: 13px; }}
.option.on small {{ color: var(--background); opacity: .75; }}
.badge {{ font: 700 10px/1 var(--font-mono); text-transform: uppercase; letter-spacing: var(--mono-tracking);
  background: var(--spot); color: #fff; padding: 4px 6px; }}
.warn {{ color: var(--red); }} .ok {{ color: var(--green); }}
"""
    out_dir = os.path.join(ROOT, "design/soma")
    os.makedirs(out_dir, exist_ok=True)
    open(os.path.join(out_dir, "soma.css"), "w").write(css)

    template = f"""<!doctype html>
<!-- A Soma mockup. Copy this file, keep the <style> block as it is (it is design/soma/soma.css, inlined), put your
     screen inside .soma. Offline: no CDN, no web fonts, no network calls. Look at it before sending: soma-look FILE. -->
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Mockup title</title>
<style>
{css}
</style></head>
<body><main class="soma">
  <header><div class="caption">App · Screen</div><h1>Screen title</h1></header>
  <section class="panel"><div class="title"><h2>Panel</h2><span class="caption">Note</span></div>
    <p>What this panel says, in plain words.</p>
    <div class="actions"><button class="action">Do it</button><button class="action secondary">Not now</button></div>
  </section>
</main></body></html>
"""
    open(os.path.join(out_dir, "template.html"), "w").write(template)

    swatches = "".join(f'<div class="sw"><i style="background:var(--{k.replace("_", "-")})"></i><span class="caption">{k}</span><code>{v}</code></div>'
                       for k, v in tokens.items())
    page = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Soma design system</title>
<style>
{css}
.sw {{ display: grid; grid-template-columns: 28px 1fr; grid-template-rows: auto auto; column-gap: 8px; align-items: center; }}
.sw i {{ grid-row: span 2; width: 28px; height: 28px; border: var(--hair) solid var(--border); }}
.sw code {{ font: 12px var(--font-mono); color: var(--muted); }}
.swatches {{ display: grid; gap: 10px; grid-template-columns: repeat(auto-fit, minmax(min(100%, 150px), 1fr)); }}
.frames {{ display: flex; flex-wrap: wrap; gap: var(--gap); align-items: flex-end; }}
.frame {{ border: var(--hair) solid var(--border); display: flex; align-items: flex-end; justify-content: center; padding: 6px; }}
</style></head>
<body><main class="soma">
<header><div class="caption">Generated from the {pack['pack']['name']} pack</div><h1>Soma design system</h1>
<p class="hand">every mockup copies this, and looks before it sends</p></header>

<section class="panel"><h2>Colour</h2><div class="swatches">{swatches}</div>
<p class="muted">Ink on paper, one spot red. The accent is for actions and the one thing that needs you; red for warnings; green for done.</p></section>

<section class="panel"><h2>Type</h2>
<div class="display" style="font-size:30px">Display: big titles, numbers</div>
<p>Body: everything you read. Schibsted Grotesk, 15 px, one weight for text and bold for names.</p>
<div class="caption">Caption: mono capitals, labels and tabs</div>
<div class="hand">Hand: narration, asides, empty states</div></section>

<section class="panel"><h2>Panels</h2>
<div class="panels">
<div class="panel"><div class="title"><h2>Paper</h2><span class="caption">default</span></div><p>Paper in a hairline of ink. Square, no shadow.</p></div>
<div class="panel ink"><div class="title"><h2>Ink</h2></div><p>The emphasised panel: white on solid ink.</p></div>
<div class="panel tone"><h2 style="background:var(--background);width:fit-content;padding:0 4px">Tone</h2></div>
</div></section>

<section class="panel"><h2>Actions and tabs</h2>
<div class="actions"><button class="action">Approve</button><button class="action secondary">Not now</button><span class="badge">needs you</span></div>
<div class="tabs"><button class="tab on">Preview</button><button class="tab">Source</button><button class="tab">Files</button></div></section>

<section class="panel"><h2>Rows</h2>
<div class="rows"><div class="row"><b>Andrew</b><span class="muted">2 min</span></div><div class="row"><b>Kel</b><span class="muted">yesterday</span></div></div></section>

<section class="panel"><h2>A question</h2>
<div class="options"><button class="option on">Build the approved layout<small>Recommended</small></button>
<button class="option">Keep the grid<small>Update the mockups instead</small></button></div>
<label class="field"><span class="caption">Or say</span><input placeholder="Type an answer"></label>
<div class="actions"><button class="action">Send</button></div></section>

<section class="panel"><h2>The Fold's two screens</h2>
<div class="frames"><div class="frame" style="width:95px;height:150px"><span class="caption">cover</span></div>
<div class="frame" style="width:186px;height:141px"><span class="caption">unfolded</span></div>
<div class="frame" style="width:93px;height:141px"><span class="caption">side panel</span></div></div>
<div class="rows">
<div class="row"><b>Cover</b><span class="muted">475 × 751 · full screen</span></div>
<div class="row"><b>Unfolded</b><span class="muted">932 × 704 · whole screen</span></div>
<div class="row"><b>Side panel</b><span class="muted">≈466 × 704 · artifacts beside the chat</span></div></div></section>

<section class="panel"><h2>Do · Don't</h2>
<div class="rows">
<div class="row"><span class="ok">Do</span><span>let text wrap; size by content</span></div>
<div class="row"><span class="ok">Do</span><span>look at all three sizes before sending</span></div>
<div class="row"><span class="warn">Don't</span><span>fixed heights, hover-only, scale-to-fit</span></div>
<div class="row"><span class="warn">Don't</span><span>shadows, rounded corners, web fonts, CDNs</span></div></div></section>
</main></body></html>
"""
    open(os.path.join(out_dir, "design-system.html"), "w").write(page)

    tok_rows = "\n".join(f"| `--{k.replace('_', '-')}` | `{v}` | `{pal.get(k, '')}` |" for k, v in tokens.items())
    md = f"""# Soma design system

Generated by `tools/design-system/build.py` from the active look's pack
(`app/src/main/assets/packs/{pack_id}/pack.toml`, "{pack['pack']['name']}"). Don't edit the tables by hand: change the
pack and rebuild. The visual version is `design/soma/design-system.html`; the stylesheet is `design/soma/soma.css`;
every mockup starts from `design/soma/template.html`.

## The look

Ink on white paper with one spot red, as a manga page: panels are square and ruled in a hairline of ink
({hair} px), nothing casts a shadow, depth comes from ink and screentone. The emphasised panel is solid ink with
white lettering.

## Colour

| Token | CSS | Pack |
|---|---|---|
{tok_rows}

The accent (spot red) marks actions and the one thing that needs Boss. `red` is for warnings, `green` for done.
Text is ink or muted ink; nothing else.

## Type

| Voice | Face | Use |
|---|---|---|
| Display | Dela Gothic One | titles, big numbers, panel headings |
| Body | Schibsted Grotesk | everything read; 15 px; bold for names |
| Mono | JetBrains Mono, bold capitals, tracking {mono.get('tracking', 0)} | captions, labels, tabs |
| Hand | Patrick Hand | narration, asides, empty states |

The faces are embedded in `soma.css` as data URLs (Latin only), so a mockup is one offline file.

## Components (classes in soma.css)

- **Panel** `.panel`: paper in a hairline; `.panel.ink` solid ink; `.panel.tone` screentone; `.panels` lays them
  out, wrapping on narrow screens.
- **Action** `.action`: a bracketed word in the body face, `[ Approve ]`, red; `.action.secondary` in ink. One line,
  never wrapped; at least 44 px tall to tap. Group with `.actions`.
- **Tab chip** `.tabs > .tab`: mono capitals in a ruled strip; the one showing is `.on` (solid ink).
- **List row** `.rows > .row`: hairlines between; name bold, details muted.
- **Form field** `.field`: caption-capital label above a ruled box.
- **Option button** `.options > .option`: a ruled box per choice, the chosen one `.on`; a `<small>` line for the
  reason.
- **Badge** `.badge`: mono capitals on the spot red, for "needs you".
- Focus is the spot red, {focus_w} px.

## The Fold

| Screen | Device px | CSS px (420 dpi) | Where an artifact goes |
|---|---|---|---|
| Cover | 1248 × 1972 | 475 × 751 | full screen |
| Unfolded | 2448 × 1848 | 932 × 704 | the side panel beside the chat, ≈466 × 704 |

Design for both; the side panel is the narrowest real width a mockup gets unfolded, and the cover is the tallest.

## Do and don't

- Do let text wrap and size things by their content; use `.panels` and flex-wrap so rows reflow.
- Do keep everything in one offline file: `soma.css` inlined, no CDN, no web fonts, no network calls.
- Do look before sending: `soma-look FILE` renders it at cover, unfolded and side-panel sizes, prints the PNGs and
  warns on overlapping or clipped text, horizontal overflow, empty (zero-height) content and network references.
  `soma-canvas propose` refuses a file that hasn't been looked at.
- Don't use fixed heights that can come out zero, scale-to-fit (`transform: scale` from measured height), hover-only
  controls, shadows, rounded corners or a generic web look.
"""
    open(os.path.join(ROOT, "docs/DESIGN-SYSTEM.md"), "w").write(md)
    print("wrote design/soma/soma.css, template.html, design-system.html, docs/DESIGN-SYSTEM.md")
    if missing:
        print("WARNING: no fonttools (pyftsubset): faces left out for", ", ".join(missing), file=sys.stderr)


if __name__ == "__main__":
    a = argparse.ArgumentParser()
    a.add_argument("--pack", default="manga")
    build(a.parse_args().pack)
