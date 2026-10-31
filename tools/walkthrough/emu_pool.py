#!/usr/bin/env python3
"""An emulator per walk (Boss, 2026-10-30: "Why don't we run the tests on separate emulators?"). See README.md, "One
emulator per walk". walk.py uses this by itself; the commands are for what isn't a single walk.

    emu_pool.py claim [--who WORDS] [--minutes N] [--parent PID]    prints a fresh emulator's serial and keeps it for you
    emu_pool.py release SERIAL                                      gives it back now
    emu_pool.py status                                              the slots, who has them, how much room is left
    emu_pool.py golden --apk APP.apk [--no-arch]                    makes this Mac's base image (once, about 15 minutes)
    emu_pool.py golden-add [--apk APK]... [--seed PACKAGE:TAR]... [--open ACTIVITY:SECONDS]...
                                                                    adds to it (Soma Notes and its walk notes, News's stream, a newer Soma)
    emu_pool.py can-take                                            "yes", or why not (the other Mac asks this)

The pool is copies of one base AVD, Fold8-Golden: first-run done, overlays, Soma keyboard, screen record tile, Arch
Linux. A claim copies it (APFS clones: instant, and only what a walk writes takes disk), boots the copy on a port of its
own and holds a kernel lock (flock) on the slot, so two walks never share one and a walk that dies never leaves it held.
Nothing of a walk outlives it: the copy is deleted at release, and by a small watcher if the walk was killed.
"""
import fcntl, glob, json, os, re, shutil, signal, socket, subprocess, sys, tempfile, time

HOME = os.path.expanduser("~")
AVD_ROOT = os.path.join(HOME, ".android", "avd")
GOLDEN = "Fold8-Golden"
GOLDEN_PORT = 5572
FIRST_PORT = 5574
SLOTS = 6  # adb finds emulators by itself on ports up to 5584
SOMA_IME = "dev.omalauncher.boss/dev.omalauncher.keyboard.OmaKeyboardService"
SOMA_HOME = "dev.omalauncher.boss/dev.omalauncher.MainActivity"
BOOT_SECONDS = 300
WAIT_SECONDS = 2700
# Boss: never load one machine so much that it slows down the rest of that computer. A new emulator starts only under
# "busy" percent of CPU, and up to "cap" running of anyone's (docs/EMULATOR.md has the measurements); what runs is never
# stopped for this. "peer" is the Mac a walk may go to when this one is at its limit.
MACHINES = {"jacks-mac-pro": {"cap": 8, "busy": 60, "peer": "macbook-pro"}, "macbook-pro": {"cap": 3, "busy": 40, "peer": "jacks-mac-pro"}}
# netsimd, the Mac's one simulated radio daemon, reset all its clients with 11 emulators up, and that crashes them, pool or
# not (2026-10-30, 18:20). No walk needs its radios; the network stays, through the emulator's own Wi-Fi path.
NO_NETSIM = ["-feature", "-WiFiPacketStream,-BluetoothEmulation,-Uwb,-NetsimWebUi,-NetsimCliUi"]
ROOM_MEMORY_GB = 20  # an emulator's 16 GB and some to spare
ROOM_DISK_GB = 15


class NoEmulator(RuntimeError):
    pass


def machine():
    name = socket.gethostname().split(".")[0].lower()
    return MACHINES.get(name, {"cap": 2, "busy": 50, "peer": None})


def cap():
    return int(os.environ.get("SOMA_EMU_CAP") or machine()["cap"])


def busy_limit():
    return int(os.environ.get("SOMA_EMU_BUSY") or machine()["busy"])


def peer():
    if os.environ.get("SOMA_EMU_OVERFLOW") == "0":
        return None
    return os.environ.get("SOMA_EMU_PEER") or machine()["peer"]


def state_dir():
    return os.environ.get("SOMA_LANES_STATE") or os.path.join(HOME, ".local", "state", "soma-macbuild")


def pool_dir():
    d = os.path.join(state_dir(), "pool")
    os.makedirs(d, exist_ok=True)
    return d


def sdk():
    for d in (os.environ.get("ANDROID_HOME"), "/opt/homebrew/share/android-commandlinetools", os.path.join(HOME, "Library/Android/sdk")):
        if d and os.path.exists(os.path.join(d, "emulator", "emulator")):
            return d
    raise NoEmulator("no Android SDK with an emulator here")


def use_sdk_tools():
    """A non-login shell (ssh from the other Mac) has no adb on its PATH."""
    try:
        tools = os.path.join(sdk(), "platform-tools")
    except NoEmulator:
        return
    if tools not in os.environ.get("PATH", "").split(":"):
        os.environ["PATH"] = os.environ.get("PATH", "") + ":" + tools


def adb(serial, *args, timeout=60):
    try:
        r = subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return ""
    return r.stdout.strip()


def say(words):
    print(words, file=sys.stderr, flush=True)


# ---- locks

def record(lock, who, **more):
    lock.seek(0); lock.truncate()
    lock.write(json.dumps({"who": who, "pid": os.getpid(), "since": int(time.time()), "thread": os.environ.get("SOMA_THREAD", ""), **more}))
    lock.flush()


