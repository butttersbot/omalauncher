#!/usr/bin/env python3
"""Two-way roads (Boss, 2026-10-28: "no more partial built lanes, we build two-way roads"): `oma-runner mesh` names every
missing leg between this machine and each tailnet computer that runs a runner, GET /devices says who a runner lets in (by
name only), and pairing a computer whose own runner doesn't let this machine's Soma in fails loudly."""
import hashlib, json, os, pathlib, socket, subprocess, sys, tempfile, unittest, urllib.error, urllib.request

HERE = pathlib.Path(__file__).resolve().parent
work = pathlib.Path(tempfile.mkdtemp(prefix='mesh-'))
KEY = 'k' * 40
(work / 'Projects').mkdir()
(work / 'keys.json').write_text(json.dumps({'devices': [{'name': 'phone', 'digest': 'sha256:' + hashlib.sha256(KEY.encode()).hexdigest(), 'added': 0}]}))
(work / 'whois.json').write_text(json.dumps({'127.0.0.1': 'phone'}))
with socket.socket() as s:
    s.bind(('127.0.0.1', 0))
    port = s.getsockname()[1]
# The tailnet as Tailscale says it: one other computer, "otherbox", online at the address this test's runner listens on,
# so it reads as a computer that runs a runner. `file cp` (a key sent by Taildrop) always works.
fake = work / 'tailscale'
fake.write_text('#!/bin/sh\nif [ "$1" = status ]; then\n  echo \'{"Self": {"DNSName": "testhost.ts.net.", "HostName": "testhost"}, '
                '"Peer": {"p1": {"DNSName": "otherbox.ts.net.", "HostName": "otherbox", "TailscaleIPs": ["127.0.0.1"], "Online": true}, '
                '"p2": {"DNSName": "sleepy.ts.net.", "HostName": "sleepy", "TailscaleIPs": ["127.0.0.9"], "Online": false}}}\'\nfi\nexit 0\n')
fake.chmod(0o755)
environment = {**os.environ, 'OMA_RUNNER_PORT': str(port), 'OMA_RUNNER_BIND': '127.0.0.1', 'OMA_RUNNER_STATE': str(work / 'state'),
               'OMA_RUNNER_LOGS': str(work / 'logs'), 'OMA_RUNNER_ROOTS': str(work / 'Projects'), 'OMA_RUNNER_KEYS': str(work / 'keys.json'),
               'OMA_RUNNER_TOKEN': str(work / 'token'), 'OMA_RUNNER_WHOIS_FILE': str(work / 'whois.json'), 'OMA_RUNNER_PHONE': 'phone',
               'OMA_RUNNER_SELF': 'testhost', 'OMA_RUNNER_SOMA_DIR': str(work / 'soma' / 'runners'), 'OMA_RUNNER_TAILSCALE': str(fake),
               'OMA_RUNNER_MAIL': str(work / 'mail'), 'OMA_RUNNER_CODEX_DISCOVER': '0', 'OMA_RUNNER_CLAUDE_DISCOVER': '0',
               'OMA_RUNNER_AGENT': str(HERE / 'fake-agent'), 'OMA_RUNNER_CODEX': str(HERE / 'fake-codex'), 'OMA_RUNNER_CURSOR': str(HERE / 'fake-cursor'),
               'OMA_RUNNER_GROK': str(work / 'no-grok'), 'OMA_RUNNER_BOTS': str(work / 'bots'), 'OMA_RUNNER_ARTIFACTS': str(work / 'artifacts'),
               'OMA_RUNNER_PROJECTS': str(work / 'projects.json'), 'HOME': str(work)}


def run(*words, pair_computers=True):
    env = {k: v for k, v in environment.items() if pair_computers or k != 'OMA_RUNNER_PAIR_COMPUTERS'}
    if pair_computers:
        env['OMA_RUNNER_PAIR_COMPUTERS'] = '1'
    return subprocess.run([sys.executable, str(HERE / 'oma-runner'), *words], env=env, capture_output=True, text=True, timeout=60)


