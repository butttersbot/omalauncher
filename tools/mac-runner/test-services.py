#!/usr/bin/env python3
"""Owner-approved service restart (soma_services): every execution is mocked; launchctl, sudo and the process are
never touched. Owner keys are generated in memory per test; the trust file lives in a temporary directory."""
import base64, hashlib, importlib.util, json, os, pathlib, stat, tempfile, unittest
from unittest.mock import patch
from types import SimpleNamespace

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('soma_services', HERE / 'soma_services.py')
S = importlib.util.module_from_spec(spec)
spec.loader.exec_module(S)


def owner_key():
    private = ec.generate_private_key(ec.SECP256R1())
    der = private.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return private, hashlib.sha256(der).hexdigest(), base64.b64encode(der).decode()


def sign(private, message):
    return base64.b64encode(private.sign(message.encode(), ec.ECDSA(hashes.SHA256()))).decode()


class Clock:
    def __init__(self, t=1_800_000_000_000):
        self.t = t

    def __call__(self):
        return self.t


class Base(unittest.TestCase):
    def setUp(self):
        self.dir = pathlib.Path(tempfile.mkdtemp())
        self.private, self.key_id, self.public = owner_key()
        trust_dir = self.dir / 'Soma'
        trust_dir.mkdir(mode=0o755)
        self.trust_file = trust_dir / 'connections-owner-keys.json'
        self.trust_file.write_text(json.dumps({'version': 1, 'keys': [{'key_id': self.key_id, 'algorithm': 'ecdsa-p256-sha256',
                                                                     'public_key': self.public, 'label': 'Boss Fold', 'device': 'galaxy-z-fold8',
                                                                     'added_at': 1, 'revoked': False}]}))
        os.chmod(self.trust_file, 0o644)
        self.clock = Clock()
        self.backend = S.MockBackend()
        self.counts = {'working': 0, 'waiting': 0, 'queued': 0, 'background': 0}
        self.locks = self.dir / 'locks'
        self.locks.mkdir()
        self.probe_result = [S.mkcheck('soma_identity', 'soma', 'ok', 'pass')]
        self.control = S.Controller(self.backend, lambda: dict(self.counts), records=S.Records(self.dir / 'state'),
                                    trust=lambda: S.load_trust(self.trust_file, require_root=False), gate=S.WorkGate(self.locks),
                                    probe=lambda service: list(self.probe_result), state=lambda service: {'loaded': True, 'state': 'running', 'pid': 1},
                                    host='jacks-mac-pro', clock=self.clock, sleep=lambda s: None, spawn=lambda fn, *a: fn(*a))

    def approve_body(self, challenge, **over):
        body = {'approval': challenge['id'], 'nonce': challenge['nonce'], 'decision': 'allow', 'owner_key_id': self.key_id,
                'owner_signature': sign(self.private, challenge['payload'])}
        body.update(over)
        return body

    def op(self, result):
        return self.control.records.get_operation(result['operation']['id'])


