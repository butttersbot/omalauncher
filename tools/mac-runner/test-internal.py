#!/usr/bin/env python3
"""Internal threads (Boss, 2026-10-29: his tabs filled with sessions agents started for tests and helpers): a thread made
internal, or a Claude session found in a folder an agent marked as its own, stays off GET /threads (and so off every
Soma's tabs, Agents tiles, Needs you and notices); ?all=1 still shows it, and the mark can be taken off. Nothing is deleted."""
import hashlib, json, os, pathlib, socket, subprocess, sys, tempfile, time, unittest, urllib.error, urllib.request, uuid

HERE = pathlib.Path(__file__).resolve().parent
work = pathlib.Path(tempfile.mkdtemp(prefix='internal-'))
KEY = 'k' * 40
(work / 'Projects' / 'real').mkdir(parents=True)
(work / 'Projects' / 'judge').mkdir(parents=True)
(work / 'Projects' / 'judge' / '.soma-internal').write_text('')
(work / 'keys.json').write_text(json.dumps({'devices': [{'name': 'phone', 'digest': 'sha256:' + hashlib.sha256(KEY.encode()).hexdigest(), 'added': 0}]}))
(work / 'whois.json').write_text(json.dumps({'127.0.0.1': 'phone'}))
with socket.socket() as s:
    s.bind(('127.0.0.1', 0))
    port = s.getsockname()[1]
environment = {**os.environ, 'OMA_RUNNER_PORT': str(port), 'OMA_RUNNER_BIND': '127.0.0.1', 'OMA_RUNNER_STATE': str(work / 'state'),
               'OMA_RUNNER_LOGS': str(work / 'logs'), 'OMA_RUNNER_ROOTS': str(work / 'Projects'), 'OMA_RUNNER_KEYS': str(work / 'keys.json'),
               'OMA_RUNNER_TOKEN': str(work / 'token'), 'OMA_RUNNER_WHOIS_FILE': str(work / 'whois.json'), 'OMA_RUNNER_PHONE': 'phone',
               'OMA_RUNNER_MAIL': str(work / 'mail'), 'OMA_RUNNER_CODEX_DISCOVER': '0', 'OMA_RUNNER_CLAUDE_DISCOVER': '1',
               'OMA_RUNNER_CLAUDE_SCAN_EVERY': '1', 'CLAUDE_CONFIG_DIR': str(work / 'claude'),
               'OMA_RUNNER_AGENT': str(HERE / 'fake-agent'), 'OMA_RUNNER_CODEX': str(HERE / 'fake-codex'), 'OMA_RUNNER_CURSOR': str(HERE / 'fake-cursor'),
               'OMA_RUNNER_GROK': str(work / 'no-grok'), 'OMA_RUNNER_BOTS': str(work / 'bots'), 'OMA_RUNNER_ARTIFACTS': str(work / 'artifacts'),
               'OMA_RUNNER_PROJECTS': str(work / 'projects.json'), 'HOME': str(work)}


def call(method, path, body=None):
    request = urllib.request.Request('http://127.0.0.1:%d%s' % (port, path), method=method, headers={'Authorization': 'Bearer ' + KEY, 'Content-Type': 'application/json'},
                                     data=None if body is None else json.dumps(body).encode())
    try:
        with urllib.request.urlopen(request, timeout=10) as reply:
            return reply.status, json.loads(reply.read())
    except urllib.error.HTTPError as problem:
        return problem.code, json.loads(problem.read())


def names(path='/threads'):
    return {t['name']: t for t in call('GET', path)[1]['threads']}


def session(folder, text):
    """A Claude Code session as a headless call would leave it, in its own folder."""
    sid = str(uuid.uuid4())
    projects = work / 'claude' / 'projects' / ('-' + folder.name)
    projects.mkdir(parents=True, exist_ok=True)
    (projects / (sid + '.jsonl')).write_text(json.dumps({'type': 'user', 'sessionId': sid, 'cwd': str(folder), 'message': {'role': 'user', 'content': text}}) + '\n')


class Internal(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.runner = subprocess.Popen([sys.executable, str(HERE / 'oma-runner'), 'serve'], env=environment, stdout=subprocess.PIPE, text=True)
        cls.runner.stdout.readline()

    @classmethod
    def tearDownClass(cls):
        cls.runner.terminate()
        cls.runner.wait()

    def test_1_aThreadMadeInternalStaysOffTheListButAllShowsIt(self):
        call('POST', '/threads', {'name': 'Boss chat', 'project': str(work / 'Projects' / 'real')})
        code, made = call('POST', '/threads', {'name': 'Mesh proof from testhost', 'project': str(work / 'Projects' / 'real'), 'internal': True})
        self.assertEqual(200, code, made)
        self.assertTrue(made['thread']['internal'])
        self.assertIn('Boss chat', names())
        self.assertNotIn('Mesh proof from testhost', names())
        self.assertIn('Mesh proof from testhost', names('/threads?all=1'))
        # Still readable by its id: hidden, not gone.
        self.assertEqual(200, call('GET', '/threads/' + made['thread']['id'])[0])

    def test_2_theMarkGoesOnAndComesOffAndNothingIsDeleted(self):
        code, made = call('POST', '/threads', {'name': 'Scratch Claude', 'project': str(work / 'Projects' / 'real')})
        tid = made['thread']['id']
        self.assertEqual(True, call('POST', '/threads/%s/internal' % tid, {'internal': True})[1]['thread']['internal'])
        self.assertNotIn('Scratch Claude', names())
        self.assertEqual(False, call('POST', '/threads/%s/internal' % tid, {'internal': False})[1]['thread']['internal'])
        self.assertIn('Scratch Claude', names())
        self.assertEqual(400, call('POST', '/threads/%s/internal' % tid, {'internal': 'yes'})[0])

    def test_3_aSessionFoundInAFolderMarkedAsAnAgentsOwnIsInternal(self):
        session(work / 'Projects' / 'judge', 'You are checking whether a shipped app screen follows its approved design.')
        session(work / 'Projects' / 'real', 'fix the fold gpu')
        for _ in range(40):
            every = names('/threads?all=1')
            if 'fix the fold gpu' in every and any(n.startswith('You are checking') for n in every):
                break
            time.sleep(0.5)
        self.assertIn('fix the fold gpu', names())
        self.assertFalse(any(n.startswith('You are checking') for n in names()))
        self.assertTrue(any(n.startswith('You are checking') and t['internal'] for n, t in names('/threads?all=1').items()))


if __name__ == '__main__':
    unittest.main(verbosity=2)
