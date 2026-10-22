#!/usr/bin/env python3
"""Walk a feature as a person would (Boss, 2026-10-22: "minimize these simple ui bugs which would be caught with a
simple test run using the feature like a human would"). Runs a .walk script's [emu] steps on an emulator over adb and
lists its [phone] steps for the check on the real Fold after promotion. See README.md.

    walk.py SCRIPT.walk [--serial emulator-5554] [--out DIR] [--phone]

--phone prints only the [phone] checklist. Exit 0 when every [emu] step passed, 1 otherwise; results.json and a
screenshot per `shot`/`expect` go to --out (default ./walk-out/<script>).
"""
import json, os, re, shlex, subprocess, sys, time, xml.etree.ElementTree as ET

def adb(serial, *args, text=True, check=False):
    r = subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=text, timeout=60)
    if check and r.returncode != 0:
        raise RuntimeError((r.stderr or r.stdout or "adb failed").strip()[:300])
    return r.stdout

def size(serial):
    m = re.search(r"(\d+)x(\d+)", adb(serial, "shell", "wm", "size").splitlines()[-1])
    return int(m.group(1)), int(m.group(2))

def density(serial):
    m = re.findall(r"(\d+)", adb(serial, "shell", "wm", "density"))
    return int(m[-1]) / 160 if m else 2.625

def point(serial, xs, ys):
    """'0.5' is a fraction of the screen, '540' pixels, '48dp' density-independent pixels from the top or left."""
    w, h = size(serial)
    def f(v, full):
        if v.endswith("dp"):
            return int(float(v[:-2]) * density(serial))
        return int(float(v) * full) if "." in v else int(v)
    return f(xs, w), f(ys, h)

def nodes(serial):
    adb(serial, "shell", "uiautomator", "dump", "/sdcard/walk.xml")
    raw = adb(serial, "shell", "cat", "/sdcard/walk.xml")
    try:
        root = ET.fromstring(raw[raw.find("<"):])
    except ET.ParseError:
        return []
    out = []
    for n in root.iter("node"):
        label = (n.get("text") or "") + "\n" + (n.get("content-desc") or "")
        b = re.findall(r"\d+", n.get("bounds") or "")
        if len(b) == 4:
            out.append((label, [int(v) for v in b]))
    return out

def find(serial, wanted, wait_ms=4000):
    """The centre of what says [wanted] on screen; a web page's nodes scrolled out of view don't count."""
    end = time.time() + wait_ms / 1000
    w, h = size(serial)
    w, h = max(w, h), max(w, h)  # rotated or not, a point inside the screen is inside this square
    while True:
        for label, (l, t, r, b) in nodes(serial):
            x, y = (l + r) // 2, (t + b) // 2
            if wanted.lower() in label.lower() and 0 <= x < w and 0 <= y < h and r > l and b > t:
                return x, y
        if time.time() > end:
            return None
        time.sleep(0.4)

def display(serial):
    """The screen in use: with two displays a plain screencap returns a warning, not a picture. The first
    SurfaceFlinger display is the inner screen, the second the cover (device state 0 = folded)."""
    ids = re.findall(r"Display (\d+)", adb(serial, "shell", "dumpsys", "SurfaceFlinger", "--display-id"))
    if len(ids) < 2:
        return None
    folded = adb(serial, "shell", "cmd", "device_state", "print-state").strip().endswith("0")
    return ids[1] if folded else ids[0]

def wake(serial):
    """An asleep or locked screen screenshots black: keep it on and unlocked (the emulator holds test data only)."""
    adb(serial, "shell", "input", "keyevent", "WAKEUP")
    adb(serial, "shell", "wm", "dismiss-keyguard")

def prepare(serial):
    adb(serial, "shell", "locksettings", "set-disabled", "true")
    adb(serial, "shell", "svc", "power", "stayon", "true")
    adb(serial, "shell", "settings", "put", "system", "screen_off_timeout", "1800000")
    # The emulator has a hardware keyboard, and Android hides soft keyboards while one is attached.
    adb(serial, "shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", "1")
    wake(serial)

