#!/usr/bin/env python3
"""The tailnet helper for the phone's runner (PHONE-RUNNER-SPEC, "Tailnet helper"): a real runner on the loopback, a fake
`tailscale` for status and Taildrop, the loopback whois file for who is calling and who an address is."""
import hashlib, json, os, pathlib, socket, subprocess, sys, tempfile, time, unittest, urllib.error, urllib.request

HERE = pathlib.Path(__file__).resolve().parent
work = pathlib.Path(tempfile.mkdtemp(prefix='phone-helper-'))
PHONE_KEY, LAPTOP_KEY = 'p' * 40, 'l' * 40
STATUS = {
    'Self': {'UserID': 1, 'DNSName': 'testhost.tail.ts.net.', 'TailscaleIPs': ['100.64.0.1'], 'OS': 'macOS', 'Online': True},
    'Peer': {
        'a': {'UserID': 1, 'DNSName': 'phone.tail.ts.net.', 'TailscaleIPs': ['100.64.0.2', 'fd7a:115c:a1e0::2'], 'OS': 'android', 'Online': True},
        'b': {'UserID': 1, 'DNSName': 'laptop.tail.ts.net.', 'TailscaleIPs': ['100.64.0.3'], 'OS': 'macOS', 'Online': False},
        'c': {'UserID': 9, 'DNSName': 'someone-elses.tail.ts.net.', 'TailscaleIPs': ['100.64.0.9'], 'OS': 'linux', 'Online': True},
    },
    'User': {'1': {'LoginName': 'boss@example.com'}, '9': {'LoginName': 'other@example.com'}},
}
fake = work / 'tailscale'
fake.write_text('#!/bin/sh\n'
                'if [ "$1" = status ]; then cat %s; exit 0; fi\n'
                'if [ "$1" = file ] && [ "$2" = cp ]; then echo "$4" >> %s; cp "$3" %s; exit 0; fi\n'
                'exit 1\n' % (work / 'status.json', work / 'sent.log', work / 'sent-file'))
fake.chmod(0o755)
(work / 'status.json').write_text(json.dumps(STATUS))
(work / 'keys.json').write_text(json.dumps({'devices': [
    {'name': 'phone', 'digest': 'sha256:' + hashlib.sha256(PHONE_KEY.encode()).hexdigest(), 'added': 0},
    {'name': 'laptop', 'digest': 'sha256:' + hashlib.sha256(LAPTOP_KEY.encode()).hexdigest(), 'added': 0}]}))
with socket.socket() as s:
    s.bind(('127.0.0.1', 0))
    port = s.getsockname()[1]
base = 'http://127.0.0.1:%d' % port
environment = {**os.environ, 'OMA_RUNNER_PORT': str(port), 'OMA_RUNNER_BIND': '127.0.0.1', 'OMA_RUNNER_STATE': str(work / 'state'),
               'OMA_RUNNER_LOGS': str(work / 'logs'), 'OMA_RUNNER_ROOTS': str(work), 'OMA_RUNNER_KEYS': str(work / 'keys.json'),
               'OMA_RUNNER_TOKEN': str(work / 'token'), 'OMA_RUNNER_WHOIS_FILE': str(work / 'whois.json'), 'OMA_RUNNER_PHONE': 'phone',
               'OMA_RUNNER_SELF': 'testhost', 'OMA_RUNNER_SOMA_DIR': str(work / 'soma-runners'), 'OMA_RUNNER_TAILSCALE': str(fake),
               'OMA_RUNNER_MAIL': str(work / 'mail'), 'OMA_RUNNER_CODEX_DISCOVER': '0', 'OMA_RUNNER_CLAUDE_DISCOVER': '0',
               'OMA_RUNNER_AGENT': str(HERE / 'fake-agent'), 'OMA_RUNNER_CODEX': str(HERE / 'fake-codex'), 'OMA_RUNNER_BOTS': str(work / 'bots'),
               'OMA_RUNNER_ARTIFACTS': str(work / 'artifacts'), 'OMA_RUNNER_PROJECTS': str(work / 'projects.json'), 'HOME': str(work)}


def caller(name):
    """Who the loopback says is calling (127.0.0.1), and who the tailnet addresses are."""
    (work / 'whois.json').write_text(json.dumps({'127.0.0.1': name, '100.64.0.2': 'phone', '100.64.0.3': 'laptop'}))


def call(method, path, body=None, key=PHONE_KEY):
    request = urllib.request.Request(base + path, method=method, data=None if body is None else json.dumps(body).encode(), headers={'Authorization': 'Bearer ' + key})
    try:
        with urllib.request.urlopen(request, timeout=10) as reply:
            return reply.status, json.loads(reply.read())
    except urllib.error.HTTPError as problem:
        return problem.code, json.loads(problem.read())


