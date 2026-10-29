#!/usr/bin/env python3
"""POST /threads/ID/archive (shared archive, A 2026-10-29): one archive for a thread, kept on its runner, read by every Soma."""
import hashlib, json, os, pathlib, socket, subprocess, sys, tempfile, time, unittest, urllib.error, urllib.request

HERE = pathlib.Path(__file__).resolve().parent
work = pathlib.Path(tempfile.mkdtemp(prefix='thread-archive-'))
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


def runner():
    process = subprocess.Popen([sys.executable, str(HERE / 'oma-runner'), 'serve'], env=environment, stdout=subprocess.PIPE, text=True)
    process.stdout.readline()
    return process


class SharedArchive(unittest.TestCase):
    def test_anArchiveIsKeptOnTheRunnerForEveryDeviceAndOutlivesARestart(self):
        process = runner()
        try:
            made = call('POST', '/threads', {'name': 'to archive', 'project': str(work / 'Projects')})[1]['thread']
            self.assertIsNone(made['archived'])
            before = int(time.time() * 1000)
            code, said = call('POST', '/threads/%s/archive' % made['id'], {'archived': True})
            self.assertEqual(200, code, said)
            self.assertGreaterEqual(said['thread']['archived'], before)
            # Every device's list says so, and the thread's events say it happened.
            listed = {t['id']: t for t in call('GET', '/threads')[1]['threads']}
            self.assertEqual(said['thread']['archived'], listed[made['id']]['archived'])
            events = call('GET', '/threads/%s/events' % made['id'])[1]['events']
            self.assertEqual([True], [e['archived'] for e in events if e['type'] == 'archived'])
            self.assertEqual(400, call('POST', '/threads/%s/archive' % made['id'], {'archived': 'yes'})[0])
            self.assertEqual(404, call('POST', '/threads/t000000000/archive', {'archived': True})[0])
        finally:
            process.terminate()
            process.wait()
        # Kept: a restarted runner still says it is archived; restored, it says null.
        process = runner()
        try:
            self.assertIsNotNone(call('GET', '/threads/' + made['id'])[1]['thread']['archived'])
            code, said = call('POST', '/threads/%s/archive' % made['id'], {'archived': False})
            self.assertEqual(200, code, said)
            self.assertIsNone(said['thread']['archived'])
            self.assertIsNone(call('GET', '/threads/' + made['id'])[1]['thread']['archived'])
        finally:
            process.terminate()
            process.wait()


if __name__ == '__main__':
    unittest.main()
