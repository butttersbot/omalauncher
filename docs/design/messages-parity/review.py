#!/usr/bin/env python3
"""Make one offline review file from a manifest of synthetic emulator evidence.

Usage: review.py MANIFEST --evidence DIR --out messages-parity-group-2.html
Images and native recordings are embedded; no fonts, scripts or media are fetched.
"""
import argparse
import html as html_std
import base64
import json
import mimetypes
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('manifest', type=Path)
p.add_argument('--evidence', type=Path, required=True)
p.add_argument('--out', type=Path, required=True)
p.add_argument('--group', type=int, default=2)
p.add_argument('--revision', type=int, default=1)
p.add_argument('--title', default='Soma Messages: gestures and response')
p.add_argument('--limits')
a = p.parse_args()
cards = json.loads(a.manifest.read_text())
for card in cards:
    for key in ('image', 'after', 'clip'):
        if key not in card:
            continue
        path = (a.evidence / card[key]).resolve()
        if not path.is_relative_to(a.evidence.resolve()):
            raise ValueError('Evidence must stay inside its directory')
        mime = mimetypes.guess_type(path.name)[0]
        card[key] = f'data:{mime};base64,' + base64.b64encode(path.read_bytes()).decode()
payload = json.dumps(cards).replace('<', '\\u003c')
html = '''<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Messages · group 2 review</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#fdfdfb;color:#111113;font:16px system-ui,sans-serif}
header{padding:20px 24px 12px;border-bottom:2px solid #111113}h1{font-size:26px;margin:0 0 8px}p{line-height:1.5;margin:8px 0}
.label{font:12px ui-monospace,monospace;letter-spacing:.08em;color:#d7262d}main{display:grid;grid-template-columns:minmax(220px,320px) minmax(0,1fr);min-height:78vh}select{display:none;font:inherit;padding:10px;border:1px solid #111113;background:#fdfdfb;color:#111113;max-width:100%;margin-top:12px}summary{cursor:pointer}
nav{padding:16px;border-right:1px solid #111113;display:flex;flex-direction:column;gap:6px}button{font:inherit;text-align:left;background:transparent;border:1px solid transparent;padding:12px;cursor:pointer;color:inherit}
button[aria-current=true]{border-color:#111113;background:#111113;color:#fdfdfb}button:focus-visible{outline:3px solid #d7262d;outline-offset:2px}
article{padding:20px;min-width:0}h2{margin:0 0 6px;font-size:22px}.id{font:12px ui-monospace,monospace;overflow-wrap:anywhere;color:#666}
.stage{display:flex;justify-content:center;gap:20px;flex-wrap:wrap;margin-top:18px}img,video{display:block;max-height:72vh;max-width:100%;width:auto;object-fit:contain;border:1px solid #aaa;background:#fdfdfb}
figure{margin:0;max-width:100%}figcaption{font-size:13px;color:#666;margin:8px 0}.note{max-width:850px;color:#555;font-size:14px}
@media(max-width:700px){main{display:block}nav{display:none}select{display:block;width:100%}header,article{padding:16px}img,video{max-height:65vh}}
</style>
<header><div class="label">PROPOSED · PARITY-G2-V1 · EMULATOR DEMO</div><h1>Soma Messages: gestures and response</h1>
<p>Keep the active Manga look. Review the behavior and visible states in one batch.</p>
<details class="note"><summary>Emulator demo · test limits</summary><p>Native captures from the dedicated Fold8 emulator using synthetic chats. Nothing installed on the phone. Pin and mute stay on this phone. Clips show behavior; their low capture rate cannot establish iPhone timing, hardware feel or 120 Hz parity. Exact motion tuning follows the restored iOS reference.</p></details><select aria-label="Review card"></select></header>
<main><nav aria-label="Review cards"></nav><article><h2></h2><div class="id"></div><p id="description"></p><div class="stage"></div></article></main>
<script>const cards=PAYLOAD;const nav=document.querySelector('nav'),stage=document.querySelector('.stage'),select=document.querySelector('select');
function show(i){const c=cards[i];document.querySelector('h2').textContent=c.title;document.querySelector('.id').textContent=c.id;
document.querySelector('#description').textContent=c.description;stage.replaceChildren();
const f=document.createElement('figure'),im=document.createElement('img');im.src=c.image;im.alt=c.title+' — actual synthetic emulator capture';f.append(im);const cap=document.createElement('figcaption');cap.textContent=c.frame==='unfolded'?'Unfolded size of the same behavior':'Cover display';f.append(cap);stage.append(f);
if(c.after){const af=document.createElement('figure'),ai=document.createElement('img');ai.src=c.after;ai.alt=c.title+' — successful retry';af.append(ai);const ac=document.createElement('figcaption');ac.textContent='After a successful retry';af.append(ac);stage.append(af)}
if(c.clip){const v=document.createElement('video');v.controls=true;v.playsInline=true;v.preload='metadata';v.src=c.clip;v.setAttribute('aria-label',c.title+' native recording');const vf=document.createElement('figure');vf.append(v);const vc=document.createElement('figcaption');vc.textContent='Native recording · replay with controls';vf.append(vc);stage.append(vf)}
nav.querySelectorAll('button').forEach((b,j)=>b.setAttribute('aria-current',String(i===j)));select.value=String(i);}
cards.forEach((c,i)=>{const b=document.createElement('button');b.textContent=c.title;b.onclick=()=>show(i);nav.append(b);const o=document.createElement('option');o.value=i;o.textContent=(i+1)+' / '+cards.length+' · '+c.title;select.append(o)});select.onchange=()=>show(Number(select.value));show(0);</script></html>'''
a.out.parent.mkdir(parents=True, exist_ok=True)
html = html.replace('group 2 review', f'group {a.group} review').replace('PARITY-G2-V1', f'PARITY-G{a.group}-V{a.revision}').replace('Soma Messages: gestures and response', html_std.escape(a.title))
if a.limits:
    start = html.index('<details class="note">')
    end = html.index('</details>', start) + len('</details>')
    html = html[:start] + '<details class="note"><summary>Emulator demo · test limits</summary><p>' + html_std.escape(a.limits) + '</p></details>' + html[end:]
a.out.write_text(html.replace('PAYLOAD', payload))
print(f'{a.out}: {len(cards)} review cards, {a.out.stat().st_size} bytes')
