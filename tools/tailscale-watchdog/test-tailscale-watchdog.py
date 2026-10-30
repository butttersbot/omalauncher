#!/usr/bin/env python3
"""The watchdog against a fake tailscale that records what it is asked. No real Tailscale is touched."""
import json, os, pathlib, plistlib, re, stat, subprocess, tempfile, time, unittest

HERE = pathlib.Path(__file__).resolve().parent
FAKE = """#!/bin/sh
echo "$@" >> "$FAKE_DIR/asked"
if [ "$1" = status ]; then cat "$FAKE_DIR/status"; exit 0; fi
if [ "$1" = up ]; then
    [ -e "$FAKE_DIR/up-hangs" ] && exec sleep 60
    [ -e "$FAKE_DIR/up-refused" ] && { echo "Access denied: prefs write access denied" >&2; exit 1; }
    cp "$FAKE_DIR/status-after-up" "$FAKE_DIR/status"
    exit 0
fi
exit 3
"""
FAKE_MAIL = """#!/bin/sh
[ -e "$FAKE_DIR/mail-down" ] && exit 1
echo "$@" >> "$FAKE_DIR/mailed"
"""


def status(state, node_key=True, expired=False, peer_expired=False, peer_state=None):
    """Real `status --json` layout: two spaces at the top, four in Self, deeper for peers."""
    body = {'Version': '1', 'BackendState': state, 'HaveNodeKey': node_key, 'AuthURL': '', 'Self': {'HostName': 'x', **({'Expired': True} if expired else {})},
            'Peer': {'a': {'HostName': 'p', **({'Expired': True} if peer_expired else {}), **({'BackendState': peer_state} if peer_state else {})}}}
    return json.dumps(body, indent=2)