def call(path):
    request = urllib.request.Request('http://127.0.0.1:%d%s' % (port, path), headers={'Authorization': 'Bearer ' + KEY})
    try:
        with urllib.request.urlopen(request, timeout=10) as reply:
            return reply.status, json.loads(reply.read())
    except urllib.error.HTTPError as problem:
        return problem.code, json.loads(problem.read())


def soma_has(*runners):
    """This machine's Soma, given keys for [runners] (its runners.json: names and addresses, never a key)."""
    (work / 'soma').mkdir(exist_ok=True)
    (work / 'soma' / 'runners.json').write_text(json.dumps({'runners': [{'id': r, 'name': r, 'url': 'http://127.0.0.1:%d' % port} for r in runners]}))


class Mesh(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.runner = subprocess.Popen([sys.executable, str(HERE / 'oma-runner'), 'serve'], env=environment, stdout=subprocess.PIPE, text=True)
        cls.runner.stdout.readline()

    @classmethod
    def tearDownClass(cls):
        cls.runner.terminate()
        cls.runner.wait()

    def test_1_meshNamesEveryMissingLegAndHowToMakeIt(self):
        soma_has()
        done = run('mesh')
        self.assertEqual(1, done.returncode, done.stdout + done.stderr)
        self.assertIn('otherbox', done.stdout)
        self.assertNotIn('sleepy', done.stdout)  # offline: not a road to check now
        # Both directions for otherbox, and this machine's own Soma to its own runner.
        self.assertIn('on testhost: OMA_RUNNER_PAIR_COMPUTERS=1 oma-runner pair otherbox', done.stdout)
        self.assertIn('on otherbox: OMA_RUNNER_PAIR_COMPUTERS=1 oma-runner pair testhost', done.stdout)
        self.assertIn('on testhost: OMA_RUNNER_PAIR_COMPUTERS=1 oma-runner pair testhost', done.stdout)

    def test_1b_aKeyWaitingInTheInboxIsNamedWithItsExactMove(self):
        # Taildrop on a Mac with Homebrew's tailscaled is fetched into ~/Inbox; Soma never takes keys from there.
        soma_has()
        inbox = work / 'Inbox'
        inbox.mkdir(exist_ok=True)
        old, new = inbox / 'soma-runner-otherbox.json', inbox / 'soma-runner-otherbox (1).json'
        old.write_text('{}')
        os.utime(old, (1, 1))
        new.write_text('{}')
        try:
            done = run('mesh')
            self.assertIn('on testhost: key waiting in ~/Inbox: mv "%s" %s/soma-runner-otherbox.json; rm "%s" (an older, replaced key)'
                          % (new, work / 'soma' / 'runners', old), done.stdout)
        finally:
            old.unlink()
            new.unlink()

    def test_2_devicesSaysWhoItLetsInByNameOnly(self):
        code, said = call('/devices')
        self.assertEqual(200, code, said)
        self.assertEqual(['phone'], said['devices'])
        self.assertNotIn('sha256', json.dumps(said))

    def test_3_pairingAComputerWithNoRoadBackFailsLoudly(self):
        soma_has('testhost')
        done = run('pair', 'otherbox')
        self.assertEqual(3, done.returncode, done.stdout + done.stderr)
        self.assertIn('ONE-WAY', done.stderr)
        self.assertIn('On otherbox run "OMA_RUNNER_PAIR_COMPUTERS=1 oma-runner pair testhost"', done.stderr)
        # The leg it did make stands, and says so by name.
        self.assertIn('otherbox', call('/devices')[1]['devices'])

    def test_4_withBothRoadsMeshIsClean(self):
        soma_has('testhost', 'otherbox')
        self.assertEqual(0, run('pair', 'testhost').returncode)  # this machine's own Soma, to its own runner
        done = run('pair', 'otherbox')
        self.assertEqual(0, done.returncode, done.stdout + done.stderr)
        clean = run('mesh')
        self.assertEqual(0, clean.returncode, clean.stdout + clean.stderr)
        self.assertIn('no missing legs', clean.stdout)


if __name__ == '__main__':
    unittest.main()
