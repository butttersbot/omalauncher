"""Tests of oma-keep (app/src/main/assets/oma-keep.py). Run in the guest with `python3 app/src/test/python/test_oma_keep.py`;
OmaKeepTest runs them too. The keeper is started for real under a temporary folder with its own socket, and stopped."""
import importlib.util, json, os, pathlib, socket, stat, subprocess, sys, tempfile, time, unittest

# Loading the script must leave no __pycache__ in the assets, which would ship in the app.
sys.dont_write_bytecode = True
SCRIPT = pathlib.Path(__file__).resolve().parents[2] / 'main' / 'assets' / 'oma-keep.py'
spec = importlib.util.spec_from_file_location('oma_keep', SCRIPT)
keep = importlib.util.module_from_spec(spec)
spec.loader.exec_module(keep)


def alive(pid):
    """Whether [pid] still runs; a zombie waiting to be reaped does not."""
    try:
        with open('/proc/%d/stat' % pid) as f:
            return f.read().rsplit(')', 1)[1].split()[0] != 'Z'
    except FileNotFoundError:
        return False


class Pure(unittest.TestCase):
    def test_names(self):
        for good in ['a', 'mock-up', 'x' * 32, '0-9']:
            self.assertTrue(keep.valid_name(good), good)
        for bad in ['', 'x' * 33, 'Mock', 'a_b', 'a.b', '../x', 'a b', 'a/b', 'é', None, 7]:
            self.assertFalse(keep.valid_name(bad), bad)

    def test_parse(self):
        self.assertEqual(keep.parse(['run', 'site', '--', 'python3', '-m', 'http.server']),
                         {'op': 'run', 'name': 'site', 'argv': ['python3', '-m', 'http.server']})
        self.assertEqual(keep.parse(['run', 'site', 'sleep', '5'])['argv'], ['sleep', '5'])
        # Only the first -- is the separator; later ones belong to the command.
        self.assertEqual(keep.parse(['run', 'a', '--', 'x', '--', 'y'])['argv'], ['x', '--', 'y'])
        self.assertEqual(keep.parse(['list']), {'op': 'list'})
        self.assertEqual(keep.parse(['stop', 'a']), {'op': 'stop', 'name': 'a'})
        self.assertEqual(keep.parse(['log', 'a']), {'op': 'log', 'name': 'a', 'lines': 100})
        self.assertEqual(keep.parse(['log', 'a', '7'])['lines'], 7)
        for bad in [[], ['run'], ['run', 'a'], ['run', 'a', '--'], ['run', 'A', 'x'], ['run', '../x', 'x'],
                    ['list', 'x'], ['stop'], ['stop', 'a', 'b'], ['log', 'a', '0'], ['log', 'a', '-1'],
                    ['log', 'a', 'x'], ['frob'], ['serve', 'x']]:
            with self.assertRaises(keep.Usage, msg=bad):
                keep.parse(bad)

    def test_argv(self):
        keep.check_argv(['bash', '-c', 'echo; rm nothing'])
        for bad in [[], 'ls', [''], ['ls', 3], ['ls', 'a\0b'], None]:
            with self.assertRaises(keep.Refused, msg=bad):
                keep.check_argv(bad)

    def test_room(self):
        self.assertIsNone(keep.to_forget({}, 'a'))
        self.assertEqual(keep.to_forget({'a': (False, 1)}, 'a'), 'a')
        with self.assertRaises(keep.Refused):
            keep.to_forget({'a': (True, 1)}, 'a')
        full = {'n%d' % i: (True, i) for i in range(keep.MAX_KEPT)}
        with self.assertRaises(keep.Refused):
            keep.to_forget(full, 'new')
        # With all room taken, the oldest that has ended makes way; one still running never does.
        full['n5'] = (False, 5)
        full['n3'] = (False, 3)
        self.assertEqual(keep.to_forget(full, 'new'), 'n3')
        self.assertIsNone(keep.to_forget({'n%d' % i: (True, i) for i in range(keep.MAX_KEPT - 1)}, 'new'))

    def test_trim(self):
        self.assertEqual(keep.trimmed(b'abc', 10), b'abc')
        data = b''.join(b'line %04d\n' % i for i in range(1000))
        cut = keep.trimmed(data, 2000)
        self.assertLessEqual(len(cut), 1000)
        self.assertTrue(data.endswith(cut))
        self.assertTrue(cut.startswith(b'line '))
        # Output without newlines is still cut to size.
        self.assertEqual(keep.trimmed(b'x' * 50, 20), b'x' * 10)

    def test_stale_logs(self):
        logs = {'a': 1, 'b': 2, 'c': 3, 'kept': 0}
        self.assertEqual(keep.stale_logs(logs, {'kept'}, keep=1), ['a', 'b'])
        self.assertEqual(keep.stale_logs(logs, {'kept'}, keep=8), [])

    def test_uptime(self):
        self.assertEqual([keep.uptime(s) for s in [0, 59, 61, 3600, 90061]], ['0s', '59s', '1m01s', '1h00m', '1d01h'])


