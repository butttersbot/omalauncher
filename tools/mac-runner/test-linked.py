#!/usr/bin/env python3
"""Linked questions (Boss, 2026-10-29: he answered a question in one thread and the same one still waited in another).
A question's answer given elsewhere is delivered only by an explicit link or the exact same card, and only with his
receipt signed by a pinned owner key; a question merely worded alike gets the hint and waits for his tap; an answer that
isn't one of the target's options is never delivered; a receipt answers a thread once."""
import base64, hashlib, json, os, pathlib, socket, subprocess, sys, tempfile, time, unittest, urllib.error, urllib.request
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

HERE = pathlib.Path(__file__).resolve().parent
work = pathlib.Path(tempfile.mkdtemp(prefix='linked-')).resolve()  # /var is a symlink on macOS; owner trust refuses a path through one
KEY = 'k' * 40
(work / 'Projects').mkdir()
(work / 'keys.json').write_text(json.dumps({'devices': [{'name': 'phone', 'digest': 'sha256:' + hashlib.sha256(KEY.encode()).hexdigest(), 'added': 0}]}))
(work / 'whois.json').write_text(json.dumps({'127.0.0.1': 'phone'}))
services = work / 'services'
services.mkdir()


def key_pair():
    private = ec.generate_private_key(ec.SECP256R1())
    der = private.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return private, hashlib.sha256(der).hexdigest(), base64.b64encode(der).decode()


OWNER, OWNER_ID, OWNER_PUB = key_pair()
STRANGER, STRANGER_ID, _ = key_pair()
(services / 'trust.json').write_text(json.dumps({'version': 1, 'keys': [{'key_id': OWNER_ID, 'algorithm': 'ecdsa-p256-sha256', 'public_key': OWNER_PUB}]}))
with socket.socket() as s:
    s.bind(('127.0.0.1', 0))
    port = s.getsockname()[1]
environment = {**os.environ, 'OMA_RUNNER_PORT': str(port), 'OMA_RUNNER_BIND': '127.0.0.1', 'OMA_RUNNER_STATE': str(work / 'state'),
               'OMA_RUNNER_LOGS': str(work / 'logs'), 'OMA_RUNNER_ROOTS': str(work / 'Projects'), 'OMA_RUNNER_KEYS': str(work / 'keys.json'),
               'OMA_RUNNER_TOKEN': str(work / 'token'), 'OMA_RUNNER_WHOIS_FILE': str(work / 'whois.json'), 'OMA_RUNNER_PHONE': 'phone',
               'OMA_RUNNER_MAIL': str(work / 'mail'), 'OMA_RUNNER_CODEX_DISCOVER': '0', 'OMA_RUNNER_CLAUDE_DISCOVER': '0',
               'OMA_RUNNER_SERVICES_TEST': str(services),
               'OMA_RUNNER_AGENT': str(HERE / 'fake-agent'), 'OMA_RUNNER_CODEX': str(HERE / 'fake-codex'), 'OMA_RUNNER_CURSOR': str(HERE / 'fake-cursor'),
               'OMA_RUNNER_GROK': str(work / 'no-grok'), 'OMA_RUNNER_BOTS': str(work / 'bots'), 'OMA_RUNNER_ARTIFACTS': str(work / 'artifacts'),
               'OMA_RUNNER_PROJECTS': str(work / 'projects.json'), 'HOME': str(work)}

canonical = lambda v: json.dumps(v, sort_keys=True, separators=(',', ':'), ensure_ascii=False)
OPTIONS = [{'label': 'Sync them', 'description': 'Every machine gets the skills'}, {'label': 'Keep them apart', 'description': 'Each machine its own'}]
CARD = {'questions': [{'question': 'Should the skills folder sync to the MacBook?', 'header': 'Skills', 'options': OPTIONS, 'multiSelect': False}]}
ALIKE = {'questions': [{'question': 'Do we sync the skills folder onto the MacBook too?', 'header': 'Skills sync', 'options': OPTIONS, 'multiSelect': False}]}
LINKED_A = {'questions': [{'question': 'Pick the theme for the new widget', 'header': 'Theme', 'options': OPTIONS, 'multiSelect': False}], 'metadata': {'source': 'link:widget-theme'}}
LINKED_B = {'questions': [{'question': 'Which way for the widget theme?', 'header': 'Widget', 'options': OPTIONS, 'multiSelect': False}], 'metadata': {'source': 'link:widget-theme'}}


def call(method, path, body=None):
    request = urllib.request.Request('http://127.0.0.1:%d%s' % (port, path), method=method, headers={'Authorization': 'Bearer ' + KEY, 'Content-Type': 'application/json'},
                                     data=None if body is None else json.dumps(body).encode())
    try:
        with urllib.request.urlopen(request, timeout=10) as reply:
            return reply.status, json.loads(reply.read())
    except urllib.error.HTTPError as problem:
        return problem.code, json.loads(problem.read())


def receipt(card, answer, signer=OWNER, signer_id=OWNER_ID, at=None, nonce='n'):
    text = canonical(card)
    r = {'v': 1, 'kind': 'question-answer', 'deviceId': signer_id, 'nonce': nonce, 'messageId': 'q1', 'at': at or int(time.time() * 1000),
         'conversation': 'phone:A', 'question': card['questions'][0]['header'], 'answers': {card['questions'][0]['question']: answer},
         'textSha256': hashlib.sha256(canonical({card['questions'][0]['question']: answer}).encode()).hexdigest(),
         'replyTo': {'messageId': 'q1', 'textSha256': hashlib.sha256(text.encode()).hexdigest(), 'text': text}}
    raw = canonical(r)
    return raw, base64.b64encode(signer.sign(raw.encode(), ec.ECDSA(hashes.SHA256()))).decode()


