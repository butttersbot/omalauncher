#!/usr/bin/env python3
"""
Soma's design system, generated from the ACTIVE look's pack so it can't drift from what the phone draws.
Soma has switchable looks (theme packs); Manga is just today's. Rerun this when the look changes.

Reads app/src/main/assets/packs/<pack>/pack.toml (default: the look picked on the phone, `oma-phone looks`) and writes:
  design/soma/soma.css            one inline-able stylesheet: the pack's tokens, its four faces as data URLs, components
  design/soma/template.html       the starter every mockup copies (soma.css already inlined)
  design/soma/design-system.html  the system shown as a page, for Boss and for Soma Canvas
  docs/DESIGN-SYSTEM.md           the written rules, its token tables filled from the pack

  python3 tools/design-system/build.py [--pack auto|manga|riso|...]

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


def active_pack():
    """The look picked on the phone, or None off the phone."""
    try:
        import json
        r = subprocess.run(["oma-phone", "looks"], capture_output=True, text=True, timeout=20)
        return json.loads(r.stdout).get("look")
    except Exception:
        return None


def lead_comment(path):
    """The pack's own description: its leading comment lines."""
    out = []
    for line in open(path):
        if not line.startswith("#"):
            break
        out.append(line[1:].strip())
    return " ".join(x for x in out if x).strip()