def holder(path):
    """What the lock's holder wrote (soma-lanes hold writes the same), or {}."""
    try:
        with open(path) as f:
            return json.loads(f.read() or "{}")
    except (OSError, ValueError):
        return {}


def try_lock(path):
    lock = open(path, "a+")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return lock
    except BlockingIOError:
        lock.close()
        return None


def ancestors():
    out, pid = set(), os.getpid()
    while pid > 1 and pid not in out:
        out.add(pid)
        r = subprocess.run(["ps", "-o", "ppid=", "-p", str(pid)], capture_output=True, text=True)
        pid = int(r.stdout.strip() or 1)
    return out


def not_theirs(rec):
    """A claim belongs to any caller, unless both name their threads and the threads differ."""
    me = os.environ.get("SOMA_THREAD")
    return not (rec.get("thread") and me and rec["thread"] != me)


def mine(rec):
    """A lock taken by whoever started this walk (soma-macbuild's hold, an oma-emu claim made for this): it isn't taken again."""
    if not rec.get("pid"):
        return False
    if int(rec["pid"]) in ancestors():
        return True
    return bool(rec.get("claim")) and not_theirs(rec)


def slot_of(serial):
    m = re.fullmatch(r"emulator-(\d+)", serial)
    if m and FIRST_PORT <= int(m.group(1)) < FIRST_PORT + 2 * SLOTS and int(m.group(1)) % 2 == 0:
        return (int(m.group(1)) - FIRST_PORT) // 2 + 1
    return None


def lock_path(serial):
    """The locks soma-macbuild and the old habit already use for these three (walks, walks-B, walks-C)."""
    n = slot_of(serial)
    if n:
        return os.path.join(pool_dir(), "slot-%d.lock" % n)
    named = {"emulator-5554": "walks", "emulator-5556": "walks-B", "emulator-5558": "walks-C"}.get(serial)
    os.makedirs(state_dir(), exist_ok=True)
    return os.path.join(state_dir(), (named or re.sub(r"\W+", "-", serial)) + ".lock")