def asking(name, card):
    """A thread whose agent asks [card] and waits; its pending question as GET /questions shows it."""
    tid = call('POST', '/threads', {'name': name, 'project': str(work / 'Projects')})[1]['thread']['id']
    call('POST', '/threads/%s/messages' % tid, {'text': 'question-json:' + json.dumps(card)})
    for _ in range(100):
        mine = [q for q in call('GET', '/questions')[1]['questions'] if q['thread'] == tid]
        if mine:
            return tid, mine[0]
        time.sleep(0.05)
    raise AssertionError('no question from ' + name)


def answered(tid):
    for _ in range(100):
        events = call('GET', '/threads/%s/events' % tid)[1]['events']
        said = [e.get('text', '') for e in events if 'Answers:' in e.get('text', '')]
        if said:
            return said[-1], [e for e in events if e['type'] == 'approval_result']
        time.sleep(0.05)
    return None, []


class Linked(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.runner = subprocess.Popen([sys.executable, str(HERE / 'oma-runner'), 'serve'], env=environment, stdout=subprocess.PIPE, text=True)
        cls.runner.stdout.readline()
        cls.first, cls.q_first = asking('Phone A', CARD)
        cls.second, cls.q_second = asking('MacBook skills', CARD)
        cls.alike, cls.q_alike = asking('Skills, worded otherwise', ALIKE)
        cls.link_a, cls.q_link_a = asking('Widget A', LINKED_A)
        cls.link_b, cls.q_link_b = asking('Widget B', LINKED_B)

    @classmethod
    def tearDownClass(cls):
        cls.runner.terminate()
        cls.runner.wait()

    def deliver(self, q, raw, sig):
        return call('POST', '/threads/%s/approvals/%s' % (q['thread'], q['approval']), {'decision': 'allow', 'nonce': q['nonce'], 'receipt': raw, 'sig': sig})

    def test_1_theHintFindsTheSameQuestionAndOneWordedAlike(self):
        by = {q['thread']: q for q in call('GET', '/questions')[1]['questions']}
        same = {(s['thread'], s['match']) for s in by[self.first]['same_pending']}
        self.assertIn((self.second, 'exact'), same)
        self.assertIn((self.alike, 'similar'), same)  # tonight's case: worded differently, still hinted
        self.assertNotIn(self.link_a, {t for t, _ in same})
        self.assertIn((self.link_b, 'link'), {(s['thread'], s['match']) for s in by[self.link_a]['same_pending']})
        self.assertEqual(by[self.link_a]['link'], 'widget-theme')
        # The asking agent's metadata goes forward untouched (Soma reads metadata.source too).
        mine = call('GET', '/threads/' + self.link_a)[1]['thread']['pending'][0]
        self.assertEqual(mine['input']['metadata'], {'source': 'link:widget-theme'})
        # The thread view carries the same marks.
        pending = call('GET', '/threads/' + self.first)[1]['thread']['pending'][0]
        self.assertEqual(pending['fingerprint'], by[self.first]['fingerprint'])

    def test_2_refusedUnlessPinnedFreshAndOneOfTheOptions(self):
        raw, sig = receipt(CARD, 'Sync them', signer=STRANGER, signer_id=STRANGER_ID)
        self.assertEqual(409, self.deliver(self.q_second, raw, sig)[0])
        raw, sig = receipt(CARD, 'Sync them', at=int((time.time() - 8 * 86400) * 1000))
        self.assertEqual(409, self.deliver(self.q_second, raw, sig)[0])
        raw, sig = receipt(CARD, 'Delete everything')
        code, said = self.deliver(self.q_second, raw, sig)
        self.assertEqual(409, code)
        self.assertIn("not one of this question's options", said['error'])
        raw, sig = receipt(CARD, 'Sync them')
        self.assertEqual(409, self.deliver(self.q_second, raw, sig[:-8] + 'AAAAAAA=')[0])

    def test_3_aQuestionOnlyWordedAlikeWaitsForHisTap(self):
        raw, sig = receipt(CARD, 'Sync them', nonce='alike')
        code, said = self.deliver(self.q_alike, raw, sig)
        self.assertEqual(409, code)
        self.assertIn('waits for his own answer', said['error'])

    def test_4_theSameCardIsAnsweredWithHisReceiptOnceAndLinkedOnesByTheirKey(self):
        raw, sig = receipt(CARD, 'Sync them', nonce='exact')
        code, said = self.deliver(self.q_second, raw, sig)
        self.assertEqual(200, code, said)
        text, results = answered(self.second)
        self.assertIn('Sync them', text)
        self.assertTrue(results[-1].get('linked_from'))
        # Once per thread: the same receipt can't answer this thread's next question.
        call('POST', '/threads/%s/messages' % self.second, {'text': 'question-json:' + json.dumps(CARD)})
        for _ in range(100):
            again = [q for q in call('GET', '/questions')[1]['questions'] if q['thread'] == self.second]
            if again:
                break
            time.sleep(0.05)
        code, said = self.deliver(again[0], raw, sig)
        self.assertEqual(409, code)
        self.assertIn('already answered', said['error'])
        # Worded differently but sharing a link: delivered.
        raw, sig = receipt(LINKED_A, 'Keep them apart', nonce='link')
        code, said = self.deliver(self.q_link_b, raw, sig)
        self.assertEqual(200, code, said)
        self.assertIn('Keep them apart', answered(self.link_b)[0])


if __name__ == '__main__':
    unittest.main(verbosity=2)
