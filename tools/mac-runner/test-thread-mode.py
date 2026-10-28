#!/usr/bin/env python3
"""POST /threads/ID/mode (Soma's /mode, 2026-10-28): a Claude or Codex thread runs auto or manual from its next message."""
import hashlib, json, os, pathlib, socket, subprocess, sys, tempfile, time, unittest, urllib.error, urllib.request

HERE = pathlib.Path(__file__).resolve().parent
work = pathlib.Path(tempfile.mkdtemp(prefix='thread-mode-'))
KEY = 'k' * 40
(work / 'Projects').mkdir()
(work / 'keys.json').write_text(json.dumps({'devices': [{'name': 'phone', 'digest': 'sha256:' + hashlib.sha256(KEY.encode()).hexdigest(), 'added': 0}]}))
(work / 'whois.json').write_text(json.dumps({'127.0.0.1': 'phone'}))
with socket.socket() as s:
    s.bind(('127.0.0.1', 0))
    port = s.getsockname()[1]
environment = {**os.environ, 'OMA_RUNNER_PORT': str(port), 'OMA_RUNNER_BIND': '127.0.0.1', 'OMA_RUNNER_STATE': str(work / 'state'),
               'OMA_RUNNER_LOGS': str(work / 'logs'), 'OMA_RUNNER_ROOTS': str(work / 'Projects'), 'OMA_RUNNER_KEYS': str(work / 'keys.json'),
               'OMA_RUNNER_TOKEN': str(work / 'token'), 'OMA_RUNNER_WHOIS_FILE': str(work / 'whois.json'), 'OMA_RUNNER_PHONE': 'phone',
               'OMA_RUNNER_SELF': 'testhost', 'OMA_RUNNER_MAIL': str(work / 'mail'), 'OMA_RUNNER_CODEX_DISCOVER': '0', 'OMA_RUNNER_CLAUDE_DISCOVER': '0',
               'OMA_RUNNER_AGENT': str(HERE / 'fake-agent'), 'OMA_RUNNER_CODEX': str(HERE / 'fake-codex'), 'OMA_RUNNER_CURSOR': str(HERE / 'fake-cursor'),
               'OMA_RUNNER_GROK': str(work / 'no-grok'), 'OMA_RUNNER_BOTS': str(work / 'bots'), 'OMA_RUNNER_ARTIFACTS': str(work / 'artifacts'),
               'OMA_RUNNER_PROJECTS': str(work / 'projects.json'), 'HOME': str(work)}


def call(method, path, body=None):
    request = urllib.request.Request('http://127.0.0.1:%d%s' % (port, path), method=method, data=None if body is None else json.dumps(body).encode(), headers={'Authorization': 'Bearer ' + KEY})
    try:
        with urllib.request.urlopen(request, timeout=10) as reply:
            return reply.status, json.loads(reply.read())
    except urllib.error.HTTPError as problem:
        return problem.code, json.loads(problem.read())


def starts():
    log = work / 'Projects' / 'starts.log'
    return [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []


def until(done, seconds=20):
    end = time.time() + seconds
    while time.time() < end and not done():
        time.sleep(0.2)
    return done()


class ThreadMode(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.runner = subprocess.Popen([sys.executable, str(HERE / 'oma-runner'), 'serve'], env=environment, stdout=subprocess.PIPE, text=True)
        cls.runner.stdout.readline()

    @classmethod
    def tearDownClass(cls):
        cls.runner.terminate()
        cls.runner.wait()

    def test_aClaudeThreadRunsTheModeChosenFromItsNextMessage(self):
        code, made = call('POST', '/threads', {'name': 'mode test', 'project': str(work / 'Projects')})
        self.assertEqual(200, code, made)
        tid = made['thread']['id']
        self.assertEqual('auto', made['thread']['mode'])
        call('POST', '/threads/%s/messages' % tid, {'text': 'first, in auto'})
        self.assertTrue(until(lambda: any(s['prompt'].startswith('first, in auto') for s in starts())))
        self.assertTrue(until(lambda: call('GET', '/threads/' + tid)[1]['thread']['status'] == 'idle'))
        self.assertEqual((200, {'ok': True, 'mode': 'manual'}), call('POST', '/threads/%s/mode' % tid, {'mode': 'manual'}))
        self.assertEqual('manual', call('GET', '/threads/' + tid)[1]['thread']['mode'])
        call('POST', '/threads/%s/messages' % tid, {'text': 'second, in manual'})
        self.assertTrue(until(lambda: any(s['prompt'].startswith('second, in manual') for s in starts())))
        self.assertEqual({'first, in auto': 'auto', 'second, in manual': 'manual'},
                         {s['prompt'].split('\n')[0]: s['mode'] for s in starts() if s['prompt'].startswith(('first', 'second'))})

    def test_onlyAutoOrManualAndOnlyClaudeOrCodex(self):
        tid = call('POST', '/threads', {'name': 'mode refused', 'project': str(work / 'Projects')})[1]['thread']['id']
        self.assertEqual(400, call('POST', '/threads/%s/mode' % tid, {'mode': 'yolo'})[0])
        self.assertEqual(400, call('POST', '/threads/%s/mode' % tid, {})[0])
        cursor = call('POST', '/threads', {'name': 'cursor', 'project': str(work / 'Projects'), 'kind': 'cursor'})
        if cursor[0] == 200:
            self.assertEqual(400, call('POST', '/threads/%s/mode' % cursor[1]['thread']['id'], {'mode': 'auto'})[0])


if __name__ == '__main__':
    unittest.main()