def wait_lock(path, who, seconds, what):
    end, told = time.monotonic() + seconds, False
    while True:
        lock = try_lock(path)
        if lock:
            return lock
        if not told:
            rec = holder(path)
            say("waiting for %s: %s has it" % (what, rec.get("who") or "pid %s" % rec.get("pid")))
            told = True
        if time.monotonic() > end:
            rec = holder(path)
            raise NoEmulator("%s still taken after %d minutes (%s)" % (what, seconds // 60, rec.get("who") or "pid %s" % rec.get("pid")))
        time.sleep(1)


class Claim:
    """An emulator this process may walk on. release() is safe twice; a process that dies lets go by itself."""
    def __init__(self, serial, avd=None, lock=None, inner=None, slot=None, pipe=None, note=""):
        self.serial, self.avd, self.lock, self.inner, self.slot, self.pipe, self.note = serial, avd, lock, inner, slot, pipe, note

    def release(self):
        if self.slot:
            stop_slot(self.slot)
        if self.pipe is not None:
            try:
                os.write(self.pipe, b"released\n"); os.close(self.pipe)
            except OSError:
                pass
            self.pipe = None
        for attr in ("inner", "lock"):
            lock = getattr(self, attr)
            if lock:
                try:
                    lock.seek(0); lock.truncate(); lock.flush(); lock.close()
                except (OSError, ValueError):
                    pass
                setattr(self, attr, None)
        self.slot = None


def named(serial, who, seconds=WAIT_SECONDS):
    """The emulator a walk asked for, under its lock. A second lock under it keeps two walks apart even when one claim
    (or one thread) started both."""
    path = lock_path(serial)
    # Free first: what a dead holder wrote is still in the file, and it says nothing once the kernel has let go.
    lock = try_lock(path)
    if not lock and not mine(holder(path)):
        lock = wait_lock(path, who, seconds, serial)
    if lock:
        record(lock, who)
        # A boot is the spike (about 3 cores for 20 s): one at a time, and the kernel lets go if this claim dies.
        booting = try_lock(os.path.join(pool_dir(), "booting.lock"))
        if not booting:
            lock.seek(0); lock.truncate(); lock.close()
            return None, BOOTING
    inner = wait_lock(path + ".walking", who, seconds, serial + " (another walk of yours)")
    record(inner, who)
    return Claim(serial, lock=lock, inner=inner, note="named")


# ---- room

def emulators():
    """(pid, avd) of every emulator running on this Mac: the pool's and anyone else's."""
    out = []
    for line in subprocess.run(["ps", "-axo", "pid=,command="], capture_output=True, text=True).stdout.splitlines():
        parts = line.split()
        if len(parts) > 3 and "qemu-system" in parts[1] and "-avd" in parts:
            out.append((int(parts[0]), parts[parts.index("-avd") + 1]))
    return out


def memory_free_gb():
    text = subprocess.run(["vm_stat"], capture_output=True, text=True).stdout
    page = int((re.search(r"page size of (\d+)", text) or [0, 16384])[1])
    pages = sum(int(m) for m in re.findall(r"Pages (?:free|inactive|speculative|purgeable):\s+(\d+)", text))
    return pages * page / 2 ** 30


def disk_free_gb():
    return shutil.disk_usage(AVD_ROOT).free / 2 ** 30


def cpu_ticks():
    """(busy, all) CPU ticks since boot, as herd's cpu_percent reads them."""
    import ctypes
    libc = ctypes.CDLL(None)
    ticks, count = (ctypes.c_uint * 4)(), ctypes.c_uint(4)  # user, system, idle, nice
    if libc.host_statistics(libc.mach_host_self(), 3, ticks, ctypes.byref(count)):  # HOST_CPU_LOAD_INFO
        return None
    return ticks[0] + ticks[1] + ticks[3], sum(ticks)


def cpu_percent(seconds=2):
    """Of all cores, over a couple of seconds; 0 if this Mac doesn't say (the emulator count still limits it)."""
    before = cpu_ticks()
    time.sleep(seconds)
    after = cpu_ticks()
    if not before or not after or after[1] <= before[1]:
        return 0
    return round(100 * (after[0] - before[0]) / (after[1] - before[1]))


def no_room():
    """Why this Mac shouldn't start another emulator now, or ''."""
    running = len(emulators())
    if running >= cap():
        return "%d emulators running, this Mac's limit" % running
    busy = cpu_percent()
    if busy >= busy_limit():
        return "CPU at %d%%, this Mac's limit is %d%%" % (busy, busy_limit())
    if memory_free_gb() < ROOM_MEMORY_GB:
        return "under %d GB of memory free" % ROOM_MEMORY_GB
    if disk_free_gb() < ROOM_DISK_GB:
        return "under %d GB of disk free" % ROOM_DISK_GB
    return ""


# ---- the slots

def avd_name(n):
    return "Fold8-Pool-%d" % n


def have_golden():
    return os.path.exists(os.path.join(AVD_ROOT, GOLDEN + ".avd", "golden.json"))


def stop_slot(n):
    name = avd_name(n)
    pids = [p for p, avd in emulators() if avd == name]
    for sig in (signal.SIGTERM, signal.SIGKILL):
        for p in pids:
            try:
                os.kill(p, sig)
            except OSError:
                pass
        end = time.monotonic() + 4
        while time.monotonic() < end and any(p in pids for p, _ in emulators()):
            time.sleep(0.2)
    shutil.rmtree(os.path.join(AVD_ROOT, name + ".avd"), ignore_errors=True)
    try:
        os.unlink(os.path.join(AVD_ROOT, name + ".ini"))
    except OSError:
        pass


def copy_golden(n):
    name = avd_name(n)
    src, dst = os.path.join(AVD_ROOT, GOLDEN + ".avd"), os.path.join(AVD_ROOT, name + ".avd")
    subprocess.run(["cp", "-c", "-R", src, dst], check=True)
    for f in ("multiinstance.lock", "hardware-qemu.ini.lock", "hardware-qemu.ini", "emu-launch-params.txt", "golden.json", "snapshot.trace"):
        try:
            os.unlink(os.path.join(dst, f))
        except OSError:
            pass
    with open(os.path.join(AVD_ROOT, name + ".ini"), "w") as f:
        f.write("avd.ini.encoding=UTF-8\npath=%s\npath.rel=avd/%s.avd\ntarget=android-37.0\n" % (dst, name))


def spawn(name, port, log_path):
    """In a session of its own, tied to nothing that started it (as oma-emu start). The watcher ends it after a crash."""
    log = open(log_path, "wb")
    return subprocess.Popen(
        [os.path.join(sdk(), "emulator", "emulator"), "-avd", name, "-port", str(port), "-writable-system",
         "-no-snapshot-save", "-no-boot-anim", "-no-window", "-no-audio", *NO_NETSIM],
        stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True)


def wait_boot(serial, process, log_path, seconds=BOOT_SECONDS):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        if adb(serial, "shell", "getprop", "sys.boot_completed", timeout=8) == "1":
            return
        # The launcher may exit once qemu runs; only the emulator's own failure counts.
        if process is not None and process.poll() not in (None, 0):
            tail = open(log_path, errors="replace").read()[-200:]
            raise NoEmulator("the emulator stopped while booting: " + tail.strip())
        time.sleep(1)
    raise NoEmulator("%s didn't finish booting in %d s" % (serial, seconds))


def fold_bars(serial):
    """As oma-emu fold-bars: Android turns the Fold-match overlay off at each boot, and again a few seconds later."""
    if not adb(serial, "shell", "pm", "path", "dev.soma.fold8.framework.overlay"):
        return
    held = 0
    for _ in range(15):
        if "[x] dev.soma.fold8.framework.overlay" in adb(serial, "shell", "cmd", "overlay", "list", "android"):
            held += 1
            if held >= 2:
                return
        else:
            held = 0
            adb(serial, "shell", "cmd", "overlay", "enable", "--user", "0", "dev.soma.fold8.framework.overlay")
            adb(serial, "shell", "cmd", "overlay", "set-priority", "dev.soma.fold8.framework.overlay", "highest")
        time.sleep(2)


def make_ready(serial):
    """Root (a walk opens screens that aren't exported), the bars, and every walk's start: cover, upright, at home."""
    adb(serial, "root"); time.sleep(1)
    adb(serial, "wait-for-device", timeout=30)
    wait_boot(serial, None, "", seconds=60)
    adb(serial, "shell", "locksettings", "set-disabled", "true")
    adb(serial, "shell", "svc", "power", "stayon", "true")
    adb(serial, "shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", "1")
    if adb(serial, "shell", "pm", "path", "dev.omalauncher.boss"):
        adb(serial, "shell", "ime", "enable", SOMA_IME)
        adb(serial, "shell", "ime", "set", SOMA_IME)
    fold_bars(serial)
    adb(serial, "emu", "fold")
    adb(serial, "shell", "settings", "put", "system", "user_rotation", "0")
    adb(serial, "shell", "input", "keyevent", "WAKEUP")
    adb(serial, "shell", "wm", "dismiss-keyguard")
    adb(serial, "shell", "input", "keyevent", "HOME")


def start_watcher(n):
    """Holds the read end of a pipe only this claim writes to: at EOF without "released" the claim died, so the emulator
    and the copy go, unless a new claim already has the slot (that one cleans up first)."""
    r, w = os.pipe()
    os.set_inheritable(r, True)
    subprocess.Popen([sys.executable, "-I", os.path.abspath(__file__), "_watch", str(n), state_dir()], stdin=r,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    os.close(r)
    return w


def watch(n):
    said = b""
    while True:
        chunk = os.read(0, 64)
        if not chunk:
            break
        said += chunk
    if b"released" in said:
        return
    path = os.path.join(pool_dir(), "slot-%d.lock" % n)
    end = time.monotonic() + 3
    while time.monotonic() < end:
        lock = try_lock(path)
        if lock:
            stop_slot(n)
            lock.seek(0); lock.truncate(); lock.close()
            return
        time.sleep(0.2)


BOOTING = "another emulator is booting"


def port_taken(port):
    """Someone else's emulator can sit in the pool's ports: adb's automatic ones overlap them."""
    with socket.socket() as s:
        s.settimeout(0.5)
        return s.connect_ex(("127.0.0.1", port)) == 0


def try_slot(who, skip=()):
    """The first free slot with its emulator booting, or (None, why not). One claim decides at a time, so two can't both
    take the last place; the boot itself is waited for outside that."""
    with open(os.path.join(pool_dir(), "pool.lock"), "a") as decide:
        fcntl.flock(decide, fcntl.LOCK_EX)
        lock, n = None, None
        for i in range(1, SLOTS + 1):
            if i in skip:
                continue
            lock = try_lock(os.path.join(pool_dir(), "slot-%d.lock" % i))
            if lock:
                stop_slot(i)  # a dead walk's leftovers, if its watcher hasn't got there
                if port_taken(FIRST_PORT + 2 * (i - 1)):
                    lock.close(); lock = None
                    continue
                n = i
                break
        if not lock:
            return None, "all %d slots taken" % SLOTS
        record(lock, who)
        # A boot is the spike (about 3 cores for 20 s): one at a time, and the kernel lets go if this claim dies.
        booting = try_lock(os.path.join(pool_dir(), "booting.lock"))
        if not booting:
            lock.seek(0); lock.truncate(); lock.close()
            return None, BOOTING
        why = no_room()
        if why:
            booting.close()
            lock.seek(0); lock.truncate(); lock.close()
            return None, why
        pipe = start_watcher(n)
        try:
            copy_golden(n)
            port = FIRST_PORT + 2 * (n - 1)
            log_path = os.path.join(pool_dir(), "slot-%d.log" % n)
            process = spawn(avd_name(n), port, log_path)
            # Until it shows in ps the next claim wouldn't count it.
            end = time.monotonic() + 6
            while time.monotonic() < end and avd_name(n) not in [a for _, a in emulators()]:
                time.sleep(0.1)
        except Exception:
            os.write(pipe, b"released\n"); os.close(pipe)
            stop_slot(n)
            booting.close()
            lock.close()
            raise
    claim = Claim("emulator-%d" % port, avd=avd_name(n), lock=lock, slot=n, pipe=pipe)
    claim.process, claim.log_path, claim.booting = process, log_path, booting
    return claim, ""


def claim(who, seconds=WAIT_SECONDS, when_full=None):
    """A fresh emulator, waiting until there is room. when_full is asked now and then while waiting: anything it returns
    but None ends the wait, and claim returns that (a walk that ran on the other Mac). A slot that fails to boot is
    tried once more, on the next slot, before giving up."""
    if not have_golden():
        raise NoEmulator("no pool on this Mac (no %s: emu_pool.py golden makes it)" % GOLDEN)
    started, told, next_ask, failures, bad = time.monotonic(), False, 0, 0, set()
    while True:
        got, why = try_slot(who, bad)
        if got:
            try:
                wait_boot(got.serial, got.process, got.log_path)
                make_ready(got.serial)
                got.booting.close()
                got.note = "%s, fresh in %d s" % (got.avd, time.monotonic() - started)
                return got
            except Exception as e:
                got.booting.close()
                bad.add(got.slot)
                got.release()
                failures += 1
                if failures >= 2:
                    raise NoEmulator(str(e))
                say("%s; trying again" % e)
                continue
        if when_full and why != BOOTING and time.monotonic() >= next_ask:
            result = when_full()
            if result is not None:
                return result
            next_ask = time.monotonic() + 60
        if not told and why != BOOTING:
            say("no emulator free yet (%s): waiting" % why)
            told = True
        if time.monotonic() - started > seconds:
            raise NoEmulator("no emulator free after %d minutes (%s)" % (seconds // 60, why))
        time.sleep(1)


# ---- what goes on it

APK_PLACES = {"dev.omalauncher.boss": "app/build/outputs/apk/debug/*.apk",
              "dev.omalauncher.artifactpreview": "tools/artifact-preview/build/outputs/apk/debug/*.apk",
              "dev.omalauncher.hunch": "hunch/build/outputs/apk/debug/*.apk"}


def needed_apks(script_text, root):
    """The checkout's builds of the apps a script drives: ([paths], [packages with no build here])."""
    used = set(re.findall(r"dev\.omalauncher\.(?:boss|artifactpreview|hunch)\b", script_text))
    if not used:
        used = {"dev.omalauncher.boss"}
    found, missing = [], []
    for package in sorted(used):
        builds = glob.glob(os.path.join(root, APK_PLACES[package]))
        if builds:
            found.append(max(builds, key=os.path.getmtime))
        else:
            missing.append(package)
    return found, missing


def install(serial, apk):
    """install -d: the base image's Soma may be newer than the build being walked. Another key's app is replaced; every
    copy holds test data only."""
    r = subprocess.run(["adb", "-s", serial, "install", "-r", "-t", "-d", apk], capture_output=True, text=True, timeout=300)
    if "Success" in r.stdout:
        return
    if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in r.stdout + r.stderr:
        package = re.search(r"Package (\S+) signatures", r.stdout + r.stderr)
        if package:
            adb(serial, "uninstall", package.group(1))
            r = subprocess.run(["adb", "-s", serial, "install", "-r", "-t", "-d", apk], capture_output=True, text=True, timeout=300)
            if "Success" in r.stdout:
                return
    raise NoEmulator("%s didn't install: %s" % (os.path.basename(apk), (r.stdout + r.stderr).strip()[-200:]))


def put_on(serial, apks, pushes):
    for apk in apks:
        install(serial, apk)
    if adb(serial, "shell", "pm", "path", "dev.omalauncher.boss"):
        adb(serial, "shell", "cmd", "package", "set-home-activity", SOMA_HOME)
        adb(serial, "shell", "pm", "grant", "dev.omalauncher.boss", "android.permission.POST_NOTIFICATIONS")
        adb(serial, "shell", "ime", "enable", SOMA_IME)
        adb(serial, "shell", "ime", "set", SOMA_IME)
    for push in pushes:
        local, device = push.rsplit(":", 1)
        r = subprocess.run(["adb", "-s", serial, "push", local, device], capture_output=True, text=True, timeout=120)
        if r.returncode != 0:
            raise NoEmulator("%s didn't push: %s" % (local, r.stderr.strip()[-200:]))
        adb(serial, "shell", "chmod", "644", device)
    adb(serial, "shell", "input", "keyevent", "HOME")


# ---- the other Mac

def on_battery():
    return "Battery Power" in subprocess.run(["pmset", "-g", "batt"], capture_output=True, text=True).stdout


def can_take():
    """'yes', or why this Mac won't take a walk from the other one: its pool must exist and have room, and an unplugged
    laptop isn't made to run emulators."""
    if not have_golden():
        return "no pool here"
    if on_battery() and os.environ.get("SOMA_EMU_OVERFLOW_BATTERY") != "1":
        return "on battery"
    free = any(try_lock_and_close(os.path.join(pool_dir(), "slot-%d.lock" % i)) for i in range(1, SLOTS + 1))
    if not free:
        return "all slots taken"
    return no_room() or "yes"


def try_lock_and_close(path):
    lock = try_lock(path)
    if lock:
        lock.close()
        return True
    return False


SSH = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=6", "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=4"]


def walk_on_peer(host, script, out, apks, pushes, who):
    """The whole walk on the other Mac: walk.py, the script, the builds and the fixtures go over, the output comes back.
    Returns walk.py's exit, or None if that Mac can't take it now (the walk then goes on waiting here)."""
    here = os.path.dirname(os.path.abspath(__file__))
    remote = ".local/state/soma-macbuild/pool/remote/%s-%d-%d" % (socket.gethostname().split(".")[0], os.getpid(), int(time.time()))
    def ssh(command, **kw):
        return subprocess.run(SSH + [host, command], **kw)
    def copy(*paths):
        return subprocess.run(["scp", "-q", *SSH[1:], *paths, "%s:%s/" % (host, remote)],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    battery = "SOMA_EMU_OVERFLOW_BATTERY=1 " if os.environ.get("SOMA_EMU_OVERFLOW_BATTERY") == "1" else ""
    try:
        if ssh("mkdir -p %s" % remote, capture_output=True, timeout=20).returncode != 0:
            return None
        if not copy(os.path.join(here, "walk.py"), os.path.join(here, "emu_pool.py"), script):
            return None
        answer = ssh("%spython3 %s/emu_pool.py can-take" % (battery, remote), capture_output=True, text=True, timeout=30).stdout.strip()
        if answer != "yes":
            say("%s can't take it: %s" % (host, answer or "no answer"))
            return None
        say("this Mac is full: walking on %s" % host)
        locals_ = apks + [p.rsplit(":", 1)[0] for p in pushes]
        if locals_ and not copy(*locals_):
            return None
        command = ["python3", remote + "/walk.py", remote + "/" + os.path.basename(script), "--out", remote + "/out", "--here"]
        for apk in apks:
            command += ["--apk", remote + "/" + os.path.basename(apk)]
        for push in pushes:
            local, device = push.rsplit(":", 1)
            command += ["--push", remote + "/" + os.path.basename(local) + ":" + device]
        command += ["--who", who]
        quoted = " ".join("'%s'" % c.replace("'", "'\\''") for c in command)
        # -tt: if this end dies, the far walk gets a hangup, ends, and lets its emulator go.
        walk = subprocess.Popen(SSH + ["-tt", host, battery + quoted], stdout=subprocess.PIPE, text=True, errors="replace")
        for line in walk.stdout:
            print(line.rstrip("\r\n"), flush=True)
        rc = walk.wait()
        if rc == 255:
            say("the connection to %s dropped: this walk goes on waiting here" % host)
            return None
        os.makedirs(out, exist_ok=True)
        subprocess.run(["rsync", "-a", "-e", " ".join(SSH), "%s:%s/out/" % (host, remote), out.rstrip("/") + "/"], stdout=subprocess.DEVNULL)
        results = os.path.join(out, "results.json")
        if os.path.exists(results):
            with open(results) as f:
                data = json.load(f)
            data["script"] = script
            for step in data.get("steps", []):
                if step.get("screenshot"):
                    step["screenshot"] = os.path.join(out, os.path.basename(step["screenshot"]))
            with open(results, "w") as f:
                json.dump(data, f, indent=1)
        return rc
    except subprocess.TimeoutExpired:
        return None
    finally:
        try:
            ssh("rm -rf %s" % remote, capture_output=True, timeout=20)
        except subprocess.SubprocessError:
            pass


# ---- the base image

def java_on_path(env):
    """The overlays are signed with keytool and apksigner; a Mac may have Java only as Gradle's or Android Studio's."""
    if subprocess.run(["java", "-version"], capture_output=True, env=env).returncode == 0:
        return
    homes = sorted(glob.glob(os.path.join(HOME, ".gradle/jdks/*/*/Contents/Home")) + glob.glob(os.path.join(HOME, ".gradle/jdks/*/Contents/Home"))) \
        + glob.glob("/Applications/Android Studio.app/Contents/jbr/Contents/Home")
    if not homes:
        raise NoEmulator("no Java on this Mac to sign the overlays with")
    env["JAVA_HOME"] = homes[-1]
    env["PATH"] = os.path.join(homes[-1], "bin") + ":" + env.get("PATH", "")


def golden(apk, arch=True):
    """Fold8-Golden from nothing, by the steps of docs/EMULATOR.md. It sits unused afterwards; only copies of it run."""
    try:
        make_golden(apk, arch)
    except BaseException:
        # Never left booted and half made: the next try starts by booting it.
        for pid, avd in emulators():
            if avd == GOLDEN:
                os.kill(pid, signal.SIGKILL)
        raise


def make_golden(apk, arch):
    here = os.path.dirname(os.path.abspath(__file__))
    sys.path.insert(0, here)
    import walk
    avd_dir = os.path.join(AVD_ROOT, GOLDEN + ".avd")
    serial = "emulator-%d" % GOLDEN_PORT
    if os.path.exists(os.path.join(avd_dir, "golden.json")):
        raise NoEmulator("%s is already made; delete it first to make a new one" % GOLDEN)
    source = os.path.join(AVD_ROOT, "Fold8.avd", "config.ini")
    if not os.path.exists(source):
        raise NoEmulator("no Fold8 AVD to take the Fold's shape from (docs/EMULATOR.md makes one)")
    tools = sorted(d for d in os.listdir(os.path.join(sdk(), "cmdline-tools")))
    manager = os.path.join(sdk(), "cmdline-tools", tools[0], "bin", "avdmanager")
    env = dict(os.environ, ANDROID_HOME=sdk(), ANDROID_SDK_ROOT=sdk(), SOMA_EMU_SERIAL=serial)
    java_on_path(env)
    if not os.path.exists(avd_dir):
        subprocess.run([manager, "create", "avd", "-n", GOLDEN, "-k", "system-images;android-37.0;google_apis;arm64-v8a",
                        "-d", "pixel_9_pro_fold"], input="no\n", text=True, env=env, capture_output=True, check=True)
    shutil.copy(source, os.path.join(avd_dir, "config.ini"))
    log_path = os.path.join(pool_dir(), "golden.log")
    say("booting the base image")
    process = spawn(GOLDEN, GOLDEN_PORT, log_path)
    wait_boot(serial, process, log_path)
    say("overlays")
    subprocess.run([os.path.join(here, "..", "fold-development", "fold8-overlay", "install.sh")], env=env, check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    adb(serial, "reboot"); time.sleep(5)
    wait_boot(serial, None, log_path)
    adb(serial, "root"); time.sleep(1); adb(serial, "wait-for-device", timeout=30)
    wait_boot(serial, None, log_path, seconds=60)
    adb(serial, "shell", "locksettings", "set-disabled", "true")
    adb(serial, "shell", "svc", "power", "stayon", "true")
    adb(serial, "shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", "1")
    say("Soma and its first-run")
    put_on(serial, [apk], [])
    adb(serial, "shell", "cmd", "statusbar", "add-tile", "dev.omalauncher.boss/dev.omalauncher.record.ScreenRecordTile")
    adb(serial, "shell", "input", "keyevent", "WAKEUP")
    walk.run_step(serial, "launch", [SOMA_HOME], tempfile.gettempdir(), 0)
    if arch:
        ok, note, _ = walk.run_step(serial, "tap", ["also", "set", "up", "Arch", "Linux"], tempfile.gettempdir(), 0)
        if not ok:
            raise NoEmulator("first-run: " + note)
    ok, note, _ = walk.run_step(serial, "tap", ["start"], tempfile.gettempdir(), 0)
    if not ok:
        raise NoEmulator("first-run: " + note)
    if arch:
        wait_for_arch(serial)
    finish_golden(serial, apk, arch)


def golden_add(apks, seeds, opens=()):
    """Boots the base image, puts these on it and stops it. New claims wait meanwhile (a copy of a running image is no copy)."""
    if not have_golden():
        raise NoEmulator("no %s here yet" % GOLDEN)
    serial = "emulator-%d" % GOLDEN_PORT
    with open(os.path.join(pool_dir(), "pool.lock"), "a") as decide:
        fcntl.flock(decide, fcntl.LOCK_EX)
        with open(os.path.join(AVD_ROOT, GOLDEN + ".avd", "golden.json")) as f:
            info = json.load(f)
        os.unlink(os.path.join(AVD_ROOT, GOLDEN + ".avd", "golden.json"))
        log_path = os.path.join(pool_dir(), "golden.log")
        process = spawn(GOLDEN, GOLDEN_PORT, log_path)
        wait_boot(serial, process, log_path)
        for apk in apks:
            install(serial, apk)
        for seed in seeds:
            package, tar = seed.split(":", 1)
            # Run once so the app's folders exist, and stopped so it can't write over what goes in.
            adb(serial, "shell", "monkey", "-p", package, "1"); time.sleep(3)
            adb(serial, "shell", "am", "force-stop", package)
            subprocess.run(["adb", "-s", serial, "push", tar, "/data/local/tmp/seed.tar"], capture_output=True, check=True)
            adb(serial, "shell", "chmod", "644", "/data/local/tmp/seed.tar")
            adb(serial, "shell", "run-as", package, "tar", "xf", "/data/local/tmp/seed.tar")
            adb(serial, "shell", "rm", "/data/local/tmp/seed.tar")
        for opened in opens:
            # An app that keeps what it fetched: its walks start with that, as they do on a used emulator, and don't
            # each fetch it all again (news.walk: "twenty walks a day ... helped get the network held back").
            activity, wait = opened.rsplit(":", 1)
            adb(serial, "shell", "input", "keyevent", "WAKEUP")
            adb(serial, "shell", "wm", "dismiss-keyguard")
            adb(serial, "shell", "am", "start", "-W", "-n", activity)
            time.sleep(int(wait))
        info["added"] = info.get("added", []) + [os.path.basename(p) for p in apks + seeds] + list(opens)
        finish_golden(serial, info.get("apk", ""), info.get("arch", True), info)


def wait_for_arch(serial, seconds=2400):
    say("Arch Linux (about ten minutes)")
    end, still = time.monotonic() + seconds, 0
    while time.monotonic() < end:
        steps = adb(serial, "shell", "run-as", "dev.omalauncher.boss", "cat", "files/rootfs-steps.json")
        busy = "Installing Linux" in adb(serial, "shell", "dumpsys", "notification", "--noredact")
        still = still + 1 if '"rootfs"' in steps and '"setup":true' in steps and not busy else 0
        if still >= 3:
            return
        time.sleep(10)
    raise NoEmulator("Arch Linux didn't finish installing in %d minutes" % (seconds // 60))


def finish_golden(serial, apk, arch, info=None):
    adb(serial, "shell", "input", "keyevent", "HOME")
    adb(serial, "shell", "rm", "-f", "/sdcard/walk.xml", "/sdcard/x.xml")
    version = re.search(r"versionName=(\S+)", adb(serial, "shell", "dumpsys", "package", "dev.omalauncher.boss"))
    adb(serial, "shell", "sync")
    adb(serial, "emu", "kill")
    end = time.monotonic() + 60
    while time.monotonic() < end and GOLDEN in [a for _, a in emulators()]:
        time.sleep(0.5)
    if GOLDEN in [a for _, a in emulators()]:
        raise NoEmulator("the base image didn't stop")
    with open(os.path.join(AVD_ROOT, GOLDEN + ".avd", "golden.json"), "w") as f:
        json.dump(dict(info or {"made": int(time.time()), "apk": os.path.basename(apk), "arch": arch}, soma=version.group(1) if version else ""), f)
    say("base image ready: %s" % os.path.join(AVD_ROOT, GOLDEN + ".avd"))


# ---- commands

def hold(who, minutes, parent):
    """Backs emu_pool.py claim: stays alive holding the emulator; its first line of output is the serial."""
    got = claim(who)
    record(got.lock, who, claim=True)
    def done(*_):
        got.release()
        os._exit(0)
    signal.signal(signal.SIGTERM, done)
    signal.signal(signal.SIGHUP, done)
    print(got.serial, flush=True)
    os.close(1)
    end = time.monotonic() + minutes * 60
    while time.monotonic() < end:
        if parent:
            try:
                os.kill(parent, 0)
            except ProcessLookupError:
                break
            except PermissionError:
                pass
        time.sleep(1)
    done()


def status():
    print("base image: %s" % (open(os.path.join(AVD_ROOT, GOLDEN + ".avd", "golden.json")).read() if have_golden() else "none on this Mac"))
    for n in range(1, SLOTS + 1):
        path = os.path.join(pool_dir(), "slot-%d.lock" % n)
        lock = try_lock(path)
        if lock:
            lock.close()
            print("slot %d  emulator-%d  free" % (n, FIRST_PORT + 2 * (n - 1)))
        else:
            rec = holder(path)
            print("slot %d  emulator-%d  %s (pid %s, since %s)" % (n, FIRST_PORT + 2 * (n - 1), rec.get("who"), rec.get("pid"),
                                                                 time.strftime("%H:%M:%S", time.localtime(rec.get("since", 0)))))
    running = emulators()
    print("running on this Mac: %d of %d: %s" % (len(running), cap(), ", ".join(a for _, a in running) or "none"))
    print("memory free %.0f GB, disk free %.0f GB; overflow: %s" % (memory_free_gb(), disk_free_gb(), peer() or "none"))


def option(args, name, default=None):
    if name in args:
        i = args.index(name)
        value = args[i + 1]
        del args[i:i + 2]
        return value
    return default


def pop_all(args, name):
    out = []
    while name in args:
        out.append(option(args, name))
    return out


def main():
    use_sdk_tools()
    args = sys.argv[1:]
    command = args.pop(0) if args else ""
    try:
        if command == "_watch":
            os.environ["SOMA_LANES_STATE"] = args[1]
            watch(int(args[0]))
        elif command == "_hold":
            hold(option(args, "--who", "a claim"), float(option(args, "--minutes", "60")), int(option(args, "--parent", "0")))
        elif command == "claim":
            who = option(args, "--who", "a claim from thread %s" % os.environ.get("SOMA_THREAD", "?"))
            cmd = [sys.executable, "-I", os.path.abspath(__file__), "_hold", "--who", who,
                   "--minutes", option(args, "--minutes", "60"), "--parent", option(args, "--parent", "0")]
            p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stdin=subprocess.DEVNULL, text=True, start_new_session=True)
            serial = p.stdout.readline().strip()
            if not serial:
                sys.exit(p.wait() or 1)
            print(serial)
        elif command == "release":
            path = lock_path(args[0])
            # Only a live claim: never whatever pid a dead holder left, or a build script holding walks.lock.
            free = try_lock(path)
            rec = {} if free else holder(path)
            if free:
                free.close()
            if not rec.get("claim"):
                say("%s has no oma-emu claim to release" % args[0]); sys.exit(1)
            if not not_theirs(rec):
                say("%s is %s's, not this thread's" % (args[0], rec.get("who"))); sys.exit(1)
            os.kill(int(rec["pid"]), signal.SIGTERM)
        elif command == "status":
            status()
        elif command == "golden":
            golden(option(args, "--apk"), "--no-arch" not in args)
        elif command == "golden-add":
            golden_add(pop_all(args, "--apk"), pop_all(args, "--seed"), pop_all(args, "--open"))
        elif command == "can-take":
            print(can_take())
        else:
            print(__doc__.strip(), file=sys.stderr)
            sys.exit(2)
    except NoEmulator as e:
        say(str(e))
        sys.exit(1)


if __name__ == "__main__":
    main()