class Challenges(Base):
    def test_payload_is_exact_and_nothing_runs_on_request(self):
        c = self.control.request('soma')
        self.assertEqual(c['payload'], '\n'.join(['soma-service-restart-v1', c['id'], c['nonce'], 'soma', 'jacks-mac-pro', str(c['expires_at'])]))
        self.assertFalse(c['payload'].endswith('\n'))
        self.assertEqual(c['expires_at'], self.clock.t + 120_000)
        self.assertEqual(base64.b64decode(c['payload_b64']).decode(), c['payload'])
        self.assertTrue(c['key_required'])
        self.assertEqual(self.backend.calls, [])

    def test_unknown_service_is_refused(self):
        with self.assertRaises(S.Refused) as e:
            self.control.request('../bin/sh')
        self.assertEqual(e.exception.status, 404)

    def test_valid_signature_restarts_once_and_verifies(self):
        c = self.control.request('soma')
        result = self.control.approve('soma', self.approve_body(c))
        self.assertEqual(self.backend.calls, [('gui', 'soma')])
        op = self.op(result)
        self.assertEqual((op['stage'], op['verified'], op['service'], op['host'], op['approval']), ('restored', True, 'soma', 'jacks-mac-pro', c['id']))
        # Replay of the same signed answer is refused and runs nothing more.
        with self.assertRaises(S.Refused) as e:
            self.control.approve('soma', self.approve_body(c))
        self.assertEqual(e.exception.code, 'no_challenge')
        self.assertEqual(self.backend.calls, [('gui', 'soma')])

    def test_bare_key_owner_flags_or_unsigned_never_execute(self):
        for extra in ({'owner': True}, {'owner_signature': None}, {'owner_key_id': 'f' * 64}, {'force': True, 'owner_signature': ''}):
            c = self.control.request('soma')
            body = self.approve_body(c, **extra)
            if 'owner' in extra or 'force' in extra:
                body.pop('owner_signature')
            with self.assertRaises(S.Refused):
                self.control.approve('soma', body)
        self.assertEqual(self.backend.calls, [])

    def test_wrong_nonce_expired_substituted_and_tampered_are_refused_and_consumed(self):
        c = self.control.request('soma')
        with self.assertRaises(S.Refused) as e:
            self.control.approve('soma', self.approve_body(c, nonce='x' * 32))
        self.assertEqual(e.exception.code, 'nonce_mismatch')
        with self.assertRaises(S.Refused):  # consumed by the failed attempt: no second try with the right nonce
            self.control.approve('soma', self.approve_body(c))
        c = self.control.request('soma')
        self.clock.t += 120_001
        with self.assertRaises(S.Refused) as e:
            self.control.approve('soma', self.approve_body(c))
        self.assertEqual(e.exception.code, 'expired')
        c = self.control.request('soma')
        with self.assertRaises(S.Refused):  # a soma signature presented for syncthing
            self.control.approve('syncthing', self.approve_body(c))
        c = self.control.request('soma')
        tampered = c['payload'].replace('soma', 'syncthing')
        with self.assertRaises(S.Refused) as e:
            self.control.approve('soma', self.approve_body(c, owner_signature=sign(self.private, tampered)))
        self.assertEqual(e.exception.code, 'bad_signature')
        self.assertEqual(self.backend.calls, [])

    def test_a_new_request_invalidates_the_open_challenge(self):
        first = self.control.request('soma')
        self.control.request('soma')
        with self.assertRaises(S.Refused):
            self.control.approve('soma', self.approve_body(first))
        self.assertEqual(self.backend.calls, [])

    def test_deny_cancels_without_signature_or_execution(self):
        c = self.control.request('soma')
        self.assertEqual(self.control.approve('soma', {'approval': c['id'], 'nonce': c['nonce'], 'decision': 'deny'}), {'ok': True, 'cancelled': True})
        self.assertEqual(self.backend.calls, [])


class Plans(Base):
    def test_one_signature_binds_a_batch_but_each_endpoint_consumes_only_its_own(self):
        a, b = self.control.request('soma'), self.control.request('syncthing')
        plan = [a['payload_b64'], b['payload_b64']]
        signature = sign(self.private, S.plan_message(plan))
        for service, c in (('soma', a), ('syncthing', b)):
            self.control.approve(service, self.approve_body(c, owner_signature=signature, owner_payloads=plan))
        self.assertEqual(self.backend.calls, [('gui', 'soma'), ('gui', 'syncthing')])

    def test_plan_message_is_sorted_with_count_and_no_trailing_newline(self):
        self.assertEqual(S.plan_message(['b', 'a']), 'soma-service-restart-plan-v1\n2\na\nb')

    def test_plan_without_own_payload_duplicates_oversized_or_foreign_entries_are_refused(self):
        other = base64.b64encode(b'soma-service-restart-v1\nsvc-x\nn\nsoma\nh\n1').decode()
        big = base64.b64encode(b'soma-service-restart-v1\n' + b'x' * 2100).decode()
        foreign = base64.b64encode(b'sign-anything\n').decode()
        cases = lambda own: ([other], [own, own], [own, big], [own, foreign], [own] + [other] * 0 + [base64.b64encode(f'soma-service-restart-v1\n{i}'.encode()).decode() for i in range(16)])
        for i in range(5):
            c = self.control.request('soma')
            plan = cases(c['payload_b64'])[i]
            with self.assertRaises(S.Refused):
                self.control.approve('soma', self.approve_body(c, owner_payloads=plan, owner_signature=sign(self.private, S.plan_message(plan))))
        self.assertEqual(self.backend.calls, [])

    def test_single_payload_signature_does_not_satisfy_a_plan_and_vice_versa(self):
        c = self.control.request('soma')
        with self.assertRaises(S.Refused):
            self.control.approve('soma', self.approve_body(c, owner_payloads=[c['payload_b64']]))  # signature is over the payload, not the plan
        c = self.control.request('soma')
        with self.assertRaises(S.Refused):
            self.control.approve('soma', self.approve_body(c, owner_signature=sign(self.private, S.plan_message([c['payload_b64']]))))
        self.assertEqual(self.backend.calls, [])


