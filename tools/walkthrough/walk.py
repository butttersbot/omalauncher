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

def point(serial, xs, ys):
    """'0.5' is a fraction of the screen, '540' pixels."""
    w, h = size(serial)
    f = lambda v, full: int(float(v) * full) if "." in v else int(v)
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
    end = time.time() + wait_ms / 1000
    while True:
        for label, (l, t, r, b) in nodes(serial):
            if wanted.lower() in label.lower():
                return (l + r) // 2, (t + b) // 2
        if time.time() > end:
            return None
        time.sleep(0.4)

def shot(serial, path):
    with open(path, "wb") as f:
        f.write(subprocess.run(["adb", "-s", serial, "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout)

def run_step(serial, op, args, out, n):
    """One [emu] step; returns (ok, note, screenshot or None)."""
    if op == "launch":
        adb(serial, "shell", "am", "start", "-W", "-n", args[0], check=True); time.sleep(1.5); return True, "", None
    if op == "url":
        adb(serial, "shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", args[0]); time.sleep(1.5); return True, "", None
    if op == "ime":
        adb(serial, "shell", "ime", "enable", args[0]); adb(serial, "shell", "ime", "set", args[0], check=True); return True, "", None
    if op == "tap":
        if len(args) == 2 and re.fullmatch(r"[\d.]+", args[0]) and re.fullmatch(r"[\d.]+", args[1]):
            x, y = point(serial, *args)
        else:
            p = find(serial, args[0])
            if not p:
                return False, f"nothing says “{args[0]}”", None
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
    if op == "shell":
        adb(serial, "shell", *args, check=True); time.sleep(1); return True, "", None
    if op == "wait":
        time.sleep(int(args[0]) / 1000); return True, "", None
    if op == "rotate":
        adb(serial, "shell", "settings", "put", "system", "accelerometer_rotation", "0")
        adb(serial, "shell", "settings", "put", "system", "user_rotation", {"portrait": "0", "landscape": "1"}[args[0]]); time.sleep(1.5); return True, "", None
    if op == "fold":
        # The Fold profile's two screens: "folded" (cover) or "open" (inner).
        adb(serial, "emu", "fold" if args[0] == "folded" else "unfold"); time.sleep(2); return True, "", None
    if op in ("shot", "expect", "absent"):
        name = re.sub(r"\W+", "-", args[0] if op == "shot" else f"{n:02d}-{op}")[:60]
        path = os.path.join(out, f"{n:02d}-{name}.png")
        shot(serial, path)
        if op == "shot":
            return True, "look at it", path
        found = find(serial, args[0], wait_ms=4000 if op == "expect" else 800) is not None
        ok = found if op == "expect" else not found
        return ok, "" if ok else (f"“{args[0]}” not on screen" if op == "expect" else f"“{args[0]}” still on screen"), path
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
    results, failed = [], 0
    for n, s in enumerate(steps, 1):
        if s["where"] == "phone":
            results.append({"step": n, "where": "phone", "text": s["text"], "result": "after promotion"}); continue
        try:
            ok, note, png = run_step(serial, s["op"], s["args"], out, n)
        except Exception as e:  # a step that can't run is a failure, never a pass
            ok, note, png = False, str(e)[:300], None
        failed += not ok
        results.append({"step": n, "where": "emu", "text": s["text"], "result": "PASS" if ok else "FAIL", "note": note, "screenshot": png})
        print(f"{'PASS' if ok else 'FAIL'}  {n:02d}  {s['text']}" + (f"  — {note}" if note else ""))
    json.dump({"script": script, "failed": failed, "steps": results}, open(os.path.join(out, "results.json"), "w"), indent=1)
    phone = [s for s in steps if s["where"] == "phone"]
    if phone:
        print(f"\n{len(phone)} [phone] steps wait for the real Fold after promotion (walk.py {script} --phone).")
    sys.exit(1 if failed else 0)

if __name__ == "__main__":
    main()