class Watchdog(unittest.TestCase):
    def setUp(self):
        self.dir = pathlib.Path(tempfile.mkdtemp()).resolve()
        fake = self.dir / 'tailscale'
        fake.write_text(FAKE); fake.chmod(0o755)
        (self.dir / 'bin').mkdir()
        mail = self.dir / 'bin/tmsg'
        mail.write_text(FAKE_MAIL); mail.chmod(0o755)
        (self.dir / 'status-after-up').write_text(status('Running'))
        self.env = {'PATH': f"{self.dir / 'bin'}:/usr/bin:/bin", 'HOME': str(self.dir), 'FAKE_DIR': str(self.dir), 'TAILSCALE_WATCHDOG_BIN': str(fake),
                    'TAILSCALE_WATCHDOG_HOME': str(self.dir / 'home'), 'TAILSCALE_WATCHDOG_UP_WAIT': '1'}

    def run_it(self, *args):
        return subprocess.run(['/bin/sh', str(HERE / 'tailscale-watchdog'), *args], capture_output=True, text=True, timeout=20, env=self.env)

    def asked(self):
        f = self.dir / 'asked'
        return f.read_text().splitlines() if f.exists() else []

    def mailed(self):
        f = self.dir / 'mailed'
        return f.read_text() if f.exists() else ''

    def set(self, text):
        (self.dir / 'status').write_text(text)

    def test_stopped_runs_a_bare_up_with_no_flag_and_says_so(self):
        self.set(status('Stopped'))
        r = self.run_it('check')
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual([a for a in self.asked() if not a.startswith('status')], ['up'])  # exactly `up`: a flag would make it not the safe case
        self.assertIn('brought back', r.stdout)
        self.assertIn('brought back', self.mailed())

    def test_running_does_nothing_and_a_peers_fields_dont_count(self):
        self.set(status('Running', peer_state='Stopped', peer_expired=True))
        self.run_it('check')
        self.assertEqual(self.asked(), ['status --json'])
        self.assertEqual(self.mailed(), '')

    def test_a_login_is_never_started_only_reported_and_only_once(self):
        for text in (status('NeedsLogin'), status('NeedsMachineAuth'), status('Stopped', node_key=False), status('Stopped', expired=True)):
            self.set(text)
            for f in ('home/last-state', 'home/unsent'):
                (self.dir / f).unlink(missing_ok=True)
            (self.dir / 'asked').unlink(missing_ok=True)
            first = self.run_it('check')
            second = self.run_it('check')
            self.assertNotIn('up', [a.split()[0] for a in self.asked()], text)
            self.assertIn('sign in', first.stdout + self.mailed())
            self.assertEqual(second.stdout, '')

    def test_a_peers_expired_key_doesnt_stop_it(self):
        self.set(status('Stopped', peer_expired=True))
        self.run_it('check')
        self.assertIn('up', self.asked())

    def test_paused_leaves_it_alone_and_can_be_resumed_by_a_command_or_the_marker(self):
        self.set(status('Stopped'))
        self.run_it('pause')
        self.assertTrue((self.dir / 'home/paused').exists())
        self.run_it('check')
        self.assertEqual(self.asked(), [])
        self.assertIn('paused', self.run_it('status').stdout)
        self.run_it('resume')
        self.run_it('check')
        self.assertIn('up', self.asked())
        (self.dir / 'status').write_text(status('Stopped'))
        (self.dir / 'asked').unlink()
        (self.dir / 'home/paused').write_text('')  # the marker alone, by hand
        self.run_it('check')
        self.assertEqual(self.asked(), [])

    def test_a_refused_up_is_reported_and_tried_again_next_time(self):
        self.set(status('Stopped'))
        (self.dir / 'up-refused').write_text('')
        r = self.run_it('check')
        self.assertIn("didn't bring it back", r.stdout)
        self.assertIn('Access denied', r.stdout)
        (self.dir / 'up-refused').unlink()
        self.run_it('check')
        self.assertEqual([a for a in self.asked() if a == 'up'], ['up', 'up'])

    def test_a_hung_up_is_cut_off(self):
        self.set(status('Stopped'))
        (self.dir / 'up-hangs').write_text('')
        started = time.monotonic()
        r = self.run_it('check')
        self.assertLess(time.monotonic() - started, 10)
        self.assertIn("didn't bring it back", r.stdout)

    def test_no_answer_or_starting_runs_nothing(self):
        for text in ('', 'The Tailscale GUI failed to start', status('Starting')):
            self.set(text)
            (self.dir / 'asked').unlink(missing_ok=True)
            self.run_it('check')
            self.assertEqual(self.asked(), ['status --json'])

    def test_what_couldnt_be_mailed_waits_for_a_way_out(self):
        self.set(status('NeedsLogin'))
        (self.dir / 'mail-down').write_text('')
        self.run_it('check')
        self.assertEqual(self.mailed(), '')
        self.assertIn('NeedsLogin', (self.dir / 'home/unsent').read_text())
        (self.dir / 'mail-down').unlink()
        self.set(status('Running'))
        self.run_it('check')
        self.assertIn('NeedsLogin', self.mailed())
        self.assertEqual((self.dir / 'home/unsent').read_text(), '')


class WhatGetsInstalled(unittest.TestCase):
    def test_it_runs_as_the_user_and_is_never_a_root_job(self):
        pro = plistlib.loads((HERE / 'dev.omalauncher.tailscale-watchdog.macpro.plist').read_bytes())
        self.assertEqual(pro['UserName'], 'jackkim')  # a system daemon: no UserName would be root running a file its user can change
        book = plistlib.loads((HERE / 'dev.omalauncher.tailscale-watchdog.macbook.plist').read_bytes())
        for job in (pro, book):
            self.assertEqual(job['StartInterval'], 120)
            self.assertEqual(job['ProgramArguments'][1:], ['check'])
        self.assertIn('User=neetzy', (HERE / 'tailscale-watchdog.service').read_text())
        self.assertEqual((HERE / 'tailscaled-restart-always.conf').read_text(), '[Service]\nRestart=always\nRestartSec=5\n')

    def test_no_flag_or_other_tailscale_command_is_in_the_script(self):
        code = [line for line in (HERE / 'tailscale-watchdog').read_text().splitlines() if not line.strip().startswith('#')]
        asked = set()
        for line in code:
            asked |= {found.strip() for found in re.findall(r'\bts ([a-z][a-z -]*)', line)}
        self.assertEqual(asked, {'status --json', 'up'})
        self.assertFalse([line for line in code if 'TS"' in line and 'ts()' not in line and 'TS=' not in line])


if __name__ == '__main__':
    unittest.main()
