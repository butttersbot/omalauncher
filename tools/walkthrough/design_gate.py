#!/usr/bin/env python3
"""The design gate's walk side (design/soma-canvas/DESIGN-GATE.md; Boss, 2026-10-29: "What is the point of having an
approved design if the agents keep handing back work that doesn't follow the approved design").

    design_gate.py run WDIR --version V --build SHORT [--walks a,b]   after the walks, on the Mac lane
    design_gate.py gate REPORT [--build SHORT]                       before take/install, on the phone
    design_gate.py gate --inbox --build COMMIT --version CODE        the same, with the report the lane Taildropped

run: for each walk in WDIR that PASSed (verdicts.txt) and has a canvas.json (walk.py writes it from `shot NAME
card=ID`), publish its screenshots as Shipped (`soma-canvas publish APP DIR`) and check each carded one against
Approved (`soma-canvas check CARD SHOT`). Writes WDIR/design-report.json and prints a DESIGN FAIL block when a card
drifted. A failed walk publishes nothing: Shipped is only what passed.

gate: exit 3 and list the cards when the report has drift Boss has not waived on Canvas (asked again now, since he
may waive after the walk); exit 0 otherwise. A check that could not run (error) is shown but does not block: only a
real drift does.
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
    """The Canvas app a walk's cards belong to: '# canvas-app: X' in its script, else hunch* walks are hunch."""
    try:
        for line in open(results.get("script", ""), encoding="utf-8"):
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

def verdict(cards):
    if any(c["result"] == "drift" and not c["waived"] for c in cards):
        return "drift"
    if any(c["result"] == "error" for c in cards):
        return "error"
    return "match" if cards else "none"

def summary(report):
    cards = report["cards"]
    lines = []
    drift = [c for c in cards if c["result"] == "drift" and not c["waived"]]
    if drift:
        lines.append(f"DESIGN FAIL: {len(drift)} of {len(cards)} walked cards are not as approved on Canvas:")
        for c in drift:
            lines.append(f"  {c['card']} ({c.get('walk', '?')}, {c.get('shot', '?')}):")
            lines += [f"    {d.get('n', i)}. {d.get('what', d)}" for i, d in enumerate(c["differences"], 1)]
    elif cards:
        lines.append(f"Design: {sum(c['result'] in ('match', 'drift') for c in cards)} of {len(cards)} walked cards checked; none drifted unwaived.")
    for c in cards:
        if c["result"] == "drift" and c["waived"]:
            lines.append(f"  {c['card']}: drift waived by Boss on Canvas")
        elif c["result"] == "no-approved":
            lines.append(f"  {c['card']}: nothing Approved yet (published as Shipped only)")
        elif c["result"] == "error":
            lines.append(f"  {c['card']}: not checked ({c.get('error', 'error')}); not blocking")
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
        print("Design: no design report for this build yet (its walks may still be running); nothing to hold it on.")
        return 0
    report = json.load(open(path))
    if build and report.get("build") and not build.startswith(report["build"][:7]) and not report["build"].startswith(build[:7]):
        print(f"Design report {path} is for {report['build']}, not {build}: no design verdict for this build.")
        return 0
    blocked = []
    for c in report.get("cards", []):
        if c["result"] != "drift" or c["waived"]:
            continue
        # Boss may have waived it on Canvas since the walk: ask again (no vision call, the stored check only).
        code, out, err = call(["check", c["card"], "--waiver", "--build", report.get("build", "")], timeout=60)
        now = parse_check(c["card"], code, out, err)
        if now["result"] != "error" and (now["waived"] or now["result"] == "match"):
            c["waived"] = True; continue
        blocked.append(c)
    if not blocked:
        print(f"Design: {len(report.get('cards', []))} walked cards, no unwaived drift.")
        return 0
    print(summary({**report, "cards": blocked, "walks": []}))
    print("Refused: build it as Approved, or Boss waives the drift on Canvas (" +
          ", ".join(c["card"] for c in blocked) + ").")
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