class Trust(Base):
    def test_missing_untrusted_and_ready(self):
        self.assertEqual(S.load_trust(self.dir / 'nope.json').state, 'missing')
        self.assertEqual(S.load_trust(self.trust_file, require_root=False).state, 'ready')
        original_lstat = os.lstat
        def nonroot_lstat(path, *args, **kwargs):
            info = original_lstat(path, *args, **kwargs)
            return SimpleNamespace(st_mode=info.st_mode, st_uid=501, st_size=info.st_size)
        with patch.object(S.os, 'lstat', side_effect=nonroot_lstat):
            self.assertEqual(S.load_trust(self.trust_file, require_root=True).state, 'untrusted')
        os.chmod(self.trust_file, 0o666)
        self.assertIn('writable', S.load_trust(self.trust_file, require_root=False).detail)
        os.chmod(self.trust_file, 0o644)
        link = self.trust_file.parent / 'link.json'
        link.symlink_to(self.trust_file)
        self.assertIn('symlink', S.load_trust(link, require_root=False).detail)

    def test_key_id_must_be_the_spki_digest_and_curve_p256(self):
        data = json.loads(self.trust_file.read_text())
        data['keys'][0]['key_id'] = '0' * 64
        self.trust_file.write_text(json.dumps(data))
        self.assertEqual(S.load_trust(self.trust_file, require_root=False).state, 'untrusted')
        p384 = ec.generate_private_key(ec.SECP384R1()).public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        data['keys'][0].update(key_id=hashlib.sha256(p384).hexdigest(), public_key=base64.b64encode(p384).decode())
        self.trust_file.write_text(json.dumps(data))
        self.assertEqual(S.load_trust(self.trust_file, require_root=False).state, 'untrusted')

    def test_revoked_keys_are_ignored_and_no_trust_blocks_execution(self):
        data = json.loads(self.trust_file.read_text())
        data['keys'][0]['revoked'] = True
        self.trust_file.write_text(json.dumps(data))
        c = self.control.request('soma')
        with self.assertRaises(S.Refused) as e:
            self.control.approve('soma', self.approve_body(c))
        self.assertEqual(e.exception.status, 423)
        self.assertEqual(self.backend.calls, [])


class Work(Base):
    def test_active_waiting_queued_or_background_work_blocks(self):
        for kind in ('working', 'waiting', 'queued', 'background'):
            self.counts = {'working': 0, 'waiting': 0, 'queued': 0, 'background': 0, kind: 1}
            c = self.control.request('soma')
            op = self.op(self.control.approve('soma', self.approve_body(c)))
            self.assertEqual(op['stage'], 'blocked', kind)
        self.assertEqual(self.backend.calls, [])

    def test_unknown_work_blocks(self):
        self.control.work = lambda: None
        op = self.op(self.control.approve('soma', self.approve_body(self.control.request('soma'))))
        self.assertEqual((op['stage'], self.backend.calls), ('blocked', []))

    def test_a_held_build_or_walk_lock_blocks_and_admission_reopens(self):
        import fcntl
        lock = open(self.locks / 'walks.lock', 'a')
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            op = self.op(self.control.approve('soma', self.approve_body(self.control.request('soma'))))
            self.assertEqual((op['stage'], self.backend.calls), ('blocked', []))
        finally:
            fcntl.flock(lock, fcntl.LOCK_UN); lock.close()
        self.assertTrue(self.control.gate.admit())

    def test_admission_is_closed_during_the_restart(self):
        seen = []
        self.backend.kickstart_gui = lambda service: seen.append(self.control.gate.admit())
        self.control.approve('soma', self.approve_body(self.control.request('soma')))
        self.assertEqual(seen, [False])
        self.assertTrue(self.control.gate.admit())


