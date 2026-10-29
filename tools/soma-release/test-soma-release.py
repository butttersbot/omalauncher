#!/usr/bin/env python3
"""The update pill's source (Boss approved on Soma Canvas, soma-desktop/Updates): soma-release publishes a build only from
a clean checkout with a passing receipt for that very commit, and a runner serves its record and only the files it names."""
import hashlib, io, json, os, pathlib, shutil, socket, subprocess, sys, tarfile, tempfile, unittest, urllib.error, urllib.request

HERE = pathlib.Path(__file__).resolve().parent
RUNNER = HERE.parent / 'mac-runner'
work = pathlib.Path(tempfile.mkdtemp(prefix='soma-release-'))
release = work / 'release'
repo = work / 'repo'
KEY = 'k' * 40


def sh(*words, cwd=repo, check=True):
    return subprocess.run(words, cwd=cwd, capture_output=True, text=True, check=check)


def publish(*extra, receipt=None):
    env = {**os.environ, 'OMA_RUNNER_RELEASE': str(release)}
    return subprocess.run([sys.executable, str(HERE / 'soma-release'), 'publish', '--repo', str(repo), '--receipt', str(receipt or work / 'receipt.json'), *extra],
                          env=env, capture_output=True, text=True)


def receipt(commit, *codes):
    path = work / 'receipt.json'
    path.write_text(json.dumps({'commit': commit, 'suites': [{'name': 's%d' % i, 'exit': c} for i, c in enumerate(codes)]}))
    return path


(repo / 'tools/mac-runner').mkdir(parents=True)
for name in ('oma-runner', 'soma_services.py'):
    shutil.copyfile(RUNNER / name, repo / 'tools/mac-runner' / name)
sh('git', 'init', '-q')
sh('git', '-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-qm', 'first', '--allow-empty')
sh('git', 'add', '.')
sh('git', '-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-qm', 'Enter sends on the Mac too')
app = work / 'Soma.zip'
app.write_bytes(b'the app ' * 1000)
jar = work / 'Soma.jar'
jar.write_bytes(b'the jar ' * 1000)


class Publish(unittest.TestCase):
    def head(self):
        return sh('git', 'rev-parse', 'HEAD').stdout.strip()

    def test_1_refusesAFailingOrForeignReceipt(self):
        self.assertNotEqual(0, publish('--artifact', 'macos-arm64=%s' % app, receipt=receipt(self.head(), 0, 1)).returncode)
        self.assertNotEqual(0, publish('--artifact', 'macos-arm64=%s' % app, receipt=receipt('0' * 40, 0)).returncode)
        self.assertNotEqual(0, publish('--artifact', 'macos-arm64=%s' % app, receipt=receipt(self.head())).returncode)  # no suites at all
        self.assertFalse((release / 'latest.json').exists())

    def test_2_refusesAChangedCheckout(self):
        (repo / 'stray').write_text('x')
        try:
            self.assertNotEqual(0, publish('--artifact', 'macos-arm64=%s' % app, receipt=receipt(self.head(), 0)).returncode)
        finally:
            (repo / 'stray').unlink()
        self.assertFalse((release / 'latest.json').exists())

    def test_3_publishesTheRecordWithEveryFilesSha(self):
        done = publish('--artifact', 'macos-arm64=%s' % app, '--artifact', 'linux-x64=%s' % jar, receipt=receipt(self.head(), 0, 0))
        self.assertEqual(0, done.returncode, done.stderr)
        record = json.loads((release / 'latest.json').read_text())
        commit = self.head()[:8]
        self.assertEqual(commit, record['commit'])
        self.assertEqual(['Enter sends on the Mac too', 'first'], record['notes'])
        self.assertEqual({'macos-arm64', 'linux-x64', 'runner'}, set(record['artifacts']))
        for a in record['artifacts'].values():
            self.assertEqual(a['sha256'], hashlib.sha256((release / commit / a['file']).read_bytes()).hexdigest())
        with tarfile.open(release / commit / record['artifacts']['runner']['file']) as tar:
            self.assertEqual({'oma-runner', 'soma_services.py', 'oma-runner.build'}, set(tar.getnames()))
            self.assertEqual(commit, tar.extractfile('oma-runner.build').read().decode().strip())

    def test_4_theNextBuildSaysWhatChangedSinceThisOne(self):
        (repo / 'tools/mac-runner/note').write_text('x')
        sh('git', 'add', '.')
        sh('git', '-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-qm', 'Settings has the tray icon')
        done = publish('--artifact', 'macos-arm64=%s' % app, receipt=receipt(self.head(), 0))
        self.assertEqual(0, done.returncode, done.stderr)
        record = json.loads((release / 'latest.json').read_text())
        self.assertEqual(['Settings has the tray icon'], record['notes'])
        self.assertIsNotNone(record['previous'])


