"""Isolated native Claude transcript discovery: no model or real owner files."""
import ast
import json
import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch


class DiscoveryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        with patch.dict(os.environ, {'OMA_RUNNER_STATE': str(self.root / 'state')}):
            p = Path(__file__).with_name('oma-runner')
            tree = ast.parse(p.read_text())
            tree.body.pop()  # main()
            self.m = {'__name__': 'test_runner', '__file__': str(p)}
            exec(compile(tree, str(p), 'exec'), self.m)
        self.m['CLAUDE_PROJECTS'] = self.root / 'projects'
        self.sid = '11111111-1111-4111-8111-111111111111'
        self.file = self.root / 'projects' / '-project' / (self.sid + '.jsonl')
        self.file.parent.mkdir(parents=True)

    def row(self, uid='u1', kind='user', content='Hello', **extra):
        return dict(type=kind, sessionId=self.sid, uuid=uid, cwd=str(self.root),
                    message={'content': content, 'model': 'claude-test'}, **extra)

    def write(self, *rows, mode='w'):
        with self.file.open(mode) as f:
            for row in rows:
                f.write(json.dumps(row) + '\n')

    def sync(self):
        self.m['sync_claude']()
        return list(self.m['threads'].values())

    def test_import_append_restart_and_partial_line(self):
        self.write(self.row(), self.row('a1', 'assistant', [{'type': 'text', 'text': 'Answer'}, {'type': 'thinking', 'thinking': 'SECRET'}]))
        t, = self.sync()
        self.assertEqual(['note', 'message', 'text'], [e['type'] for e in t.events])
        self.assertEqual('Answer', t.events[-1]['text'])
        self.assertTrue(t.view()['external'])
        self.assertFalse(t.view()['read_only'])
        self.sync()
        self.assertEqual(3, len(t.events))
        partial = json.dumps(self.row('u2', content='Next'))
        with self.file.open('a') as f:
            f.write(partial[:30])
        self.sync()
        self.assertEqual(3, len(t.events))
        with self.file.open('a') as f:
            f.write(partial[30:] + '\n')
        self.sync()
        self.assertEqual('Next', t.events[-1]['text'])
        self.m['threads'].clear()
        self.m['load']()
        t, = self.sync()
        self.assertEqual(4, len(t.events))

    def test_private_payloads_subagents_and_foreign_session_excluded(self):
        self.write(self.row(), self.row('secret', content='SYSTEM', isMeta=True),
                   self.row('side', content='SIDE', isSidechain=True),
                   self.row('tool', content=[{'type': 'tool_result', 'content': 'SECRET'}]))
        t, = self.sync()
        self.assertEqual(['Hello'], [e['text'] for e in t.events if e['type'] == 'message'])

    def test_runner_owned_session_is_not_duplicated(self):
        self.write(self.row())
        t = self.m['Thread']({'id': 'owned', 'kind': 'claude', 'session': self.sid})
        self.m['threads']['owned'] = t
        self.assertEqual([t], self.sync())
        self.assertFalse(t.events)

    def test_old_session_ignored_but_recent_existing_session_visible(self):
        self.write(self.row())
        os.utime(self.file, (time.time() - 172800,) * 2)
        self.assertEqual([], self.sync())
        os.utime(self.file, None)
        self.assertEqual(1, len(self.sync()))

    def test_title_and_malformed_record(self):
        self.write(self.row(), {'type': 'custom-title', 'customTitle': 'My task', 'sessionId': self.sid})
        with self.file.open('a') as f:
            f.write('not json\n')
        t, = self.sync()
        self.assertEqual('My task', t.meta['name'])

    def test_discovered_session_resumes_through_existing_driver(self):
        self.write(self.row())
        t, = self.sync()
        self.m['AGENT'] = str(Path(__file__).with_name('fake-agent').resolve())
        # No real canvas token service or remote model in this test.
        self.m['canvas_env'] = lambda thread: {}
        with self.m['changed']:
            answer = self.m['take'](t, 'hello from phone')
        self.assertEqual('now', answer['taken'])
        deadline = time.time() + 10
        while t.worker is not None and time.time() < deadline:
            time.sleep(.02)
        self.assertIsNone(t.worker)
        self.assertEqual(self.sid, t.meta['session'])
        self.assertTrue(any(e['type'] == 'result' for e in t.events))
        starts = [json.loads(line) for line in (self.root / 'starts.log').read_text().splitlines()]
        self.assertEqual(1, len(starts))
        self.assertTrue(starts[0]['resumed'])
        self.assertEqual('manual', starts[0]['mode'])
        self.assertEqual('stdio', starts[0]['prompt_tool'])
        deadline = time.time() + 5
        while t.kept is not None and time.time() < deadline:
            time.sleep(.02)
        self.assertIsNone(t.kept)

    def test_discovered_session_forwards_tool_approval(self):
        self.write(self.row())
        t, = self.sync()
        self.m['AGENT'] = str(Path(__file__).with_name('fake-agent').resolve())
        self.m['canvas_env'] = lambda thread: {}
        with self.m['changed']:
            self.m['take'](t, 'write')
        deadline = time.time() + 10
        while not t.pending and time.time() < deadline:
            time.sleep(.02)
        self.assertTrue(t.pending)
        self.assertFalse((self.root / 'written.txt').exists())
        with self.m['changed']:
            approval, entry = next(iter(t.pending.items()))
            code, result = self.m['decide'](t, approval, {'nonce': entry['nonce'], 'decision': 'allow'}, 'test-owner')
        self.assertEqual(200, code)
        deadline = time.time() + 10
        while (t.worker is not None or t.kept is not None) and time.time() < deadline:
            time.sleep(.02)
        self.assertIsNone(t.worker)
        self.assertIsNone(t.kept)
        self.assertTrue((self.root / 'written.txt').exists())
        self.assertEqual(self.sid, t.meta['session'])

    def test_phone_turn_deduplicates_native_transcript(self):
        self.write(self.row())
        t, = self.sync()
        driver = self.m['Claude'](t, 'Phone prompt')
        t.say = lambda event: None
        with self.m['changed']:
            t.emit('message', text='Phone prompt')
            driver.begin()
            driver.heard({'type': 'assistant', 'uuid': 'stream-a', 'message': {'id': 'api-a', 'content': [{'type': 'text', 'text': 'Phone answer'}]}})
        self.write(self.row(driver.uuid, content='Phone prompt'),
                   dict(self.row('disk-a', 'assistant', [{'type': 'text', 'text': 'Phone answer'}]), message={'id': 'api-a', 'content': [{'type': 'text', 'text': 'Phone answer'}]}), mode='a')
        self.sync()
        self.assertEqual(1, sum(e.get('text') == 'Phone prompt' for e in t.events))
        self.assertEqual(1, sum(e.get('text') == 'Phone answer' for e in t.events))

    def test_wrong_session_is_never_adopted(self):
        self.write(self.row())
        t, = self.sync()
        driver = self.m['Claude'](t, 'Hello')
        with self.assertRaises(ValueError):
            driver.heard({'type': 'system', 'subtype': 'init', 'session_id': 'another-session'})
        self.assertEqual(self.sid, t.meta['session'])

    def holder(self, t, status='busy', **extra):
        folder = self.root / 'sessions'
        folder.mkdir(exist_ok=True)
        pid = os.getpid()
        record = dict(pid=pid, sessionId=t.meta['session'], status=status,
                      procStart=self.m['claude_process_start'](pid))
        record.update(extra)
        (folder / ('%d.json' % pid)).write_text(json.dumps(record))
        return pid

    def test_busy_and_idle_open_sessions_refuse_before_queue(self):
        self.write(self.row())
        t, = self.sync()
        for state in ('busy', 'idle'):
            self.holder(t, state)
            result = self.m['take'](t, 'phone turn')
            self.assertEqual('refused', result['taken'])
            self.assertIn('Close that conversation', result['error'])
            self.assertEqual([], t.queue)
            self.assertIsNone(t.worker)

    def test_dead_and_reused_pid_do_not_hold_session(self):
        self.write(self.row())
        t, = self.sync()
        self.holder(t)
        with patch.object(self.m['os'], 'kill', side_effect=ProcessLookupError):
            self.assertIsNone(self.m['claude_holder_problem'](t))
        self.m['claude_process_start'] = lambda pid: 'new-start'
        self.assertIsNone(self.m['claude_holder_problem'](t))

    def test_unknown_identity_refuses_and_own_process_is_excluded(self):
        import types
        self.write(self.row())
        t, = self.sync()
        pid = self.holder(t)
        self.m['claude_process_start'] = lambda pid: None
        self.assertIsNotNone(self.m['claude_holder_problem'](t))
        t.process = types.SimpleNamespace(pid=pid)
        self.assertIsNone(self.m['claude_holder_problem'](t))

    def test_darwin_start_identity_and_unknown_format(self):
        self.write(self.row())
        t, = self.sync()
        self.m['LINUX'] = False
        self.m['claude_process_start'] = lambda pid: 'Thu Oct 22 13:44:00 2026'
        self.holder(t, procStart='Thu Oct 22 13:44:00 2026')
        self.assertIsNotNone(self.m['claude_holder_problem'](t))
        self.holder(t, procStart='Thu Oct 22 12:44:00 2026')
        self.assertIsNotNone(self.m['claude_holder_problem'](t))
        self.holder(t, procStart='unrecognized identity format')
        self.assertIsNotNone(self.m['claude_holder_problem'](t))

    def test_http_refuses_open_holder_without_starting_worker(self):
        import io
        self.write(self.row())
        t, = self.sync()
        self.holder(t)
        request = self.m['Runner'].__new__(self.m['Runner'])
        request.allowed = lambda: True
        request.parts = lambda: (['threads', t.meta['id'], 'messages'], {})
        payload = json.dumps({'text': 'do work'}).encode()
        request.headers = {'Content-Length': str(len(payload))}
        request.rfile = io.BytesIO(payload)
        answers = []
        request.answer = lambda code, body: answers.append((code, body))
        request.do_POST()
        self.assertEqual(409, answers[0][0])
        self.assertFalse(answers[0][1]['ok'])
        self.assertEqual([], t.queue)
        self.assertIsNone(t.worker)

    def test_no_peer_keys_are_read(self):
        self.write(self.row())
        t, = self.sync()
        folder = self.root / 'sessions'
        folder.mkdir()
        (folder / '123.peer.key').write_text('NOT JSON: DO NOT READ')
        self.assertIsNone(self.m['claude_holder_problem'](t))

    def test_equal_size_rewrite_and_record_filtering(self):
        self.write(self.row())
        t, = self.sync()
        self.write(self.row('u2', content='Other'))
        now = time.time() + 1
        os.utime(self.file, (now, now))
        self.sync()
        self.assertEqual(['Hello', 'Other'], [e['text'] for e in t.events if e['type'] == 'message'])
        self.write({'type': 'user', 'message': 7}, self.row('meta', content='SECRET', isCompactSummary=True), mode='a')
        self.sync()
        self.assertEqual(3, len(t.events))

    def test_http_accepts_reply_for_discovered_session(self):
        import io
        self.write(self.row())
        t, = self.sync()
        request = self.m['Runner'].__new__(self.m['Runner'])
        request.allowed = lambda: True
        request.parts = lambda: (['threads', t.meta['id'], 'messages'], {})
        payload = json.dumps({'text': 'do work'}).encode()
        request.headers = {'Content-Length': str(len(payload))}
        request.rfile = io.BytesIO(payload)
        answers = []
        request.answer = lambda code, body: answers.append((code, body))
        sent = []
        self.m['take'] = lambda thread, text: sent.append((thread, text)) or {'taken': 'now'}
        request.do_POST()
        self.assertEqual(200, answers[0][0])
        self.assertTrue(answers[0][1]['ok'])
        self.assertEqual([(t, 'do work')], sent)

    def test_large_tool_record_does_not_block_next_prompt(self):
        self.write(self.row(), self.row('tool', content=[{'type': 'tool_result', 'content': 'x' * (4 * 1024 * 1024)}]),
                   self.row('u2', content='Next'))
        t, = self.sync()
        self.assertEqual(['Hello', 'Next'], [e['text'] for e in t.events if e['type'] == 'message'])

    def test_symlink_and_subagent_files_ignored(self):
        self.write(self.row())
        real = self.file.with_suffix('.other')
        self.file.rename(real)
        self.file.symlink_to(real)
        self.assertEqual([], self.sync())


if __name__ == '__main__':
    unittest.main()