class Modes(Base):
    def test_privileged_service_without_helper_is_blocked_not_pretended(self):
        self.probe_result = [S.mkcheck('x', 'x', 'ok', 'pass')]
        S_verifiable = S.verifiable
        S.verifiable = lambda s: True
        try:
            op = self.op(self.control.approve('tailscaled', self.approve_body(self.control.request('tailscaled'))))
        finally:
            S.verifiable = S_verifiable
        self.assertEqual((op['stage'], op['verified']), ('blocked', False))
        self.assertTrue(any(c['code'] == 'privileged_helper_missing' for c in op['checks']))
        self.assertEqual(self.backend.calls, [])

    def test_service_without_capability_probe_is_blocked_before_any_restart(self):
        op = self.op(self.control.approve('canvas', self.approve_body(self.control.request('canvas'))))
        self.assertEqual((op['stage'], self.backend.calls), ('blocked', []))

    def test_failed_verification_is_failed_not_restored(self):
        self.probe_result = [S.mkcheck('soma_identity', 'soma', 'wrong_identity', 'fail')]
        op = self.op(self.control.approve('soma', self.approve_body(self.control.request('soma'))))
        self.assertEqual((op['stage'], op['verified']), ('failed', False))
        self.assertTrue(op.get('incident'))
        incident = json.loads((self.dir / 'state' / 'incidents' / 'newest.json').read_text())
        self.assertEqual(set(incident) - {'operation'}, {'schema', 'id', 'time', 'last_seen', 'occurrences', 'service', 'host', 'stage',
                                                         'summary', 'root_cause', 'checks', 'next_steps', 'checks_truncated'})
        self.assertEqual((incident['schema'], incident['service'], incident['host'], incident['checks_truncated']), (1, 'soma', 'jacks-mac-pro', False))
        for check in incident['checks']:
            self.assertEqual(set(check), {'check', 'target', 'code', 'result', 'fields', 'stderr_tail'})
        self.assertTrue(all(set(step) == {'actor', 'action', 'reason'} and step['actor'] in ('agent', 'owner') for step in incident['next_steps']))

    def test_reachable_but_unverified_is_never_restored(self):
        self.probe_result = [S.mkcheck('soma_probe', 'soma', 'reachable_unverified', 'unverified')]
        op = self.op(self.control.approve('soma', self.approve_body(self.control.request('soma'))))
        self.assertEqual((op['stage'], op['verified']), ('failed', False))

    def test_runner_self_restart_journals_then_exits_and_resume_verifies(self):
        result = self.control.approve('runner', self.approve_body(self.control.request('runner')))
        self.assertEqual(self.backend.calls, [('exit', 'runner')])
        self.assertEqual(self.op(result)['stage'], 'verifying')
        self.probe_result = [S.mkcheck('runner_self', 'runner', 'ok', 'pass')]
        self.control.resume()
        self.assertEqual((self.op(result)['stage'], self.op(result)['verified']), ('restored', True))


class Records(Base):
    def test_records_are_private_redacted_and_never_hold_nonces_or_signatures(self):
        self.probe_result = [S.mkcheck('soma_identity', 'soma', 'unreachable', 'fail', {'note': 'token=supersecret1'}, stderr='Authorization: Bearer abcdefghijklmnop')]
        c = self.control.request('soma')
        result = self.control.approve('soma', self.approve_body(c))
        root = self.dir / 'state'
        blob = ''.join(p.read_text() for p in root.rglob('*.json'))
        for secret in (c['nonce'], 'abcdefghijklmnop', 'supersecret1', self.approve_body(c)['owner_signature'][:20]):
            self.assertNotIn(secret, blob)
        for path in root.rglob('*'):
            mode = stat.S_IMODE(path.stat().st_mode)
            self.assertEqual(mode, 0o700 if path.is_dir() else 0o600, path)
        self.assertTrue((root / 'incidents' / 'newest.json').exists())
        self.assertIsNone(self.control.records.get_operation('../../etc/passwd'))
        self.assertIsNotNone(self.op(result))

    def test_inventory_lists_the_fixed_allowlist_and_honest_restart_support(self):
        inv = self.control.inventory()
        self.assertEqual([s['id'] for s in inv['services']], list(S.SERVICES))
        self.assertEqual(inv['owner_trust']['state'], 'ready')
        by = {s['id']: s for s in inv['services']}
        self.assertTrue(by['soma']['restart']['supported'])
        self.assertEqual(by['tailscaled']['restart'].get('blocked_reason'), 'privileged_helper_missing')
        self.assertEqual(by['canvas']['restart'].get('blocked_reason'), 'no_capability_probe')
        self.assertNotIn('nonce', json.dumps(inv))


