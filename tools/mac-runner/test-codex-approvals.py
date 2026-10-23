"""Focused isolated tests for the reconciled native Codex driver; no model, Keychain or live runner access."""
import ast
import json
import os
import shlex
from pathlib import Path
import tempfile
import types
import unittest


class ReconciledRunnerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.root = root
        keys = ('OMA_RUNNER_STATE', 'OMA_RUNNER_KEYS', 'OMA_RUNNER_TOKEN', 'OMA_RUNNER_LOGS', 'CODEX_HOME')
        old = {key: os.environ.get(key) for key in keys}
        self.addCleanup(lambda: [os.environ.pop(key, None) if value is None else os.environ.__setitem__(key, value) for key, value in old.items()])
        for key in keys:
            os.environ[key] = str(root / key.lower())
        path = Path(__file__).with_name('oma-runner')
        tree = ast.parse(path.read_text(), filename=str(path))
        # Load definitions without running the CLI, contacting services, or reading live thread state.
        assert isinstance(tree.body[-1], ast.Expr) and isinstance(tree.body[-1].value, ast.Call) and tree.body[-1].value.func.id == 'main'
        tree.body.pop()
        self.m = {'__file__': str(path), '__name__': 'candidate_under_test'}
        exec(compile(tree, str(path), 'exec'), self.m)
        self.events = []
        self.asks = []
        self.replies = []
        self.thread = types.SimpleNamespace(model=None, meta={'project': str(root), 'kind': 'codex'}, pending={}, worker=object(), stopped=False)
        self.thread.ask = lambda *args, **kw: self.asks.append((args, kw))
        self.thread.emit = lambda *args, **kw: self.events.append((args, kw))
        self.thread.status = lambda status: None
        self.thread.save = lambda: (root / 'thread.json').write_text(json.dumps(self.thread.meta))
        self.driver = self.m['Codex'](self.thread, 'test')
        self.driver.reply = lambda *args, **kw: self.replies.append((args, kw)) or True
        self.thread.driver = self.driver
        self.params = {'command': "/bin/zsh -lc 'git status --short'", 'cwd': str(root), 'threadId': 't1', 'turnId': 'turn1', 'itemId': 'item1'}

    def scope(self, **changes):
        return self.m['command_scope'](self.thread, {**self.params, **changes})

    def grant(self):
        scope = self.scope()
        self.thread.pending['a1'] = {'nonce': 'n1', 'request': 1, 'input': {}, 'command_scope': scope}
        return self.m['decide'](self.thread, 'a1', {'nonce': 'n1', 'decision': 'allow', 'remember': True}, 'owner-device')

    def test_exact_reuse_and_persistence(self):
        self.assertEqual(200, self.grant()[0])
        self.thread.meta = json.loads((self.root / 'thread.json').read_text())
        changed_ids = {**self.params, 'turnId': 'another', 'itemId': 'another'}
        self.driver.asked('item/commandExecution/requestApproval', 2, changed_ids)
        self.assertFalse(self.asks)
        self.assertEqual({'decision': 'accept'}, self.replies[-1][0][1])
        self.assertTrue(self.events[-1][1]['saved_command'])

    def test_full_access_auto_and_sandboxed_manual_apply_to_new_and_resumed_threads(self):
        requests = []
        self.driver.request = lambda method, params: requests.append((method, params))
        self.driver.notify = lambda *args: None
        for selected, policy, sandbox in ((None, 'never', 'danger-full-access'),
                                          ('auto', 'never', 'danger-full-access'),
                                          ('manual', 'untrusted', 'workspace-write')):
            self.thread.meta['mode'] = selected
            self.thread.meta['model'] = 'gpt-6.1-sol'
            for session in (None, 'native-session'):
                self.thread.meta['session'] = session
                self.driver.answered('initialize', {})
                method, rules = requests[-1]
                self.assertEqual('thread/resume' if session else 'thread/start', method)
                self.assertEqual(policy, rules['approvalPolicy'])
                self.assertEqual(sandbox, rules['sandbox'])
                self.assertEqual('user', rules['approvalsReviewer'])
                self.assertEqual('gpt-6.1-sol', rules['model'])

    def test_changes_and_compound_commands_ask(self):
        self.assertEqual(200, self.grant()[0])
        for changes in ({'command': "/bin/zsh -lc 'git status --porcelain'"}, {'additionalPermissions': {'network': True}}, {'cwd': str(self.root.parent)}):
            self.driver.asked('item/commandExecution/requestApproval', 2, {**self.params, **changes})
        self.assertEqual(3, len(self.asks))
        for command in ('git status; pwd', 'echo "$HOME"', 'sh -c pwd', 'git status\npwd'):
            self.assertIsNone(self.scope(command=shlex.join(['/bin/zsh', '-lc', command])))

    def test_nonce_and_stop(self):
        self.thread.pending['a1'] = {'nonce': 'n1', 'request': 1, 'input': {}, 'command_scope': self.scope()}
        self.assertEqual(403, self.m['decide'](self.thread, 'a1', {'nonce': 'wrong', 'decision': 'allow', 'remember': True})[0])
        self.assertNotIn('command_grants', self.thread.meta)
        self.assertEqual(200, self.grant()[0])
        self.thread.stopped = True
        self.driver.asked('item/commandExecution/requestApproval', 2, self.params)
        self.assertEqual({'decision': 'decline'}, self.replies[-1][0][1])

    def test_native_question_and_permission_paths_are_preserved(self):
        self.driver.asked('item/tool/requestUserInput', 3, {'questions': [{'id': 'q1', 'question': 'Which?'}]})
        args, extra = self.asks[-1]
        self.assertEqual('questions', args[2]['codex_request'])
        self.thread.pending['q1'] = {'nonce': 'n1', 'request': 3, 'input': args[2]}
        self.assertEqual(400, self.m['decide'](self.thread, 'q1', {'nonce': 'n1', 'decision': 'allow'})[0])
        self.assertIn('q1', self.thread.pending)
        self.assertTrue(self.driver.user_input(self.thread.pending['q1'], 'owner answer'))
        self.assertEqual({'answers': {'q1': {'answers': ['owner answer']}}}, self.replies[-1][0][1])
        entry = {'request': 4, 'input': {'codex_request': 'permissions', 'permissions': {'network': True}}}
        self.driver.answer(entry, False)
        self.assertEqual({'permissions': {}, 'scope': 'turn'}, self.replies[-1][0][1])

    def test_live_discovery_and_tool_support_are_retained(self):
        self.assertIn('CodexCatalog', self.m)
        self.assertIn('sync_codex', self.m)
        self.assertEqual(['hooks', 'guardian_approval'], self.m['CODEX_OFF'])
        self.assertIn('user_input', self.m['Codex'].__dict__)
        self.assertIn(Path.home() / 'Documents/Codex', self.m['ROOTS'])


    def conflict(self):
        self.old = '11111111-1111-4111-8111-111111111111'
        self.thread.meta.update(session=self.old, codex_external=True)
        self.sent = []
        self.m['codex_source_turn'] = lambda path, sid: ('finished', 'done1')
        self.driver.request = lambda method, params: self.sent.append((method, params))
        self.driver.calls[1] = 'thread/resume'
        return self.driver.heard({'id': 1, 'error': {'message': 'thread ' + self.old + ' already has an active writer'}})

    def test_active_writer_checks_turn_before_fork_and_persists_copy(self):
        self.assertFalse(self.conflict())
        self.assertEqual('thread/read', self.sent[-1][0])
        self.assertEqual(self.old, self.thread.meta['session'])
        self.driver.answered('thread/read', {'thread': {'id': self.old, 'path': '/native.jsonl'}})
        method, params = self.sent[-1]
        self.assertEqual('thread/fork', method)
        self.assertEqual('done1', params['lastTurnId'])
        self.assertEqual(self.old, params['threadId'])
        self.assertEqual('never', params['approvalPolicy'])
        self.assertEqual('danger-full-access', params['sandbox'])
        self.driver.answered('thread/fork', {'thread': {'id': '22222222-2222-4222-8222-222222222222'}})
        saved = json.loads((self.root / 'thread.json').read_text())
        self.assertEqual([self.old], saved['codex_origins'])
        self.assertEqual(self.old, saved['forked_from'])
        self.assertEqual('22222222-2222-4222-8222-222222222222', saved['session'])
        self.assertEqual('turn/start', self.sent[-1][0])
        self.assertFalse(self.thread.codex_fork_pending)
        self.assertTrue(any('own copy' in kw.get('text', '') for args, kw in self.events))

    def test_busy_writer_waits_until_completed_and_cancel_never_forks(self):
        from unittest.mock import patch
        self.conflict()
        states = iter([('busy', 'running'), ('finished', 'done1')])
        self.m['codex_source_turn'] = lambda path, sid: next(states)
        with self.m['changed'], patch.object(self.m['changed'], 'wait') as wait:
            self.driver.answered('thread/read', {'thread': {'id': self.old, 'path': '/native.jsonl'}})
        wait.assert_called_once()
        self.assertEqual('thread/resume', self.sent[-1][0])
        self.driver.answered('thread/read', {'thread': {'id': self.old, 'path': '/native.jsonl'}})
        self.assertEqual('thread/fork', self.sent[-1][0])
        self.driver = self.m['Codex'](self.thread, 'test')
        self.conflict()
        self.m['codex_source_turn'] = lambda path, sid: ('busy', 'running')
        with self.m['changed'], patch.object(self.m['changed'], 'wait', side_effect=lambda seconds: setattr(self.thread, 'stopped', True)):
            self.assertTrue(self.driver.answered('thread/read', {'thread': {'id': self.old, 'path': '/native.jsonl'}}))
        self.assertFalse(any(m == 'thread/fork' for m, p in self.sent))

    def test_closed_writer_during_wait_resumes_original_without_copy(self):
        from unittest.mock import patch
        self.conflict()
        self.m['codex_source_turn'] = lambda path, sid: ('busy', 'orphaned')
        with self.m['changed'], patch.object(self.m['changed'], 'wait'):
            self.driver.answered('thread/read', {'thread': {'id': self.old, 'path': '/native.jsonl'}})
        self.assertEqual('thread/resume', self.sent[-1][0])
        self.driver.answered('thread/resume', {'thread': {'id': self.old}})
        self.assertEqual('turn/start', self.sent[-1][0])
        self.assertEqual(self.old, self.thread.meta['session'])
        self.assertNotIn('codex_origins', self.thread.meta)

    def test_busy_timeout_unknown_state_and_invalid_copy_do_not_start_turn(self):
        self.conflict()
        self.driver.fork_deadline = 0
        self.m['codex_source_turn'] = lambda path, sid: ('busy', 'running')
        self.assertTrue(self.driver.answered('thread/read', {'thread': {'id': self.old, 'path': '/native.jsonl'}}))
        self.m['codex_source_turn'] = lambda path, sid: ('unknown', None)
        self.assertTrue(self.driver.answered('thread/read', {'thread': {'id': self.old, 'path': '/native.jsonl'}}))
        self.assertTrue(self.driver.answered('thread/fork', {'thread': {'id': self.old}}))
        self.assertEqual(self.old, self.thread.meta['session'])
        self.assertFalse(any(m == 'turn/start' for m, p in self.sent))

    def test_raw_markers_distinguish_quiet_busy_from_finished_and_partial(self):
        sid = '11111111-1111-4111-8111-111111111111'
        path = self.root / 'native.jsonl'
        header = dict(type='session_meta', payload={'id': sid})
        def event(kind, tid):
            return dict(type='event_msg', payload={'type': kind, 'turn_id': tid})
        rows = [header, event('task_complete', 'old'), event('task_started', 'new')]
        path.write_text(''.join(json.dumps(r) + '\n' for r in rows))
        os.utime(path, (1, 1))
        self.assertEqual(('busy', 'new'), self.m['codex_source_turn'](str(path), sid))
        with path.open('a') as f:
            f.write(json.dumps(event('task_complete', 'new')) + '\n')
        self.assertEqual(('finished', 'new'), self.m['codex_source_turn'](str(path), sid))
        with path.open('a') as f:
            f.write('{')
        self.assertEqual(('busy', None), self.m['codex_source_turn'](str(path), sid))
        self.assertEqual(('unknown', None), self.m['codex_source_turn'](str(path), 'wrong-session'))

    def test_other_resume_errors_keep_original_without_fork(self):
        self.conflict()
        self.driver.calls[2] = 'thread/resume'
        self.sent.clear()
        self.assertTrue(self.driver.heard({'id': 2, 'error': {'message': 'session not found'}}))
        self.assertEqual(self.old, self.thread.meta['session'])
        self.assertEqual([], self.sent)

    def test_normal_external_resume_rejects_unexpected_id(self):
        self.conflict()
        self.sent.clear()
        self.assertTrue(self.driver.answered('thread/resume', {'thread': {'id': 'unexpected'}}))
        self.assertEqual([], self.sent)
        self.assertEqual(self.old, self.thread.meta['session'])

    def test_writer_conflict_roundtrip_over_real_driver_pipes(self):
        import time
        old = '11111111-1111-4111-8111-111111111111'
        self.m['CODEX'] = str(Path(__file__).with_name('fake-codex').resolve())
        self.m['codex_features'] = lambda: []
        self.m['canvas_env'] = lambda thread: {}
        original = self.root / 'fake-native-writer'
        original.write_text('native holder remains untouched')
        t = self.m['Thread'](dict(id='pipe-test', kind='codex', session=old, codex_external=True,
                                  name='Test', project=str(self.root), status='idle', mode='auto'))
        self.m['take'](t, 'hello')
        deadline = time.time() + 8
        while t.worker is not None and time.time() < deadline:
            time.sleep(.01)
        self.assertIsNone(t.worker)
        self.assertNotEqual(old, t.meta['session'])
        self.assertEqual([old], t.meta['codex_origins'])
        self.assertTrue(any(e['type'] == 'result' and not e.get('failed') for e in t.events))
        self.assertEqual('native holder remains untouched', original.read_text())
        new = t.meta['session']
        self.m['take'](t, 'hello again')
        deadline = time.time() + 8
        while t.worker is not None and time.time() < deadline:
            time.sleep(.01)
        self.assertIsNone(t.worker)
        self.assertEqual(new, t.meta['session'])
        self.assertEqual(1, sum('own copy' in e.get('text', '') for e in t.events))

    def test_discovery_reuses_copy_and_never_reimports_origin(self):
        import time
        old = '11111111-1111-4111-8111-111111111111'
        new = '22222222-2222-4222-8222-222222222222'
        t = self.m['Thread'](dict(id='same-soma-thread', kind='codex', session=new, codex_external=True,
                                  codex_origins=[old], codex_seen=['prompt', 'reply'], name='Test', project=str(self.root), status='idle'))
        self.m['threads'][t.meta['id']] = t
        entry = dict(createdAt=int(time.time()) + 10, updatedAt=int(time.time()) + 10, cwd=str(self.root), name='Test')
        class Catalog:
            def call(inner, method, params):
                if method == 'thread/list':
                    return {'data': [dict(entry, id=old), dict(entry, id=new)]}
                self.assertEqual(new, params['threadId'])
                return {'data': [{'items': [{'type': 'userMessage', 'id': 'prompt', 'content': [{'type': 'text', 'text': 'Old prompt'}]},
                                           {'type': 'agentMessage', 'id': 'reply', 'text': 'Old reply'}]}]}
        self.m['sync_codex'](Catalog())
        self.assertEqual(1, len(self.m['threads']))
        self.assertEqual([], t.events)
        self.assertEqual(new, t.meta['session'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