def pairing(device='laptop', **change):
    record = {'url': 'http://100.64.0.2:8478', 'key': 'x' * 40, 'name': 'Fold', 'runner': 'phone', **change}
    return {'device': device, 'name': 'soma-runner-phone.json', 'content': json.dumps(record)}


class Helper(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        caller('phone')
        cls.runner = subprocess.Popen([sys.executable, str(HERE / 'oma-runner'), 'serve'], env=environment, stdout=subprocess.PIPE, text=True)
        cls.runner.stdout.readline()

    @classmethod
    def tearDownClass(cls):
        cls.runner.terminate()
        cls.runner.wait()

    def setUp(self):
        caller('phone')

    def test_1_tailnetListsHisDevicesAndSaysWhoThePhoneIs(self):
        code, said = call('GET', '/phone-runner/tailnet')
        self.assertEqual(200, code, said)
        self.assertEqual('phone', said['self']['name'])
        self.assertEqual(['100.64.0.2', 'fd7a:115c:a1e0::2'], said['self']['addresses'])
        self.assertEqual('testhost', said['runner'])
        self.assertEqual(['testhost', 'laptop'], [d['name'] for d in said['devices']], 'his own devices only, the phone itself apart')
        self.assertFalse(said['devices'][1]['online'])

    def test_2_whoisNamesHisDevicesAndNoOneElse(self):
        self.assertEqual((200, {'ok': True, 'device': 'laptop', 'owner': True}), call('GET', '/phone-runner/whois?address=100.64.0.3'))
        code, said = call('GET', '/phone-runner/whois?address=100.64.0.9')
        self.assertEqual(404, code)
        self.assertFalse(said['owner'])
        for wrong in ('192.168.1.5', '8.8.8.8', 'laptop', ''):
            self.assertEqual(400, call('GET', '/phone-runner/whois?address=' + wrong)[0], wrong)

    def test_3_onlyThePhoneAsks(self):
        caller('laptop')
        for method, path, body in (('GET', '/phone-runner/tailnet', None), ('GET', '/phone-runner/whois?address=100.64.0.3', None), ('POST', '/phone-runner/taildrop', pairing())):
            self.assertEqual(403, call(method, path, body, key=LAPTOP_KEY)[0], path)
        # The phone's key from another device's address opens nothing at all.
        self.assertEqual(401, call('GET', '/phone-runner/tailnet')[0])

    def test_4_thePairingFileGoesByTaildropToHisOtherDevice(self):
        code, said = call('POST', '/phone-runner/taildrop', pairing())
        self.assertEqual((200, True), (code, said['ok']), said)
        self.assertEqual('laptop:', (work / 'sent.log').read_text().splitlines()[-1])
        self.assertEqual('phone', json.loads((work / 'sent-file').read_text())['runner'])

    def test_5_toThisMacItGoesStraightIntoSomasFolder(self):
        code, said = call('POST', '/phone-runner/taildrop', pairing('testhost'))
        self.assertEqual(200, code, said)
        kept = work / 'soma-runners' / 'soma-runner-phone.json'
        self.assertEqual('phone', json.loads(kept.read_text())['runner'])
        self.assertEqual(0o600, kept.stat().st_mode & 0o777)

    def test_6_anythingElseIsRefused(self):
        bad = [
            pairing('someone-elses'), pairing('phone'), pairing('nobody'),
            {**pairing(), 'name': 'soma-runner-laptop.json'}, {**pairing(), 'name': '../../.ssh/authorized_keys'},
            pairing(runner='laptop'), pairing(url='http://192.168.1.5:8478'), pairing(url='http://100.64.0.2:8478/x'),
            {**pairing(), 'content': json.dumps({'url': 'http://100.64.0.2:8478', 'key': 'x', 'name': 'Fold', 'runner': 'phone', 'more': 'y'})},
            {**pairing(), 'content': 'not json'}, {**pairing(), 'content': 'x' * 5000}, {**pairing(), 'extra': 1}, {'device': 'laptop'},
        ]
        for body in bad:
            code, said = call('POST', '/phone-runner/taildrop', body)
            self.assertEqual(400, code, (body, said))
        self.assertNotIn('x' * 40, (work / 'logs').joinpath('runner.log').read_text() if (work / 'logs' / 'runner.log').exists() else '')

    def test_7_soOftenAndNoMore(self):
        codes = [call('GET', '/phone-runner/whois?address=100.64.0.3')[0] for _ in range(40)]
        self.assertIn(429, codes)


if __name__ == '__main__':
    unittest.main()
