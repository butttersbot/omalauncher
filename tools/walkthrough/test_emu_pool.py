"""The pool's promises, without booting anything: two walks never share a slot, a dead walk lets go, a named emulator
waits for its lock, the lock of whoever started the walk isn't waited for. The real thing: README.md's proof."""
import importlib.util, json, os, signal, subprocess, sys, tempfile, textwrap, threading, time
from pathlib import Path
import unittest
from unittest.mock import patch

HERE = Path(__file__).parent
spec = importlib.util.spec_from_file_location("emu_pool", HERE / "emu_pool.py")
pool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pool)


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        env = patch.dict(os.environ, {"SOMA_LANES_STATE": self.tmp.name, "SOMA_THREAD": "", "SOMA_EMU_CAP": "99"})
        env.start(); self.addCleanup(env.stop)


def fake_machine(test):
    """A claim with nothing real behind it: no copy, no emulator, always room."""
    for name, value in dict(have_golden=lambda: True, no_room=lambda: "", stop_slot=lambda n: None, copy_golden=lambda n: None,
                            spawn=lambda *a: None, wait_boot=lambda *a, **k: None, make_ready=lambda s: None,
                            start_watcher=lambda n: None, port_taken=lambda port: False, emulators=lambda: [(1, "Fold8-Pool-%d" % i) for i in range(1, 7)]).items():
        p = patch.object(pool, name, value)
        p.start(); test.addCleanup(p.stop)


class Slots(Base):
    def test_walks_at_once_never_share_a_slot(self):
        fake_machine(self)
        got, lock = [], threading.Lock()
        def walk():
            c = pool.claim("test")
            with lock:
                got.append(c)
        threads = [threading.Thread(target=walk) for _ in range(pool.SLOTS)]
        [t.start() for t in threads]; [t.join() for t in threads]
        self.assertEqual(len({c.serial for c in got}), pool.SLOTS)

    def test_a_seventh_waits_until_one_is_released(self):
        fake_machine(self)
        held = [pool.claim("test") for _ in range(pool.SLOTS)]
        with self.assertRaises(pool.NoEmulator):
            pool.claim("test", seconds=0)
        freed = held[2].serial
        held[2].release()
        self.assertEqual(pool.claim("test", seconds=0).serial, freed)

    def test_no_room_waits_and_a_full_mac_asks_the_other(self):
        fake_machine(self)
        with patch.object(pool, "no_room", lambda: "6 emulators running"):
            self.assertEqual(pool.claim("test", seconds=5, when_full=lambda: 0), 0)
            with self.assertRaisesRegex(pool.NoEmulator, "6 emulators running"):
                pool.claim("test", seconds=0, when_full=lambda: None)

    def test_a_slot_that_will_not_boot_is_tried_once_more(self):
        fake_machine(self)
        tries = []
        def boot(*a, **k):
            tries.append(1)
            if len(tries) == 1:
                raise pool.NoEmulator("stalled")
        with patch.object(pool, "wait_boot", boot), patch.object(pool, "say"):
            self.assertTrue(pool.claim("test").serial.startswith("emulator-"))
        self.assertEqual(len(tries), 2)

    def test_a_bad_slot_does_not_stop_the_others(self):
        fake_machine(self)
        with patch.object(pool, "port_taken", lambda port: port == pool.FIRST_PORT):
            self.assertEqual(pool.claim("test").serial, "emulator-%d" % (pool.FIRST_PORT + 2))
        tries = []
        def boot(serial, *a, **k):
            tries.append(serial)
            if len(tries) == 1:
                raise pool.NoEmulator("stalled")
        with patch.object(pool, "wait_boot", boot), patch.object(pool, "say"):
            pool.claim("test")
        self.assertNotEqual(tries[0], tries[1])

    def test_a_killed_walk_lets_the_slot_go(self):
        # A real process holding a real lock, killed with -9.
        code = textwrap.dedent("""
            import importlib.util, sys, time
            spec = importlib.util.spec_from_file_location("emu_pool", sys.argv[1])
            pool = importlib.util.module_from_spec(spec); spec.loader.exec_module(pool)
            lock = pool.try_lock(pool.os.path.join(pool.pool_dir(), "slot-1.lock"))
            pool.record(lock, "doomed"); print("held", flush=True); time.sleep(60)
        """)
        p = subprocess.Popen([sys.executable, "-I", "-c", code, str(HERE / "emu_pool.py")], stdout=subprocess.PIPE, text=True)
        self.addCleanup(p.wait)
        self.assertEqual(p.stdout.readline().strip(), "held")
        path = os.path.join(pool.pool_dir(), "slot-1.lock")
        self.assertIsNone(pool.try_lock(path))
        p.send_signal(signal.SIGKILL); p.wait()
        lock = pool.try_lock(path)
        self.assertIsNotNone(lock)
        lock.close()