def build(pack_id):
    pack_dir = os.path.join(ROOT, "app/src/main/assets/packs", pack_id)
    pack = tomllib.load(open(os.path.join(pack_dir, "pack.toml"), "rb"))
    pal = dict(pack["palette"])
    pal.update(pack["palette"].get("extra", {}))
    tokens = {k: color(v, pal) for k, v in pal.items() if isinstance(v, str) and k != "mode"}
    tokens.setdefault("spot", tokens["accent"])
    scrap = pack.get("scrap", {})
    hair = scrap.get("border", 1)
    focus_w = scrap.get("focus_width", 2)
    focus_c = color(scrap.get("focus_color", "accent"), pal)
    radius = pack.get("shape", {}).get("radius", 0)
    panel_fill = color(scrap.get("fill", "background"), pal)
    panel_ink = color(scrap.get("ink", "foreground"), pal)
    panel_line = color(scrap.get("border_color", "border"), pal)
    sh = scrap.get("shadow")
    shadow = f"{sh.get('x', 0)}px {sh.get('y', 0)}px {sh.get('blur', 0)}px {color(sh.get('color', 'foreground'), pal)}" if sh else "none"
    alt = scrap.get("alt", {})
    alt_fill = color(alt.get("fill", "alt"), pal) if "alt" in pal or "fill" in alt else tokens["foreground"]
    alt_ink = color(alt.get("ink", "on_alt"), pal) if "on_alt" in pal or "ink" in alt else tokens["background"]
    size = pack["type"].get("size", {})
    body_px = size.get("body", 15)

    faces, missing = [], []
    names = {"display": "Soma Display", "body": "Soma Body", "hand": "Soma Hand", "mono": "Soma Mono"}
    generic = {"sans-serif": '"Helvetica Neue", Arial, sans-serif', "serif": "Georgia, serif",
               "monospace": "ui-monospace, Menlo, monospace", "cursive": '"Comic Sans MS", cursive'}
    stacks = {}
    for voice in ("display", "body", "hand", "mono"):
        spec = pack["type"].get(voice, pack["type"]["body"])
        fallback = generic.get(spec.get("family", "sans-serif"), "sans-serif")
        url = subset(os.path.join(pack_dir, spec["file"])) if spec.get("file") else None
        if url:
            faces.append(f'@font-face {{ font-family: "{names[voice]}"; src: url({url}) format("woff2"); '
                         f'font-weight: 100 900; font-display: block; }}')
            stacks[voice] = f'"{names[voice]}", {fallback}'
        else:
            if spec.get("file"):
                missing.append(voice)
            stacks[voice] = fallback
    voice_css = {v: (pack["type"].get(v, {}).get("weight", 400), "uppercase" if pack["type"].get(v, {}).get("uppercase") else "none",
                     pack["type"].get(v, {}).get("tracking", 0)) for v in ("display", "body", "hand", "mono")}

    mono = pack["type"]["mono"]
    css = f"""/* soma.css: Soma's design system, generated from the active look's pack by tools/design-system/build.py.
   soma-pack: {pack_id}  ({pack['pack']['name']})
   Don't edit by hand; when the look changes, rebuild. Inline this whole file in a <style> (mockups are offline, single files). */
{chr(10).join(faces)}
:root {{
{chr(10).join(f'  --{k.replace("_", "-")}: {v};' for k, v in tokens.items())}
  --hair: {hair}px;                 /* a panel's line, the pack's scrap border */
  --radius: {radius}px;             /* the pack's corner */
  --shadow: {shadow};
  --panel-fill: {panel_fill}; --panel-ink: {panel_ink}; --panel-line: {panel_line};
  --alt-fill: {alt_fill}; --alt-ink: {alt_ink};
  --focus: {focus_w}px solid {focus_c};
  --font-display: {stacks['display']};
  --font-body: {stacks['body']};
  --font-hand: {stacks['hand']};
  --font-mono: {stacks['mono']};
  --display-weight: {voice_css['display'][0]}; --display-case: {voice_css['display'][1]}; --display-tracking: {voice_css['display'][2]}em;
  --mono-weight: {voice_css['mono'][0]}; --mono-case: {voice_css['mono'][1]}; --mono-tracking: {voice_css['mono'][2]}em;
  --hand-weight: {voice_css['hand'][0]};
  --body-size: {body_px}px;
  --gap: 12px; --pad: 14px; --gap-rows: 18px;   /* rows sit further apart than panels side by side */
}}
* {{ box-sizing: border-box; }}
html, body {{ margin: 0; background: var(--background); color: var(--foreground); font: var(--body-size)/1.4 var(--font-body);
  -webkit-text-size-adjust: 100%; overflow-wrap: anywhere; }}
.soma {{ padding: var(--pad); display: flex; flex-direction: column; gap: var(--gap-rows); min-height: 100vh; }}
h1, h2, .display {{ font-family: var(--font-display); font-weight: var(--display-weight); text-transform: var(--display-case);
  letter-spacing: var(--display-tracking); line-height: 1.12; margin: 0; }}
header {{ display: flex; flex-direction: column; gap: 6px; }}
h1 {{ font-size: clamp(24px, 7vw, 34px); }}
h2 {{ font-size: 19px; }}
.caption, .label {{ font: var(--mono-weight) 11px/1.3 var(--font-mono); text-transform: var(--mono-case);
  letter-spacing: var(--mono-tracking); color: var(--muted); }}
.hand {{ font-family: var(--font-hand); font-weight: var(--hand-weight); font-size: 18px; color: var(--muted); }}
p {{ margin: 0; }}
.muted {{ color: var(--muted); }}
/* Panel: the pack's scrap: its fill, line, corner and shadow. */
.panel {{ border: var(--hair) solid var(--panel-line); background: var(--panel-fill); color: var(--panel-ink);
  border-radius: var(--radius); box-shadow: var(--shadow); padding: var(--pad); display: flex; flex-direction: column; gap: 8px; min-width: 0; }}
.panel.ink {{ background: var(--alt-fill); color: var(--alt-ink); border-color: var(--alt-fill); }}
.panel.tone {{ background-image: radial-gradient(circle, var(--foreground) 1px, transparent 1.2px); background-size: 6px 6px; }}
.panel > .title {{ display: flex; justify-content: space-between; align-items: baseline; gap: 8px; }}
.panels {{ display: grid; gap: var(--gap); grid-template-columns: repeat(auto-fit, minmax(min(100%, 200px), 1fr)); }}
/* Actions are bracketed words in the body face: one line, never wrapped. Primary = red, secondary = ink. */
.action {{ font: var(--body-size)/1.2 var(--font-body); color: var(--accent); background: none; border: 0; padding: 10px 2px;
  min-height: 44px; white-space: nowrap; cursor: pointer; }}
.action::before {{ content: "[ "; }} .action::after {{ content: " ]"; }}
.action.secondary {{ color: var(--foreground); }}
.action:focus-visible, .option:focus-visible, .field:focus-within {{ outline: var(--focus); outline-offset: 2px; }}
.actions {{ display: flex; flex-wrap: wrap; gap: 4px 16px; align-items: center; }}
/* Tab chips: mono capitals; the one showing is solid ink. */
.tabs {{ display: flex; flex-wrap: wrap; border: var(--hair) solid var(--border); border-radius: var(--radius); overflow: hidden; width: fit-content; max-width: 100%; }}
.tab {{ font: var(--mono-weight) 11px/1 var(--font-mono); text-transform: var(--mono-case); letter-spacing: var(--mono-tracking);
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
.field input, .field textarea {{ font: var(--body-size)/1.4 var(--font-body); color: var(--foreground); background: var(--background);
  border: var(--hair) solid var(--border); border-radius: var(--radius); padding: 10px 12px; min-height: 44px; width: 100%; }}
/* Option buttons (a question with choices): ruled boxes, the chosen one solid ink. */
.options {{ display: flex; flex-direction: column; gap: 8px; }}
.option {{ text-align: left; font: var(--body-size)/1.35 var(--font-body); color: var(--foreground); background: var(--background);
  border: var(--hair) solid var(--border); border-radius: var(--radius); padding: 12px 14px; min-height: 48px; width: 100%; }}
.option.on {{ background: var(--foreground); color: var(--background); }}
.option small {{ display: block; color: var(--muted); font-size: 13px; }}
.option.on small {{ color: var(--background); opacity: .75; }}
.badge {{ font: var(--mono-weight) 10px/1 var(--font-mono); text-transform: var(--mono-case); letter-spacing: var(--mono-tracking);
  background: var(--spot); color: var(--background); padding: 4px 6px; border-radius: var(--radius); }}
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
<meta name="soma-pack" content="{pack_id}">
<meta name="soma-reply-to" content="">
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
  <!-- Question form: replace the empty recipient with phone:A/B/C or a known mac: thread key.
       The owner's Submit tap delivers to this fixed recipient without a second review. -->
  <section class="panel">
    <h2>Your answer</h2>
    <form id="soma-question-form">
      <p class="caption">Choose one, or write your own answer.</p>
      <button class="option" type="button" data-answer="approve" aria-pressed="false"><b>Approve this design</b><small>Build the screens shown in this mockup.</small></button>
      <button class="option" type="button" data-answer="changes" aria-pressed="false"><b>Request changes</b><small>Describe the changes in Other.</small></button>
      <label class="field">Other<textarea name="answer" maxlength="4096" rows="3"></textarea></label>
      <div class="actions"><button class="action" type="submit">Submit answers</button></div>
      <p class="caption" id="soma-reply-status" role="status"></p>
    </form>
  </section>
</main>
<script>
let picked = '';
document.querySelectorAll('[data-answer]').forEach(function(button) {{
  button.onclick = function() {{
    picked = button.dataset.answer;
    document.querySelectorAll('[data-answer]').forEach(function(option) {{
      option.classList.toggle('on', option === button);
      option.setAttribute('aria-pressed', String(option === button));
    }});
  }};
}});
document.getElementById('soma-question-form').addEventListener('submit', function(event) {{
  event.preventDefault();
  const other = new FormData(event.target).get('answer').trim();
  const text = [picked, other].filter(Boolean).join(': ');
  const status = document.getElementById('soma-reply-status');
  if (!text) return;
  if (window.soma && typeof window.soma.answer === 'function') {{
    window.soma.answer({{decision: picked || "other", other: other}});
    status.textContent = 'Sending answers…';
    event.target.querySelectorAll('button,input,textarea').forEach(function(field) {{ field.disabled = true; }});
  }} else status.textContent = 'Reply delivery is unavailable in this preview. Open it in updated Soma.';
}});
window.addEventListener('soma-answer-result', function(event) {{
  document.getElementById('soma-reply-status').textContent = event.detail.message;
}});
</script></body></html>
"""
    open(os.path.join(out_dir, "template.html"), "w").write(template)

    swatches = "".join(f'<div class="sw"><i style="background:var(--{k.replace("_", "-")})"></i><span class="caption">{k}</span><code>{v}</code></div>'
                       for k, v in tokens.items())
    page = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="soma-pack" content="{pack_id}">
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
<header><div class="caption">The active look: {pack['pack']['name']}</div><h1>Soma design system</h1>
<p class="hand">every mockup copies this, and looks before it sends</p></header>

