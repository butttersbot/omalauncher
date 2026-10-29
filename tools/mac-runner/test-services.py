#!/usr/bin/env python3
"""Owner-approved service restart (soma_services): every execution is mocked; launchctl, sudo and the process are
never touched. Owner keys are generated in memory per test; the trust file lives in a temporary directory."""
import base64, hashlib, importlib.util, json, os, pathlib, stat, tempfile, unittest

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
        self.probe_result = [{'code': 'soma_identity', 'state': 'pass', 'detail': 'ok'}]
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
        self.assertEqual(S.load_trust(self.trust_file, require_root=True).state, 'untrusted')  # not root-owned in a test
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
        self.probe_result = [{'code': 'x', 'state': 'pass', 'detail': 'ok'}]
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
        self.probe_result = [{'code': 'soma_identity', 'state': 'fail', 'detail': 'no'}]
        op = self.op(self.control.approve('soma', self.approve_body(self.control.request('soma'))))
        self.assertEqual((op['stage'], op['verified']), ('failed', False))
        self.assertTrue(op.get('incident'))

    def test_runner_self_restart_journals_then_exits_and_resume_verifies(self):
        result = self.control.approve('runner', self.approve_body(self.control.request('runner')))
        self.assertEqual(self.backend.calls, [('exit', 'runner')])
        self.assertEqual(self.op(result)['stage'], 'verifying')
        self.probe_result = [{'code': 'runner_self', 'state': 'pass', 'detail': 'ok'}]
        self.control.resume()
        self.assertEqual((self.op(result)['stage'], self.op(result)['verified']), ('restored', True))


class Records(Base):
    def test_records_are_private_redacted_and_never_hold_nonces_or_signatures(self):
        self.probe_result = [{'code': 'soma_identity', 'state': 'fail', 'detail': 'Authorization: Bearer abcdefghijklmnop token=supersecret1'}]
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


class LiveBackendIsNeverUsedHere(unittest.TestCase):
    def test_mock_backend_is_not_live(self):
        self.assertFalse(S.MockBackend.live)
        self.assertTrue(S.LiveBackend.live)


if __name__ == '__main__':
    unittest.main()
