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


    def test_status_falls_back_when_adb_hangs(self):
        full = pathlib.Path(__file__).with_name('oma-install.py').read_text()
        for hanging in ('start-server', 'connect', 'dumpsys', 'cat'):
            calls = []
            def fake_run(args, **kwargs):
                calls.append((args, kwargs['timeout']))
                if args[0] == 'adb':
                    if hanging in args:
                        raise subprocess.TimeoutExpired(args, kwargs['timeout'])
                    output = 'fixture' if 'getprop' in args else 'versionCode=173'
                else:
                    output = ("package: name='dev.omalauncher.boss' versionCode='174'" if 'badging' in args
                              else 'Signer #1 certificate SHA-256 digest: ' + CERT)
                return subprocess.CompletedProcess(args, 0, output, '')
            def read(p):
                return {'device.json':'{"serial":"fixture"}', 'install-port':'12345',
                        'pending-install.json':'{"version":174,"log":"fixture.log"}',
                        'state.json':'{"status":"installed","version":173}'}.get(p.name, CERT)
            with self.subTest(hanging=hanging), patch('sys.argv', ['oma-install', '--status']), \
                 patch('pathlib.Path.read_text', autospec=True, side_effect=read), \
                 patch('pathlib.Path.is_file', return_value=True), \
                 patch('pathlib.Path.exists', return_value=True), \
                 patch('concurrent.futures.ThreadPoolExecutor') as pool, \
                 patch('subprocess.run', side_effect=fake_run):
                pool.return_value.__enter__.return_value.map.return_value = []
                with self.assertRaises(SystemExit) as stopped:
                    exec(compile(full, 'oma-install.py', 'exec'), {'__name__':'status_test'})
                self.assertEqual(stopped.exception.code, 0)
                self.assertTrue(all(0 < timeout <= 3 for args, timeout in calls if args[0]=='adb'))
                self.assertFalse(any('install' in args or 'push' in args for args, _ in calls))


if __name__ == '__main__':
    unittest.main()
