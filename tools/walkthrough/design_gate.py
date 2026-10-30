#!/usr/bin/env python3
"""What a build's walks leave on Canvas. Nothing here ever holds a build (Boss, 2026-10-30: "We need to remove the blockers").

    design_gate.py run WDIR --version V --build SHORT [--walks a,b]   after the walks, on the Mac lane
    design_gate.py gate REPORT [--build SHORT]                       says what that found; always exit 0
    design_gate.py gate --inbox --build COMMIT --version CODE        the same, with the report the lane Taildropped

run: for each walk in WDIR that PASSed (verdicts.txt) and has a canvas.json (walk.py writes it from `shot NAME card=ID`):
one quick diff per carded screen that changed (one look, advisory, `soma-canvas check --quick`), then publish its
screenshots as Shipped. A failed walk publishes nothing: Shipped is only what passed. If a diff can't run, it says so.
"""
import concurrent.futures, json, os, shutil, subprocess, sys

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
        # Before the publish: a screen with no Approved is compared with what it was last time.
        shots = [(stem, card if isinstance(card, str) else card.get("card", stem)) for stem, card in cards.items()]
        def diff(shot):
            stem, card = shot
            code, out, err = call(["check", card, os.path.join(d, stem + ".png"), "--quick", "--build", build, "--version", version], timeout=120)
            return {**parse_check(card, code, out, err), "walk": w, "shot": stem + ".png"}
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            report["cards"] += list(pool.map(diff, shots))
        code, out, err = call(["publish", app, d, "--version", version, "--build", build, "--source", f"walk {w} {build}"])
        report["walks"].append({"walk": w, "app": app, "published": code == 0,
                                **({} if code == 0 else {"why": (err or out).strip()[-300:]})})
    json.dump(report, open(os.path.join(wdir, "design-report.json"), "w"), indent=1)
    print(summary(report))
    return report

def summary(report):
    cards = report["cards"]
    lines = []
    differs = [c for c in cards if c["result"] == "drift"]
    unchecked = [c for c in cards if c["result"] == "error"]
    if differs:
        lines.append(f"Design diff (advisory, holds nothing): {len(differs)} of {len(cards)} walked screens look different:")
        for c in differs:
            lines.append(f"  {c['card']}: " + "; ".join(str(d.get("what", d))[:120] for d in c["differences"][:3]))
    if unchecked:
        lines.append(f"Design diff couldn't run for {', '.join(c['card'] for c in unchecked)}; moved on.")
    if cards and not (differs or unchecked):
        lines.append(f"Design diff: nothing notable on {len(cards)} walked screens.")
    for w in report["walks"]:
        if w.get("published") is False and w.get("why") != "walk did not PASS":
            lines.append(f"  {w['walk']}: publish failed ({w['why'][:150]})")
    return "\n".join(lines) or "Design diff: no walked screenshot names a Canvas card."

def inbox_report(build, version, inbox="/root/Inbox"):
    """The lane sends design-<short>-<code>.json beside the walk zip (soma-macbuild); Taildrop may add ' (2)'."""
    import glob
    found = sorted(glob.glob(os.path.join(inbox, f"design-{build[:9]}-{version}*.json")), key=os.path.getmtime)
    return found[-1] if found else None

def gate(path, build=None):
    """Says what the build's diff found, and never holds anything: nothing on Canvas blocks a build (Boss, 2026-10-30)."""
    report = None
    if path:
        try:
            report = json.load(open(path))
        except (OSError, ValueError):
            pass
    if report and (not build or not report.get("build") or build.startswith(report["build"][:7]) or report["build"].startswith(build[:7])):
        print(summary({**report, "walks": []}))
    return 0

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
