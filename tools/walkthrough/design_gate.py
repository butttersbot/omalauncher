#!/usr/bin/env python3
"""The design gate's walk side (design/soma-canvas/DESIGN-GATE.md; Boss, 2026-10-29: "What is the point of having an
approved design if the agents keep handing back work that doesn't follow the approved design").

    design_gate.py run WDIR --version V --build SHORT [--walks a,b]   after the walks, on the Mac lane
    design_gate.py gate REPORT [--build SHORT]                       before take/install, on the phone
    design_gate.py gate --inbox --build COMMIT --version CODE        the same, with the report the lane Taildropped

run: for each walk in WDIR that PASSed (verdicts.txt) and has a canvas.json (walk.py writes it from `shot NAME
card=ID`), publish its screenshots as Shipped (`soma-canvas publish APP DIR`) and check each carded one against
Approved (`soma-canvas check CARD SHOT`). Writes WDIR/design-report.json and prints a DESIGN FAIL block when a card
drifted from Approved. A failed walk publishes nothing: Shipped is only what passed.

gate: exit 3 and list the cards when the report has drift Boss has not waived (asked of Canvas again now, since he
may approve or waive after the walk, in chat or on Canvas); exit 0 otherwise. Neither of these blocks, but each is
printed so nobody calls the build done without saying so (DESIGN-GATE.md amendment, 2026-10-29: "I don't want things
to be blocked if I forgot to click a few approve buttons"):
  DESIGN UNAPPROVED  a carded screen with nothing Approved: ask Boss once, one native question card for the batch;
  DESIGN UNCHECKED   a check that could not run, or no report.
"""
import json, os, shutil, subprocess, sys

def canvas_cli():
    for c in (os.environ.get("SOMA_CANVAS"), shutil.which("soma-canvas"),
              os.path.expanduser("~/ClaudeSync/projects/soma-canvas/bin/soma-canvas")):
        if c and os.access(c, os.X_OK):
            return c
    return None

def call(args, timeout=300):
    """Runs soma-canvas; returns (exit code, stdout, stderr). A missing CLI or a hang is exit 127/124."""
    cli = canvas_cli()
    if not cli:
        return 127, "", "soma-canvas not found"
    try:
        p = subprocess.run([cli] + args, capture_output=True, text=True, timeout=timeout)
        return p.returncode, p.stdout, p.stderr
    except subprocess.TimeoutExpired:
        return 124, "", f"soma-canvas {args[0]} took over {timeout} s"

def parse_check(card, code, out, err):
    """One check's answer as the report keeps it (contract mailed to taebc3f66, 2026-10-29)."""
    try:
        r = json.loads(out.strip().splitlines()[-1]) if out.strip() else None
    except (ValueError, IndexError):
        r = None
    if not isinstance(r, dict) or r.get("result") not in ("match", "drift", "no-approved", "error"):
        why = (err or out).strip().splitlines()[-1:] or [f"exit {code}"]
        return {"card": card, "result": "error", "error": f"check unavailable: {why[0][:200]}"}
    r.setdefault("card", card)
    r.setdefault("differences", [])
    r["waived"] = bool(r.get("waived"))
    return r

def canvas_app(walk, results):
    """The Canvas app a walk's cards belong to. walk.py writes it into results.json (canvasApp) from the script's
    '# canvas-app: X'; older output has only the script's path, which is the walking machine's (a lane's zip read on the
    Fold, 2026-10-29: connections fell back to 'soma' and publish 404'd), so that path is tried, then the script of
    that name in this checkout. Else hunch* walks are hunch, the rest soma."""
    if results.get("canvasApp"):
        return results["canvasApp"]
    here = os.path.join(os.path.dirname(os.path.abspath(__file__)), "scripts", walk + ".walk")
    for path in (results.get("script", ""), here):
        try:
            for line in open(path, encoding="utf-8"):
                if line.strip().startswith("# canvas-app:"):
                    return line.split(":", 1)[1].strip()
        except OSError:
            pass
    return "hunch" if walk.startswith("hunch") else "soma"

def passed(wdir):
    try:
        return {l.split()[0] for l in open(os.path.join(wdir, "verdicts.txt")) if l.split()[1:2] == ["PASS"]}
    except OSError:
        return set()

def run(wdir, version, build, walks=None):
    ok = passed(wdir)
    report = {"build": build, "version": version, "walks": [], "cards": []}
    for w in sorted(walks or os.listdir(wdir)):
        d = os.path.join(wdir, w)
        cmap = os.path.join(d, "canvas.json")
        if not os.path.isfile(cmap):
            continue
        if w not in ok:
            report["walks"].append({"walk": w, "published": False, "why": "walk did not PASS"}); continue
        cards = json.load(open(cmap))
        results = json.load(open(os.path.join(d, "results.json"))) if os.path.isfile(os.path.join(d, "results.json")) else {}
        app = canvas_app(w, results)
        code, out, err = call(["publish", app, d, "--version", version, "--build", build, "--source", f"walk {w} {build}"])
        report["walks"].append({"walk": w, "app": app, "published": code == 0,
                                **({} if code == 0 else {"why": (err or out).strip()[-300:]})})
        for stem, card in cards.items():
            card = card if isinstance(card, str) else card.get("card", stem)
            code, out, err = call(["check", card, os.path.join(d, stem + ".png"), "--build", build, "--version", version])
            report["cards"].append({**parse_check(card, code, out, err), "walk": w, "shot": stem + ".png"})
    report["result"] = verdict(report["cards"])
    json.dump(report, open(os.path.join(wdir, "design-report.json"), "w"), indent=1)
    print(summary(report))
    return report

