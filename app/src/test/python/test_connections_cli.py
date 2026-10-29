"""Read-only incident CLI: isolated temporary home, no service calls or real credentials."""
import importlib.util
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[2] / 'main/assets/oma-connections.py'
spec = importlib.util.spec_from_file_location('connections_cli', SCRIPT)
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)

class ConnectionsCli(unittest.TestCase):
    def test_reads_known_record_and_refuses_paths_and_restart(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(cli.Path, 'home', return_value=Path(folder)):
            directory = Path(folder) / '.local/share/soma/connections/incidents'
            directory.mkdir(parents=True)
            record = {'id':'123-abcdef0123456789','service':'runner','summary':'Blocked','last_seen':123}
            (directory/'newest.json').write_text(json.dumps(record))
            (directory/(record['id']+'.json')).write_text(json.dumps(record))
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(0, cli.main(['incident']))
            self.assertEqual(record, json.loads(output.getvalue()))
            with contextlib.redirect_stderr(io.StringIO()):
                for argv in [['incident','../secrets'], ['restart'], ['approve']]:
                    with self.assertRaises(SystemExit): cli.main(argv)
            with contextlib.redirect_stdout(io.StringIO()) as listed:
                self.assertEqual(0, cli.main(['list']))
            self.assertEqual(1, len(json.loads(listed.getvalue())))

    def test_refuses_symlink(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(cli.Path, 'home', return_value=Path(folder)):
            directory = Path(folder) / '.local/share/soma/connections/incidents'; directory.mkdir(parents=True)
            real = Path(folder)/'private.json'; real.write_text('{"secret":"not-a-real-token"}')
            (directory/'newest.json').symlink_to(real)
            with contextlib.redirect_stderr(io.StringIO()):self.assertEqual(1,cli.main(['incident']))

if __name__ == '__main__': unittest.main()
