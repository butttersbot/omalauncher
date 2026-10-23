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
        self.assertTrue(t.view()['read_only'])
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

    def test_mirror_cannot_start_a_second_claude(self):
        self.write(self.row())
        t, = self.sync()
        self.assertEqual('refused', self.m['take'](t, 'do work')['taken'])
        self.assertIsNone(t.worker)
        self.assertEqual([], t.queue)

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

    def test_http_rejects_reply_to_mirror(self):
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
        request.do_POST()
        self.assertEqual(409, answers[0][0])
        self.assertFalse(answers[0][1]['ok'])
        self.assertEqual([], t.queue)

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
