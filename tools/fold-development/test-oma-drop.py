#!/usr/bin/env python3
"""Real subprocess timeout/fallback checks; no phone or Taildrop data touched."""
import os
import pathlib
import subprocess
import tempfile
import time
import unittest

DROP = pathlib.Path(__file__).resolve().parents[2] / 'app/src/main/assets/oma-drop.sh'

class DropTimeoutTest(unittest.TestCase):
    def test_hung_adb_falls_back_to_bridge(self):
        for stage in ('start-server', 'connect', 'shell'):
            with self.subTest(stage=stage), tempfile.TemporaryDirectory() as directory:
                root = pathlib.Path(directory)
                (root/'.config/oma-development').mkdir(parents=True)
                (root/'.config/oma-development/install-port').write_text('12345')
                scripts = {
                    'adb': '#!/bin/bash\ncase " $* " in *" '+stage+' "*) exec sleep 60;; esac\ncase " $* " in *get-state*) echo device;; esac\n',
                    'curl': '#!/bin/bash\necho bridge >> "$HOME/calls"\necho \'{"ok":true,"files":[]}\'\n',
                }
                for name, content in scripts.items():
                    path = root/name
                    path.write_text(content)
                    path.chmod(0o755)
                started = time.monotonic()
                result = subprocess.run(['bash', str(DROP), 'get', '--keep'],
                    env={**os.environ, 'HOME':directory, 'PATH':directory+':'+os.environ['PATH']},
                    capture_output=True, text=True, timeout=8)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn('Nothing waiting.', result.stdout)
                self.assertEqual((root/'calls').read_text(), 'bridge\n')
                self.assertLess(time.monotonic()-started, 6)

if __name__ == '__main__':
    unittest.main()
