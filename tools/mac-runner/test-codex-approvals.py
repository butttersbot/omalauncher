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
        self.thread = types.SimpleNamespace(meta={'project': str(root), 'kind': 'codex'}, pending={}, worker=object(), stopped=False)
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


if __name__ == '__main__':
    unittest.main(verbosity=2)
