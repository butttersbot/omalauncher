#!/usr/bin/env python3
"""Patterns in the keyboard practice tap log (~/.local/share/oma-kb/taps.jsonl, written by design/preview-server.py).

Only taps made while the typed text still matched the phrase count, so each has a known intended key.
Prints, per layout: the most common slips (wanted -> got), each letter's slip rate, and where taps land
relative to the centre of the intended key (dp; +x is right, +y is down), overall and per row.
"""
import collections
import json
import pathlib
import statistics
import sys

LOG = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else pathlib.Path.home() / '.local/share/oma-kb/taps.jsonl')
ROWS = ['qwertyuiop', 'asdfghjkl', 'zxcvbnm']


def row_of(c):
    for i, r in enumerate(ROWS):
        if c in r:
            return f'row {i + 1}'
    return 'space' if c == ' ' else None


taps = [json.loads(l) for l in LOG.read_text().splitlines() if l.strip()]
by_layout = collections.defaultdict(list)
for t in taps:
    if t['want'] != '⌫' and t.get('wx') is not None:
        by_layout[t['layout']].append(t)

for layout, ts in by_layout.items():
    want = lambda t: t['want'].lower()
    got = lambda t: t['got'].lower()
    print(f'\n=== {layout}: {len(ts)} aimed taps, {len(set(t["phrase"] for t in ts))} phrases ===')
    variants = collections.Counter(f"{t['keys']} · return {t['ret'].upper()}" + ('' if layout == 'cover' else f" · {t['split']}") for t in ts)
    for v, n in variants.most_common():
        miss = sum(1 for t in ts if f"{t['keys']} · return {t['ret'].upper()}" + ('' if layout == 'cover' else f" · {t['split']}") == v and want(t) != got(t))
        print(f'  {v}: {n} taps, {miss} slips ({100 * miss / n:.1f}%)')

    slips = collections.Counter((want(t), got(t)) for t in ts if want(t) != got(t))
    print('\n  Most common slips (wanted -> got):')
    for (w, g), n in slips.most_common(15):
        print(f'    {w!r:>5} -> {g!r:<5} {n}')

    per = collections.defaultdict(lambda: [0, 0])
    for t in ts:
        per[want(t)][0] += 1
        per[want(t)][1] += want(t) != got(t)
    print('\n  Slip rate by intended key (keys with 5+ taps, worst first):')
    for k, (n, m) in sorted(per.items(), key=lambda kv: -kv[1][1] / kv[1][0]):
        if n >= 5 and m:
            print(f'    {k!r:>5}: {m}/{n} ({100 * m / n:.0f}%)')

    def offsets(group):
        dx = [t['x'] - t['wx'] for t in group]
        dy = [t['y'] - t['wy'] for t in group]
        return statistics.mean(dx), statistics.mean(dy), statistics.pstdev(dx), statistics.pstdev(dy)

    print('\n  Where taps land vs the intended key centre (mean dx, dy; spread sx, sy) in dp:')
    groups = collections.defaultdict(list)
    for t in ts:
        r = row_of(want(t))
        if r:
            groups[r].append(t)
            groups['left half' if r != 'space' and ROWS[int(r[-1]) - 1].index(want(t)) < len(ROWS[int(r[-1]) - 1]) / 2 else 'right half'].append(t)
    for name in ['row 1', 'row 2', 'row 3', 'space', 'left half', 'right half']:
        if len(groups[name]) >= 3:
            dx, dy, sx, sy = offsets(groups[name])
            print(f'    {name:<10} n={len(groups[name]):<4} dx={dx:+5.1f} dy={dy:+5.1f}  sx={sx:4.1f} sy={sy:4.1f}')
    print('\n  Per key (10+ taps):')
    keyg = collections.defaultdict(list)
    for t in ts:
        keyg[want(t)].append(t)
    for k in sorted(keyg, key=lambda k: (row_of(k) or 'z', k)):
        if len(keyg[k]) >= 10:
            dx, dy, sx, sy = offsets(keyg[k])
            print(f'    {k!r:>5} n={len(keyg[k]):<4} dx={dx:+5.1f} dy={dy:+5.1f}  sx={sx:4.1f} sy={sy:4.1f}')

    fixes = sum(1 for t in taps if t['layout'] == layout and t['got'] == '⌫')
    early = sum(1 for t in taps if t['layout'] == layout and t['got'] == '⏎' and t['want'] != '⏎')
    print(f'\n  ⌫ presses: {fixes}; return pressed before the phrase was done: {early}')