class Serve(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with socket.socket() as s:
            s.bind(('127.0.0.1', 0))
            cls.port = s.getsockname()[1]
        state = work / 'runner'
        (state / 'Projects').mkdir(parents=True)
        (state / 'keys.json').write_text(json.dumps({'devices': [{'name': 'phone', 'digest': 'sha256:' + hashlib.sha256(KEY.encode()).hexdigest(), 'added': 0}]}))
        (state / 'whois.json').write_text(json.dumps({'127.0.0.1': 'phone'}))
        env = {**os.environ, 'OMA_RUNNER_PORT': str(cls.port), 'OMA_RUNNER_BIND': '127.0.0.1', 'OMA_RUNNER_STATE': str(state / 'state'),
               'OMA_RUNNER_LOGS': str(state / 'logs'), 'OMA_RUNNER_ROOTS': str(state / 'Projects'), 'OMA_RUNNER_KEYS': str(state / 'keys.json'),
               'OMA_RUNNER_TOKEN': str(state / 'token'), 'OMA_RUNNER_WHOIS_FILE': str(state / 'whois.json'), 'OMA_RUNNER_PHONE': 'phone',
               'OMA_RUNNER_MAIL': str(state / 'mail'), 'OMA_RUNNER_CODEX_DISCOVER': '0', 'OMA_RUNNER_CLAUDE_DISCOVER': '0',
               'OMA_RUNNER_AGENT': str(RUNNER / 'fake-agent'), 'OMA_RUNNER_CODEX': str(RUNNER / 'fake-codex'), 'OMA_RUNNER_CURSOR': str(RUNNER / 'fake-cursor'),
               'OMA_RUNNER_GROK': str(state / 'no-grok'), 'OMA_RUNNER_BOTS': str(state / 'bots'), 'OMA_RUNNER_ARTIFACTS': str(state / 'artifacts'),
               'OMA_RUNNER_PROJECTS': str(state / 'projects.json'), 'OMA_RUNNER_RELEASE': str(release), 'HOME': str(state)}
        cls.runner = subprocess.Popen([sys.executable, str(RUNNER / 'oma-runner'), 'serve'], env=env, stdout=subprocess.PIPE, text=True)
        cls.runner.stdout.readline()

    @classmethod
    def tearDownClass(cls):
        cls.runner.terminate()
        cls.runner.wait()

    def get(self, path, key=KEY):
        request = urllib.request.Request('http://127.0.0.1:%d%s' % (self.port, path), headers={'Authorization': 'Bearer ' + key})
        try:
            with urllib.request.urlopen(request, timeout=10) as reply:
                return reply.status, reply.read()
        except urllib.error.HTTPError as problem:
            return problem.code, problem.read()

    def test_1_servesTheRecordAndOnlyTheFilesItNames(self):
        code, body = self.get('/release')
        self.assertEqual(200, code)
        record = json.loads(body)['release']
        for a in record['artifacts'].values():
            code, data = self.get('/release/files/' + a['file'])
            self.assertEqual(200, code)
            self.assertEqual(a['sha256'], hashlib.sha256(data).hexdigest())
        (release / record['commit'] / 'secret.txt').write_text('not listed')
        self.assertEqual(404, self.get('/release/files/secret.txt')[0])
        self.assertEqual(404, self.get('/release/files/..%2Flatest.json')[0])
        self.assertEqual(404, self.get('/release/files/latest.json')[0])

    def test_2_aKeyItDoesntKnowGetsNothing(self):
        self.assertEqual(401, self.get('/release', key='x' * 40)[0])

    def test_3_healthSaysNoBuildForARunnerFromACheckout(self):
        code, body = self.get('/health')
        self.assertEqual(200, code)
        self.assertIsNone(json.loads(body)['build'])


if __name__ == '__main__':
    try:
        unittest.main(verbosity=2)
    finally:
        shutil.rmtree(work, ignore_errors=True)