def blocking(c):
    """Drift from Approved that Boss hasn't waived: the builder's to fix, only his to waive."""
    return c["result"] == "drift" and not c["waived"]

def unapproved(c):
    return c["result"] == "no-approved" and not c["waived"]

def verdict(cards):
    if any(blocking(c) for c in cards):
        return "fail"
    if any(c["result"] == "error" for c in cards):
        return "unchecked"
    if any(unapproved(c) for c in cards):
        return "unapproved"
    return "match" if cards else "none"

def summary(report):
    cards = report["cards"]
    lines = []
    drift = [c for c in cards if c["result"] == "drift" and not c["waived"]]
    unappr = [c for c in cards if unapproved(c)]
    unchecked = [c for c in cards if c["result"] == "error"]
    if drift:
        lines.append(f"DESIGN FAIL: {len(drift)} of {len(cards)} walked cards are not as approved on Canvas:")
        for c in drift:
            lines.append(f"  {c['card']} ({c.get('walk', '?')}, {c.get('shot', '?')}):")
            lines += [f"    {d.get('n', i)}. {d.get('what', d)}" for i, d in enumerate(c["differences"], 1)]
    if unappr:
        lines.append(f"DESIGN UNAPPROVED: {', '.join(c['card'] for c in unappr)} have nothing Approved on Canvas "
                     "(not blocking): ask Boss once, one native question card for the whole batch, with the Canvas link; "
                     "record his yes with: soma-canvas approve-from-chat CARD... --receipt R.json (R.json from oma receipt last-answer)")
    if unchecked:
        lines.append(f"DESIGN UNCHECKED: {', '.join(c['card'] for c in unchecked)} could not be checked against Approved; "
                     "this build is not done on design until they are")
        lines += [f"  {c['card']}: {c.get('error', 'error')}" for c in unchecked]
    # Characters a device font drew because no font in the theme's store set has them (Canvas's check, fontFallback).
    # Never drift and never blocking; printed so it gets fixed (Boss, 2026-10-29: one font store, same on every device).
    fell = [c for c in cards if c.get("fontFallback")]
    if fell:
        lines.append(f"FONT FALLBACK: {', '.join(c['card'] for c in fell)} draw characters in a device font "
                     "(not in the theme's fonts on Canvas):")
        for c in fell:
            lines += [f"  {c['card']}: {f.get('chars', '?')} in {f.get('font', '?')}" + (f" ({f['where']})" if f.get("where") else "")
                      for f in c["fontFallback"][:6]]
    if cards and not (drift or unappr or unchecked):
        lines.append(f"Design: all {len(cards)} walked cards match Approved (or Boss waived them).")
    for c in cards:
        if c["result"] in ("drift", "no-approved") and c["waived"]:
            lines.append(f"  {c['card']}: {'drift' if c['result'] == 'drift' else 'not approved'}, waived by Boss")
    for w in report["walks"]:
        if w.get("published") is False and w.get("why") != "walk did not PASS":
            lines.append(f"  {w['walk']}: publish failed ({w['why'][:150]})")
    return "\n".join(lines) or "Design: no walked screenshot names a Canvas card."

def inbox_report(build, version, inbox="/root/Inbox"):
    """The lane sends design-<short>-<code>.json beside the walk zip (soma-macbuild); Taildrop may add ' (2)'."""
    import glob
    found = sorted(glob.glob(os.path.join(inbox, f"design-{build[:9]}-{version}*.json")), key=os.path.getmtime)
    return found[-1] if found else None

def gate(path, build=None):
    if not path:
        print("DESIGN UNCHECKED: no design report for this build (no walk names a Canvas card, or its walks are still "
              "running); nothing to hold it on, but nothing says it is as approved either.")
        return 0
    report = json.load(open(path))
    if build and report.get("build") and not build.startswith(report["build"][:7]) and not report["build"].startswith(build[:7]):
        print(f"DESIGN UNCHECKED: design report {path} is for {report['build']}, not {build}: no design verdict for this build.")
        return 0
    cards = report.get("cards", [])
    for i, c in enumerate(cards):
        if not (blocking(c) or unapproved(c)):
            continue
        # Boss may have approved the card or waived it on Canvas since the walk: ask again (the stored Shipped of
        # this build, no new screenshot). Whatever Canvas says now replaces the walk's answer, unless it can't answer.
        code, out, err = call(["check", c["card"], "--waiver", "--build", report.get("build", "")], timeout=60)
        now = parse_check(c["card"], code, out, err)
        if now["result"] != "error":
            cards[i] = {**c, **now}
    blocked = [c for c in cards if blocking(c)]
    print(summary({**report, "cards": cards, "walks": []}))
    if not blocked:
        return 0
    print("Refused: build it as Approved, or Boss waives the difference (in chat or on Canvas) (" +
          ", ".join(c["card"] for c in blocked) + "). His waiver in chat: soma-canvas waive-from-chat CARD --item N "
          "--build SHA --receipt R.json (R.json from oma receipt; the message he answered must name the card and N).")
    return 3

def main(a):
    if len(a) >= 2 and a[0] == "run":
        opt = lambda k: a[a.index(k) + 1] if k in a else None
        walks = [w for w in (opt("--walks") or "").split(",") if w] or None
        run(a[1], opt("--version") or "", opt("--build") or "", walks)
        return 0
    if len(a) >= 2 and a[0] == "gate":
        build = a[a.index("--build") + 1] if "--build" in a else None
        if a[1] == "--inbox":
            return gate(inbox_report(build or "", a[a.index("--version") + 1] if "--version" in a else ""), build)
        return gate(a[1], build)
    print(__doc__); return 2

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