class Named(Base):
    def test_the_old_locks_keep_their_names(self):
        self.assertTrue(pool.lock_path("emulator-5554").endswith("/walks.lock"))
        self.assertTrue(pool.lock_path("emulator-5556").endswith("/walks-B.lock"))
        self.assertTrue(pool.lock_path("emulator-5574").endswith("/pool/slot-1.lock"))
        self.assertTrue(pool.lock_path("emulator-5560").endswith("/emulator-5560.lock"))
        self.assertIsNone(pool.slot_of("emulator-5572"))  # the base image's port is nobody's slot

    def test_a_second_walk_waits_for_the_first(self):
        first = pool.named("emulator-5560", "first")
        with patch.object(pool, "say"), patch.object(pool, "mine", lambda rec: False):
            with self.assertRaisesRegex(pool.NoEmulator, "first"):
                pool.named("emulator-5560", "second", seconds=0)
        first.release()
        pool.named("emulator-5560", "second", seconds=0).release()

    def test_a_script_holding_the_lock_is_not_waited_for(self):
        # soma-macbuild's and run-walk-locked's way: soma-lanes hold, with the script's pid, then walk.py as its child.
        path = pool.lock_path("emulator-5554")
        held = pool.try_lock(path)
        held.write(json.dumps({"who": "the parent", "pid": os.getppid(), "since": 1})); held.flush()
        c = pool.named("emulator-5554", "child", seconds=0)
        self.assertIsNone(c.lock)
        # Two walks under that one lock still can't run together.
        with patch.object(pool, "say"):
            with self.assertRaises(pool.NoEmulator):
                pool.named("emulator-5554", "child 2", seconds=0)
        c.release(); held.close()

    def test_a_claim_is_walked_on_by_its_claimer_but_not_by_another_thread(self):
        path = pool.lock_path("emulator-5574")
        held = pool.try_lock(path)
        # A holder process of its own, as oma-emu claim's is: pid 1 is nobody's ancestor here.
        held.write(json.dumps({"who": "my claim", "pid": 1, "since": 1, "thread": "", "claim": True})); held.flush()
        c = pool.named("emulator-5574", "walk", seconds=0)  # no thread named, as from a plain shell
        c.release()
        with patch.dict(os.environ, {"SOMA_THREAD": "tOther"}):
            held.seek(0); held.truncate()
            held.write(json.dumps({"who": "my claim", "pid": 1, "since": 1, "thread": "tMine", "claim": True})); held.flush()
            with patch.object(pool, "say"), self.assertRaises(pool.NoEmulator):
                pool.named("emulator-5574", "walk", seconds=0)
        held.close()

    def test_release_touches_only_a_live_claim(self):
        env = dict(os.environ)
        # A free lock with a stale pid, and a build script's held lock with no claim: both refused, nothing signalled.
        path = pool.lock_path("emulator-5554")
        Path(path).write_text(json.dumps({"who": "dead", "pid": os.getpid(), "since": 1, "claim": True}))
        run = lambda: subprocess.run([sys.executable, "-I", str(HERE / "emu_pool.py"), "release", "emulator-5554"], env=env, capture_output=True, text=True)
        self.assertEqual(run().returncode, 1)
        held = pool.try_lock(path)
        held.seek(0); held.truncate(); held.write(json.dumps({"who": "soma-macbuild", "pid": os.getpid(), "since": 1})); held.flush()
        self.assertEqual(run().returncode, 1)
        held.close()

    def test_what_a_dead_holder_wrote_means_nothing(self):
        path = pool.lock_path("emulator-5554")
        Path(path).write_text(json.dumps({"who": "dead", "pid": os.getppid(), "since": 1}))
        c = pool.named("emulator-5554", "next", seconds=0)
        self.assertIsNotNone(c.lock)
        c.release()


class Builds(Base):
    def test_only_the_apps_a_script_names(self):
        root = Path(self.tmp.name)
        for place in ("app/build/outputs/apk/debug", "tools/artifact-preview/build/outputs/apk/debug"):
            (root / place).mkdir(parents=True)
            (root / place / "x.apk").write_text("")
        found, missing = pool.needed_apks("launch dev.omalauncher.artifactpreview/.PreviewActivity", str(root))
        self.assertEqual([Path(f).parts[-6] for f in found], ["artifact-preview"])
        self.assertEqual(missing, [])
        found, missing = pool.needed_apks("launch dev.omalauncher.hunch/.MainActivity", str(root))
        self.assertEqual((found, missing), ([], ["dev.omalauncher.hunch"]))
        self.assertEqual(len(pool.needed_apks("fold open", str(root))[0]), 1)  # no app named: Soma

    def test_a_laptop_on_battery_takes_nothing_unless_told(self):
        fake_machine(self)
        with patch.object(pool, "on_battery", lambda: True):
            self.assertEqual(pool.can_take(), "on battery")
            with patch.dict(os.environ, {"SOMA_EMU_OVERFLOW_BATTERY": "1"}):
                self.assertEqual(pool.can_take(), "yes")


if __name__ == "__main__":
    unittest.main()