class Keeper(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix='oma-keep-test-')
        self.keep_dir = os.path.join(self.dir, 'keep')
        self.env = dict(os.environ, OMA_KEEP_DIR=self.keep_dir)
        self.keeper = subprocess.Popen([sys.executable, str(SCRIPT), 'serve'], env=self.env, stderr=subprocess.DEVNULL)
        sock = os.path.join(self.keep_dir, 'keep.sock')
        for _ in range(100):
            if os.path.exists(sock):
                break
            time.sleep(0.05)
        self.sock = sock

    def tearDown(self):
        self.keeper.terminate()
        self.keeper.wait(10)
        subprocess.run(['rm', '-rf', self.dir])

    def cmd(self, *args, cwd=None):
        return subprocess.run([sys.executable, str(SCRIPT)] + list(args), env=self.env, cwd=cwd or self.dir,
                              capture_output=True, text=True, timeout=30)

    def test_only_its_owner_can_reach_it(self):
        self.assertEqual(stat.S_IMODE(os.stat(self.sock).st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(os.stat(self.keep_dir).st_mode), 0o700)
        self.assertTrue(stat.S_ISSOCK(os.stat(self.sock).st_mode))

    def test_run_list_log_stop(self):
        ran = self.cmd('run', 'echo', '--', 'bash', '-c', 'echo "in $PWD"; echo "$1"; exec sleep 60', 'x', 'a b; $(no)')
        self.assertEqual(ran.returncode, 0, ran.stderr)
        self.assertIn('echo: running', ran.stdout)
        time.sleep(0.3)
        # The words went as given: no shell expanded the last one.
        log = self.cmd('log', 'echo')
        self.assertIn('in %s\n' % self.dir, log.stdout)
        self.assertIn('a b; $(no)\n', log.stdout)
        listed = self.cmd('list')
        self.assertRegex(listed.stdout, r'echo\s+\d+\s+running')
        again = self.cmd('run', 'echo', 'true')
        self.assertEqual(again.returncode, 1)
        self.assertIn('already running', again.stderr)
        started = time.monotonic()
        self.assertEqual(self.cmd('stop', 'echo').returncode, 0)
        self.assertLess(time.monotonic() - started, 4)
        self.assertIn('Nothing is kept', self.cmd('list').stdout)
        self.assertEqual(self.cmd('stop', 'echo').returncode, 1)

    def test_a_command_that_ignores_term_is_killed(self):
        self.cmd('run', 'stubborn', 'bash', '-c', 'trap "" TERM; while :; do sleep 0.2; done')
        started = time.monotonic()
        self.assertEqual(self.cmd('stop', 'stubborn').returncode, 0)
        self.assertGreaterEqual(time.monotonic() - started, keep.STOP_GRACE - 0.5)
        self.assertIn('Nothing is kept', self.cmd('list').stdout)

    def test_it_outlives_the_command_that_asked(self):
        ran = self.cmd('run', 'sleeper', 'sleep', '60')
        pid = int(ran.stdout.split('pid ')[1].split('.')[0])
        # The asking process has ended; the kept one is a child of the keeper, not of it.
        with open('/proc/%d/stat' % pid) as f:
            self.assertEqual(int(f.read().rsplit(')', 1)[1].split()[1]), self.keeper.pid)
        self.cmd('stop', 'sleeper')

    def test_a_failed_start_is_said(self):
        ran = self.cmd('run', 'quick', 'bash', '-c', 'echo bad flag; exit 3')
        self.assertEqual(ran.returncode, 1)
        self.assertIn('exited 3', ran.stdout)
        self.assertIn('bad flag', ran.stdout)
        missing = self.cmd('run', 'missing', 'no-such-command-here')
        self.assertEqual(missing.returncode, 1)
        self.assertIn('cannot start', missing.stderr)

    def test_at_most_eight_run(self):
        for i in range(keep.MAX_KEPT):
            self.assertEqual(self.cmd('run', 'n%d' % i, 'sleep', '60').returncode, 0)
        ninth = self.cmd('run', 'n9', 'sleep', '60')
        self.assertEqual(ninth.returncode, 1)
        self.assertIn('stop one first', ninth.stderr)
        self.cmd('stop', 'n0')
        self.assertEqual(self.cmd('run', 'n9', 'sleep', '60').returncode, 0)

    def test_the_log_stays_under_its_cap(self):
        self.cmd('run', 'chatty', 'bash', '-c', 'yes line 2>/dev/null | head -n 400000; echo end')
        for _ in range(100):
            if 'exited 0' in self.cmd('list').stdout:
                break
            time.sleep(0.1)
        time.sleep(0.3)
        size = os.path.getsize(os.path.join(self.keep_dir, 'chatty.log'))
        self.assertLessEqual(size, keep.LOG_CAP)
        self.assertEqual(self.cmd('log', 'chatty', '2').stdout, 'line\nend\n')

    def test_a_bad_request_is_refused(self):
        client = socket.socket(socket.AF_UNIX)
        client.connect(self.sock)
        client.sendall(json.dumps({'op': 'run', 'name': 'x', 'argv': 'rm -rf /'}).encode() + b'\n')
        reply = json.loads(client.makefile().readline())
        client.close()
        self.assertFalse(reply['ok'])
        self.assertIn('Nothing is kept', self.cmd('list').stdout)

    def test_the_keeper_takes_what_it_kept_along(self):
        ran = self.cmd('run', 'orphan', 'sleep', '60')
        pid = int(ran.stdout.split('pid ')[1].split('.')[0])
        self.keeper.terminate()
        self.keeper.wait(10)
        time.sleep(0.2)
        self.assertFalse(alive(pid))
        self.assertFalse(os.path.exists(self.sock))
        self.assertIn('not running', self.cmd('list').stderr)


if __name__ == '__main__':
    unittest.main()