<section class="panel"><h2>Colour</h2><div class="swatches">{swatches}</div>
<p class="muted">Roles, not colours: accent for actions and the one thing that needs you, red for warnings, green for done, muted for details. Each look fills them in its own way.</p></section>

<section class="panel"><h2>Type</h2>
<div class="display" style="font-size:30px">Display: big titles, numbers</div>
<p>Body: everything you read, at the look's body size; bold for names.</p>
<div class="caption">Caption: mono capitals, labels and tabs</div>
<div class="hand">Hand: narration, asides, empty states</div></section>

<section class="panel"><h2>Panels</h2>
<div class="panels">
<div class="panel"><div class="title"><h2>Panel</h2><span class="caption">default</span></div><p>The look's card: its fill, line, corner and shadow.</p></div>
<div class="panel ink"><div class="title"><h2>Emphasis</h2></div><p>The look's emphasised card (its alt colours).</p></div>
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
<div class="row"><span class="warn">Don't</span><span>hard-coded colours or fonts, web fonts, CDNs</span></div></div></section>
</main></body></html>
"""
    open(os.path.join(out_dir, "design-system.html"), "w").write(page)

    tok_rows = "\n".join(f"| `--{k.replace('_', '-')}` | `{v}` | `{pal.get(k, '')}` |" for k, v in tokens.items())
    face = lambda v: (os.path.splitext(os.path.basename(pack["type"].get(v, {}).get("file") or ""))[0] or pack["type"].get(v, {}).get("family", "system"))
    md = f"""# Soma design system

