#!/usr/bin/env python3
"""Check installer preflight without a phone, private key, APK or install side effect."""
import pathlib
import subprocess
import unittest
from unittest.mock import patch

SOURCE = pathlib.Path(__file__).with_name('oma-install.py').read_text().split('\ndef install_self():', 1)[0]
CERT = 'a' * 64


class InstallerPreflightTest(unittest.TestCase):
    def preflight(self, package, version, extra=()):
        def fake_run(args, **kwargs):
            output = ("package: name='%s' versionCode='%s' versionName='test'" % (package, version)
                      if 'badging' in args else 'Signer #1 certificate SHA-256 digest: ' + CERT)
            return subprocess.CompletedProcess(args, 0, output, '')
        scope = {'__name__': 'installer_preflight_test'}
        with patch('sys.argv', ['oma-install', '--self', *extra]), \
             patch('pathlib.Path.read_text', autospec=True, side_effect=lambda p: '{"serial":"fixture"}' if p.name == 'device.json' else CERT), \
             patch('pathlib.Path.is_file', return_value=True), \
             patch('subprocess.run', side_effect=fake_run):
            exec(compile(SOURCE, 'oma-install.py', 'exec'), scope)
        return scope

    def test_launcher_version_survives_certificate_check(self):
        result = self.preflight('dev.omalauncher.boss', 97)
        self.assertEqual(result['version'], 97)
        self.assertEqual(result['target'], 'dev.omalauncher.boss')

    def test_separate_messages_app_keeps_its_own_version(self):
        result = self.preflight('dev.omalauncher.messages', 3, ['--apk', '/tmp/messages.apk'])
        self.assertEqual(result['version'], 3)
        self.assertEqual(result['target'], 'dev.omalauncher.messages')

    def test_separate_notes_app_keeps_its_own_version(self):
        result = self.preflight('dev.omalauncher.notes', 1, ['--apk', '/tmp/notes.apk'])
        self.assertEqual(result['version'], 1)
        self.assertEqual(result['target'], 'dev.omalauncher.notes')

    def test_unrelated_package_is_refused(self):
        with self.assertRaises(SystemExit):
            self.preflight('other.app', 500, ['--apk', '/tmp/other.apk'])


if __name__ == '__main__':
    unittest.main()