def shot(serial, path):
    wake(serial)
    d = display(serial)
    cmd = ["adb", "-s", serial, "exec-out", "screencap", "-p"] + (["-d", d] if d else [])
    with open(path, "wb") as f:
        f.write(subprocess.run(cmd, capture_output=True, timeout=60).stdout)

def run_step(serial, op, args, out, n):
    """One [emu] step; returns (ok, note, screenshot or None)."""
    if op == "launch":
        adb(serial, "shell", "am", "start", "-S", "-W", "-n", args[0], check=True); time.sleep(1.5); return True, "", None
    if op == "url":
        adb(serial, "shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", args[0]); time.sleep(1.5); return True, "", None
    if op == "ime":
        adb(serial, "shell", "ime", "enable", args[0]); adb(serial, "shell", "ime", "set", args[0], check=True); return True, "", None
    if op == "tap":
        if len(args) == 2 and re.fullmatch(r"[\d.]+", args[0]) and re.fullmatch(r"[\d.]+", args[1]):
            x, y = point(serial, *args)
        else:
            # "A | B": whichever is on screen (Android's own dialogs word things differently by version).
            wanted = [w.strip() for w in " ".join(args).split("|")]
            # All the choices watched together, until one deadline: a WebView's labels come up 6-10 s after a fresh start.
            end, p = time.time() + 8, None
            while p is None and time.time() < end:
                p = next((q for q in (find(serial, w, wait_ms=0) for w in wanted) if q), None)
                if p is None:
                    time.sleep(0.4)
            if not p:
                return False, "nothing says " + " or ".join(f"“{w}”" for w in wanted), None
            x, y = p
        adb(serial, "shell", "input", "tap", str(x), str(y)); time.sleep(0.6); return True, "", None
    if op == "swipe":
        x1, y1 = point(serial, args[0], args[1]); x2, y2 = point(serial, args[2], args[3])
        ms = args[4] if len(args) > 4 else "350"
        adb(serial, "shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), ms); time.sleep(0.8); return True, "", None
    if op == "type":
        adb(serial, "shell", "input", "text", " ".join(args).replace(" ", "%s")); time.sleep(0.5); return True, "", None
    if op == "key":
        adb(serial, "shell", "input", "keyevent", "KEYCODE_" + args[0].upper()); time.sleep(0.6); return True, "", None
    if op == "scrollto":
        # Swipes up until TEXT is on screen (a WebView only reports what shows).
        wanted = " ".join(args)
        for _ in range(8):
            if find(serial, wanted, wait_ms=600):
                return True, "", None
            w, h = size(serial)
            adb(serial, "shell", "input", "swipe", str(w // 2), str(int(h * 0.75)), str(w // 2), str(int(h * 0.35)), "400"); time.sleep(0.7)
        return False, f"“{wanted}” never came on screen", None
    if op == "nolog":
        # The app must not have logged TEXT since the last clearlog (a flip back, a second change).
        wanted = " ".join(args)
        ok = wanted not in adb(serial, "logcat", "-d", "-s", "SomaWalk:I")
        return ok, "" if ok else f"the app logged “{wanted}”", None
    if op in ("log", "clearlog"):
        # The app's own account of canvas-drawn state (Log tag SomaWalk), for what has no text to read.
        if op == "clearlog":
            adb(serial, "logcat", "-c"); return True, "", None
        wanted = " ".join(args)
        end = time.time() + 4
        while time.time() < end:
            if wanted in adb(serial, "logcat", "-d", "-s", "SomaWalk:I"):
                return True, "", None
            time.sleep(0.4)
        return False, f"the app never logged “{wanted}”", None
    if op == "shell":
        adb(serial, "shell", *args, check=True); time.sleep(1); return True, "", None
    if op == "wait":
        time.sleep(int(args[0]) / 1000); return True, "", None
    if op == "rotate":
        adb(serial, "shell", "settings", "put", "system", "accelerometer_rotation", "0")
        # Rotation 0 is the screen's natural shape: the cover is upright, the open Fold's inner screen is wide.
        w, h = size(serial)
        turn = (args[0] == "landscape") != (w > h)
        adb(serial, "shell", "settings", "put", "system", "user_rotation", "1" if turn else "0"); time.sleep(1.5); return True, "", None
    if op == "fold":
        # The Fold profile's two screens: "folded" (cover) or "open" (inner).
        adb(serial, "emu", "fold" if args[0] == "folded" else "unfold"); time.sleep(2); wake(serial); return True, "", None
    if op in ("shot", "expect", "absent"):
        words = " ".join(args)
        name = re.sub(r"\W+", "-", words if op == "shot" else op)[:60]
        path = os.path.join(out, f"{n:02d}-{name}.png")
        shot(serial, path)
        if op == "shot":
            return None, "look at it", path
        found = find(serial, words, wait_ms=4000 if op == "expect" else 800) is not None
        ok = found if op == "expect" else not found
        return ok, "" if ok else (f"“{words}” not on screen" if op == "expect" else f"“{words}” still on screen"), path
    return False, f"unknown step “{op}”", None

def parse(path):
    steps = []
    for i, line in enumerate(open(path, encoding="utf-8"), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        m = re.match(r"\[(emu|phone)\]\s*(.*)", line)
        where, rest = (m.group(1), m.group(2)) if m else ("emu", line)
        if where == "phone":
            steps.append({"line": i, "where": "phone", "text": rest}); continue
        parts = shlex.split(rest)
        steps.append({"line": i, "where": "emu", "op": parts[0], "args": parts[1:], "text": rest})
    return steps

def main():
    a = sys.argv[1:]
    if not a:
        print(__doc__); sys.exit(2)
    script = a[0]
    serial = a[a.index("--serial") + 1] if "--serial" in a else "emulator-5554"
    out = a[a.index("--out") + 1] if "--out" in a else os.path.join("walk-out", os.path.splitext(os.path.basename(script))[0])
    steps = parse(script)
    if "--phone" in a:
        for s in steps:
            if s["where"] == "phone":
                print(f"[phone] {s['text']}")
        return
    os.makedirs(out, exist_ok=True)
    prepare(serial)
    results, failed = [], 0
    for n, s in enumerate(steps, 1):
        if s["where"] == "phone":
            results.append({"step": n, "where": "phone", "text": s["text"], "result": "after promotion"}); continue
        try:
            ok, note, png = run_step(serial, s["op"], s["args"], out, n)
        except Exception as e:  # a step that can't run is a failure, never a pass
            ok, note, png = False, str(e)[:300], None
        # A screenshot proves nothing by itself: it is LOOK until someone has looked (README).
        verdict = "LOOK" if ok is None else "PASS" if ok else "FAIL"
        failed += ok is False
        results.append({"step": n, "where": "emu", "text": s["text"], "result": verdict, "note": note, "screenshot": png})
        print(f"{verdict}  {n:02d}  {s['text']}" + (f"  — {note}" if note else ""))
    looks = sum(r["result"] == "LOOK" for r in results)
    if looks:
        print(f"\n{looks} screenshots to look at before this walk counts: a LOOK is not a PASS.")
    json.dump({"script": script, "failed": failed, "looks": looks, "steps": results}, open(os.path.join(out, "results.json"), "w"), indent=1)
    phone = [s for s in steps if s["where"] == "phone"]
    if phone:
        print(f"\n{len(phone)} [phone] steps wait for the real Fold after promotion (walk.py {script} --phone).")
    sys.exit(1 if failed else 0)

if __name__ == "__main__":
    main()