Generated by `tools/design-system/build.py` from the **active look's** pack. Soma has switchable looks (theme
packs in `app/src/main/assets/packs/`); this file, `design/soma/soma.css`, `design/soma/template.html` and
`design/soma/design-system.html` describe whichever is picked. Generated now from **{pack['pack']['name']}**
(`{pack_id}`). When Boss picks another look, rerun `python3 tools/design-system/build.py` (it asks the phone which
look is active) and re-look any mockup you're still working on. Don't edit the generated files by hand.

## How it works: roles, mapped to the active pack

Mockups use **roles** (classes and CSS variables), never raw colours or font names. The generator fills each role
from the pack:

| Role | CSS | From the pack |
|---|---|---|
| Page | `--background`, `--foreground` | `[palette] background / foreground` |
| Card / panel | `.panel`, `--panel-fill`, `--panel-line`, `--hair`, `--radius`, `--shadow` | `[scrap] fill, border_color, border`, `[shape] radius`, `[scrap.shadow]` |
| Emphasised card | `.panel.ink`, `--alt-fill`, `--alt-ink` | `[scrap.alt]`, `[palette] alt / on_alt` |
| Title | `h1`, `h2`, `.display` | `[type.display]` |
| Body | `body`, `p`, `--body-size` | `[type.body]`, `[type.size] body` |
| Label / caption | `.caption`, `.label` | `[type.mono]` (weight, case, tracking) |
| Aside | `.hand` | `[type.hand]` |
| Chip / tab | `.tabs > .tab`, `.on` for the one showing | `[type.mono]`, palette ink |
| List row | `.rows > .row` | `--hair`, palette |
| Form field | `.field` | `--hair`, `--radius` |
| Option button | `.options > .option`, `.on` chosen | `--hair`, `--radius`, palette |
| Primary action | `.action` → `[ Label ]` in the accent | `[palette] accent` |
| Secondary action | `.action.secondary` → `[ Label ]` in ink | `[palette] foreground` |
| Badge | `.badge` | `[palette] spot` (or accent) |
| Focus | `:focus-visible` | `[scrap] focus_color, focus_width` |

Actions are Soma's bracketed words in every look (`[ Approve ]`, one line, never wrapped, at least 44 px tall).

## The active look: {pack['pack']['name']}

{lead_comment(os.path.join(pack_dir, 'pack.toml'))}

| Token | CSS | Pack |
|---|---|---|
{tok_rows}

| Voice | Face |
|---|---|
| Display | {face('display')} |
| Body | {face('body')} |
| Mono | {face('mono')} |
| Hand | {face('hand')} |

Card line {hair} px, corner {radius} px, shadow `{shadow}`. The faces are embedded in `soma.css` as data URLs
(Latin only), so a mockup is one offline file; a look that uses system fonts embeds none.