class Fake:
    def __init__(self, **kw): self.__dict__.update(kw)


class Probes(unittest.TestCase):
    def test_launchctl_print_keeps_only_whitelisted_fields(self):
        out = ('gui/501/dev.omalauncher.soma.serve = {\n\tstate = running\n\tpid = 4242\n\tlast exit code = 0\n'
               '\tenvironment = {\n\t\tOPENAI_API_KEY => sk-livelivelivelive\n\t\tHOME => /Users/x\n\t}\n}\n')
        got = S.launchd_state('soma', runner=lambda *a, **k: Fake(returncode=0, stdout=out, stderr=''))
        self.assertEqual(got, {'loaded': True, 'state': 'running', 'pid': 4242, 'last_exit': 0})

    def test_oversized_or_incomplete_200_is_reachable_unverified_not_down(self):
        os.environ['OMA_RUNNER_SOMA_SERVICE'] = 'http://127.0.0.1:1'
        try:
            for why in ('oversized reply', 'incomplete or non-JSON reply'):
                def fetch(url, why=why): raise S.Unverified(why)
                (check,) = S.capability_probe('soma', fetch=fetch)
                self.assertEqual((check['code'], check['result']), ('reachable_unverified', 'unverified'))
            def down(url): raise OSError('connection refused')
            self.assertEqual(S.capability_probe('soma', fetch=down)[0]['result'], 'fail')
        finally:
            del os.environ['OMA_RUNNER_SOMA_SERVICE']

    def test_real_http_200_cut_short_oversized_or_not_json_is_unverified(self):
        import http.server, threading
        bodies = {'/short': (b'{"service": "soma-serve"', 100), '/big': (b'{"a": "' + b'x' * 300_000 + b'"}', None),
                  '/list': (b'[1, 2]', None), '/ok': (b'{"service": "soma-serve"}', None)}
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                body, length = bodies[self.path]
                self.send_response(200); self.send_header('Content-Length', str(length or len(body))); self.end_headers()
                self.wfile.write(body)
            def log_message(self, *a): pass
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        base = 'http://127.0.0.1:%d' % server.server_address[1]
        try:
            for path in ('/short', '/big', '/list'):
                with self.assertRaises(S.Unverified, msg=path):
                    S.http_json(base + path, timeout=2)
            self.assertEqual(S.http_json(base + '/ok'), (200, {'service': 'soma-serve'}))
        finally:
            server.shutdown(); server.server_close()

    def test_unprobed_service_is_structured_unsupported(self):
        (check,) = S.capability_probe('canvas')
        self.assertEqual((check['check'], check['target'], check['code'], check['result']), ('canvas_capability', 'canvas', 'no_capability_probe', 'unsupported'))


class Redaction(unittest.TestCase):
    def test_cookie_and_authorization_lines_are_removed_whole(self):
        text = 'ok line\nCookie: a=1; session=abcdef123456\nSet-Cookie: sid=zzzzyyyyxxxx; Path=/\nauthorization: Basic dXNlcjpwYXNz\nlast'
        out = S.redact(text)
        for secret in ('abcdef123456', 'zzzzyyyyxxxx', 'dXNlcjpwYXNz', 'a=1'):
            self.assertNotIn(secret, out)
        self.assertIn('ok line', out); self.assertIn('last', out)

    def test_quoted_json_token_assignments_are_removed(self):
        out = S.redact('{"access_token": "ya29.abc def", "refresh_token":"1//xyz", "expires_in": 3599}')
        for secret in ('ya29.abc def', '1//xyz', 'abc def'):
            self.assertNotIn(secret, out)
        self.assertIn('3599', out)

    def test_stderr_is_redacted_before_the_tail_is_taken(self):
        # The cut lands inside the secret: tailing first would keep its unredactable second half.
        stderr = 'x' * 500 + ' token=' + 'S' * 20 + 'TAILSECRET'
        tail = S.stderr_tail(stderr, limit=25)
        self.assertNotIn('TAILSECRET', tail)
        self.assertNotIn('SSSS', tail)


class LiveBackendIsNeverUsedHere(unittest.TestCase):
    def test_mock_backend_is_not_live(self):
        self.assertFalse(S.MockBackend.live)
        self.assertTrue(S.LiveBackend.live)


if __name__ == '__main__':
    unittest.main()
