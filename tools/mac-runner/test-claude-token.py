#!/usr/bin/env python3
"""claude_token: Claude threads get Boss's long-lived token only from a file of his that nobody else can read."""
import ast
import os
import pathlib
import tempfile
import unittest

source = pathlib.Path(__file__).with_name('oma-runner').read_text()
module = ast.parse(source)
functions = ast.Module(body=[n for n in module.body if isinstance(n, ast.FunctionDef) and n.name in {'claude_token', 'clean_environment'}], type_ignores=[])


class ClaudeToken(unittest.TestCase):
    def setUp(self):
        self.dir = pathlib.Path(tempfile.mkdtemp())
        self.file = self.dir / 'claude-token'
        self.ns = {'os': os, 'CLAUDE_TOKEN': self.file}
        exec(compile(functions, 'oma-runner', 'exec'), self.ns)

    def test_noFileMeansNoToken(self):
        self.assertEqual({}, self.ns['claude_token']())

    def test_ownPrivateFileGivesTheToken(self):
        self.file.write_text('sk-ant-oat-example\n')
        self.file.chmod(0o600)
        self.assertEqual({'CLAUDE_CODE_OAUTH_TOKEN': 'sk-ant-oat-example'}, self.ns['claude_token']())

    def test_aFileOthersCanReadIsRefused(self):
        self.file.write_text('sk-ant-oat-example')
        self.file.chmod(0o644)
        self.assertEqual({}, self.ns['claude_token']())

    def test_anEmptyFileGivesNothing(self):
        self.file.write_text('\n')
        self.file.chmod(0o600)
        self.assertEqual({}, self.ns['claude_token']())

    def test_otherAgentsStillGetNoClaudeVariables(self):
        os.environ['CLAUDE_CODE_OAUTH_TOKEN'] = 'leaked'
        try:
            self.assertNotIn('CLAUDE_CODE_OAUTH_TOKEN', self.ns['clean_environment']())
        finally:
            del os.environ['CLAUDE_CODE_OAUTH_TOKEN']


if __name__ == '__main__':
    unittest.main()