## Artifact question replies

Generated HTML opts in with `<meta name="soma-reply-to" content="phone:B">` in its head.
Use one fixed `phone:A`, `phone:B`, `phone:C`, or the exact known `mac:` thread key.
The declared recipient is untrusted until Boss reviews it. An artifact must never
choose a different recipient from JavaScript or claim that answers were delivered.

The template's Submit calls `soma.answer(answers)` on Boss's tap. That Submit tap is
consent to send directly to the fixed recipient. No second review, approval card,
or navigation away from the mockup. Soma delivers a normal message to the exact
known thread/builder and reports `soma-answer-result` with `sent` and `message`.
The page shows “Sent to <agent>” only after confirmed delivery; an offline browser
has no bridge and explains that. A failed/uncertain delivery is never called sent.

Put questions inside the mockup (a section or Questions tab), with options, Other,
and one Submit. Without a mockup, use the same question block as its own artifact.
Pass an object of question keys to selected labels/arrays or Other text. A plain
string remains supported. Use `decision: "approve"` for a mockup's Approve choice.
On an approval answer, Soma signs a native receipt binding the exact HTML hash,
integer version, answers and fixed recipient with a non-exportable Android Keystore P-256 key. The agent receives
`canvasReceipt` (the exact signed JSON string plus DER signature) and
`receiptStatus` (registered / signed-offline / unlinked), plus
`canvasReceiptId` when Canvas answered. With no confirmed linked device, answers
still deliver but contain no approval proof. Native never stores an owner key.

Link once through Canvas's owner menu in Soma's artifact panel: `await
window.somaReceipts.publicKey()` returns only SPKI base64 and deviceId on a
main-frame owner gesture at the configured CANVAS_URL origin. Canvas does the
owner-authenticated registration. No signing call is available to the page.
Agents use a verified receipt with `soma-canvas record-approved`; they cannot
mint proof themselves. The local submission UUID only prevents duplicate sends.
Canvas approvals are separate; `soma.approve` is parked and absent from the template.

Generated HTML gets no file/content access, credentials or general native API.
Live URLs cannot opt in. Main-frame gesture and per-version token checks apply;
missing/duplicate declarations, unknown recipients and control characters fail
closed. Replies are nonblank and at most 4096 characters. Reloads/double taps of
the same source/version/answer cannot silently deliver again. Delivery errors remain
visible in the page and agent chat. No background scripts or subframes can submit.

Native AskUserQuestion forms use their nonce-bound approval answers instead;
they do not send a second chat message through the artifact bridge.

## The Fold

| Screen | Device px | CSS px (420 dpi) | Where an artifact goes |
|---|---|---|---|
| Cover | 1248 × 1972 | 475 × 751 | full screen |
| Unfolded | 2448 × 1848 | 932 × 704 | the side panel beside the chat, ≈466 × 704 |

Design for both; the side panel is the narrowest real width a mockup gets unfolded, and the cover is the tallest.

## Do and don't

- Do start from `design/soma/template.html` and use only the roles above; let text wrap and size by content.
- Do keep everything in one offline file: `soma.css` inlined, no CDN, no web fonts, no network calls.
- Do look before sending: `soma-look FILE` renders it at cover, unfolded and side-panel sizes, prints the PNGs and
  warns on overlapping or clipped text, horizontal overflow, empty (zero-height) content, network references, and a
  file made for a different look than the active one. `soma-canvas propose` refuses a file that hasn't been looked at.
- Don't hard-code colours or font names, use fixed heights that can come out zero, scale-to-fit
  (`transform: scale` from a measured height), or hover-only controls.
"""
    open(os.path.join(ROOT, "docs/DESIGN-SYSTEM.md"), "w").write(md)
    print("wrote design/soma/soma.css, template.html, design-system.html, docs/DESIGN-SYSTEM.md")
    if missing:
        print("WARNING: no fonttools (pyftsubset): faces left out for", ", ".join(missing), file=sys.stderr)


if __name__ == "__main__":
    a = argparse.ArgumentParser()
    a.add_argument("--pack", default="auto")
    pid = a.parse_args().pack
    if pid == "auto":
        pid = active_pack() or "manga"
        print("active look:", pid)
    build(pid)
