#!/usr/bin/env python3
"""Owner-approved service restart (soma_services): every execution is mocked; launchctl, sudo and the process are
never touched. Owner keys are generated in memory per test; the trust file lives in a temporary directory."""
import base64, hashlib, importlib.util, json, os, pathlib, stat, sys, tempfile, threading, time, unittest, urllib.error, urllib.parse

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

HERE = pathlib.Path(__file__).resolve().parent

# No test may reach a real service: only ports a test opened itself on loopback are allowed.
import urllib.request as _request
OWN_PORTS = set()
_real_urlopen = _request.urlopen


def _guarded_urlopen(request, *a, **k):
    url = urllib.parse.urlsplit(request.full_url if hasattr(request, 'full_url') else request)
    if url.hostname != '127.0.0.1' or url.port not in OWN_PORTS:
        raise AssertionError('a test tried to reach a real service: ' + url.geturl())
    return _real_urlopen(request, *a, **k)


_request.urlopen = _guarded_urlopen
spec = importlib.util.spec_from_file_location('soma_services', HERE / 'soma_services.py')
S = importlib.util.module_from_spec(spec)
spec.loader.exec_module(S)


def owner_key():
    private = ec.generate_private_key(ec.SECP256R1())
    der = private.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return private, hashlib.sha256(der).hexdigest(), base64.b64encode(der).decode()


def sign(private, message):
    return base64.b64encode(private.sign(message.encode(), ec.ECDSA(hashes.SHA256()))).decode()


def as_owner(st, uid):
    fields = list(st)
    fields[4] = uid
    return os.stat_result(fields)


class Clock:
    def __init__(self, t=1_800_000_000_000):
        self.t = t

    def __call__(self):
        return self.t


class Base(unittest.TestCase):
    def setUp(self):
        self.dir = pathlib.Path(tempfile.mkdtemp()).resolve()  # /var is a symlink on macOS; the trust chain refuses symlinks
        # Controller mechanics are tested with every service's evidence complete; MissingEvidence tests the real policy.
        self.saved_missing, S.MISSING_EVIDENCE = S.MISSING_EVIDENCE, {}
        self.addCleanup(lambda: setattr(S, 'MISSING_EVIDENCE', self.saved_missing))
        installed, S.installed = S.installed, lambda service: True  # portable: this host's real plists don't matter here
        self.addCleanup(lambda: setattr(S, 'installed', installed))
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
        as_user = lambda p: as_owner(os.lstat(p), 501)  # portable: real uid may be 0 (proot), so the owner is faked
        self.assertEqual(S.load_trust(self.trust_file, require_root=True, lstat=as_user).state, 'untrusted')
        as_root = lambda p: as_owner(os.lstat(p), 0)
        self.assertEqual(S.load_trust(self.trust_file, require_root=True, lstat=as_root).state, 'ready')
        os.chmod(self.trust_file, 0o666)
        self.assertIn('writable', S.load_trust(self.trust_file, require_root=False).detail)
        os.chmod(self.trust_file, 0o644)
        link = self.trust_file.parent / 'link.json'
        link.symlink_to(self.trust_file)
        self.assertIn('symlink', S.load_trust(link, require_root=False).detail)

    def test_every_ancestor_must_be_a_root_owned_unwritable_real_directory(self):
        grand = self.trust_file.parent.parent
        def writable(p, bits=0o002):
            st = as_owner(os.lstat(p), 0)
            if pathlib.Path(p) == grand:
                fields = list(st); fields[0] |= bits; return os.stat_result(fields)
            return st
        detail = S.load_trust(self.trust_file, require_root=True, lstat=writable).detail
        self.assertIn(f'{grand} is group- or world-writable', detail)
        def linked(p):
            st = as_owner(os.lstat(p), 0)
            if pathlib.Path(p) == grand:
                fields = list(st); fields[0] = stat.S_IFLNK | 0o755; return os.stat_result(fields)
            return st
        self.assertIn(f'{grand} is a symlink', S.load_trust(self.trust_file, require_root=True, lstat=linked).detail)
        def user_owned(p):
            return as_owner(os.lstat(p), 501 if pathlib.Path(p) == grand else 0)
        self.assertIn(f'{grand} is not owned by root', S.load_trust(self.trust_file, require_root=True, lstat=user_owned).detail)
        self.assertEqual(S.load_trust(pathlib.Path('relative/keys.json'), require_root=False).state, 'untrusted')
        self.assertEqual(S.load_trust(self.trust_file.parent / '..' / self.trust_file.parent.name / self.trust_file.name, require_root=False).state, 'untrusted')

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


    def test_a_second_restart_cannot_enter_or_reopen_the_first_ones_gate(self):
        inside, release, outcome = threading.Event(), threading.Event(), {}
        def first():
            with self.control.gate.exclusive(lambda: dict(self.counts)):
                inside.set()
                release.wait(5)
        runner = threading.Thread(target=first)
        runner.start()
        self.assertTrue(inside.wait(5))
        try:  # zero locks held: ownership alone must keep the second out
            with self.control.gate.exclusive(lambda: dict(self.counts)):
                outcome['entered'] = True
        except S.Refused as e:
            outcome['code'] = e.code
        self.assertEqual(outcome, {'code': 'restart_in_progress'})
        self.assertFalse(self.control.gate.admit())  # the loser did not reopen the owner's gate
        release.set()
        runner.join(5)
        self.assertTrue(self.control.gate.admit())

    def test_simultaneous_approvals_run_one_restart(self):
        gate = threading.Event()
        self.backend.kickstart_gui = lambda service: (self.backend.calls.append(('gui', service)), gate.wait(2))
        threads = [threading.Thread(target=self.control.approve, args=(svc, self.approve_body(self.control.request(svc)))) for svc in ('soma', 'syncthing')]
        for t in threads: t.start()
        time.sleep(0.3)
        gate.set()
        for t in threads: t.join(5)
        self.assertEqual(len(self.backend.calls), 1)

    def test_closing_waits_for_an_admission_that_is_enqueueing(self):
        gate = S.WorkGate(self.locks, admission=threading.Lock())
        result = {}
        gate.admission.acquire()  # an admission saw the gate open and is mid-enqueue
        def restart():
            try:
                with gate.exclusive(lambda: dict(self.counts)):
                    result['ran'] = True
            except S.Refused as e:
                result['code'] = e.code
        t = threading.Thread(target=restart)
        t.start()
        time.sleep(0.2)
        self.assertTrue(gate.admit())  # not closed while the admission holds the lock
        self.counts['queued'] = 1  # ... which then counts itself
        gate.admission.release()
        t.join(5)
        self.assertEqual(result, {'code': 'busy_work'})
        self.assertTrue(gate.admit())


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
        saved, S.PROBED = S.PROBED, S.PROBED - {'canvas'}
        try:
            op = self.op(self.control.approve('canvas', self.approve_body(self.control.request('canvas'))))
        finally:
            S.PROBED = saved
        self.assertEqual((op['stage'], self.backend.calls), ('blocked', []))

    def test_the_root_helper_gets_the_owners_own_proof_which_is_never_recorded(self):
        seen = []
        self.backend.privileged = lambda service, proof: seen.append((service, proof))
        self.probe_result = [S.mkcheck('herd_identity', 'herd', 'ok', 'pass')]
        c = self.control.request('herd')
        body = self.approve_body(c)
        saved, S.PRIVILEGED_HELPER = S.PRIVILEGED_HELPER, self.trust_file  # "installed"
        try:
            op = self.op(self.control.approve('herd', body))
        finally:
            S.PRIVILEGED_HELPER = saved
        self.assertEqual(op['stage'], 'restored')
        self.assertEqual(seen, [('herd', {'approval': c['id'], 'nonce': c['nonce'], 'service': 'herd', 'host': 'jacks-mac-pro', 'expires_at': c['expires_at'],
                                          'owner_key_id': self.key_id, 'owner_signature': body['owner_signature']})])
        blob = ''.join(p.read_text() for p in (self.dir / 'state').rglob('*.json'))
        self.assertNotIn(body['owner_signature'][:20], blob)
        self.assertNotIn(c['nonce'], blob)

    def test_an_unsupported_check_beside_passes_is_never_restored(self):
        self.probe_result = [S.mkcheck('soma_identity', 'soma', 'ok', 'pass'), S.mkcheck('extra', 'soma', 'no_capability_probe', 'unsupported')]
        op = self.op(self.control.approve('soma', self.approve_body(self.control.request('soma'))))
        self.assertEqual((op['stage'], op['verified']), ('failed', False))

    def test_operation_is_found_by_its_approval_and_bound_to_it(self):
        c = self.control.request('soma')
        result = self.control.approve('soma', self.approve_body(c))
        found = self.control.records.operation_for_approval(c['id'])
        self.assertEqual((found['id'], found['approval'], found['id']), (result['operation']['id'], c['id'], S.operation_id(c['id'])))
        self.assertIsNone(self.control.records.operation_for_approval('svc-' + '0' * 32))
        self.assertIsNone(self.control.records.operation_for_approval('../../etc/passwd'))

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

    def test_a_crash_before_the_restart_began_ends_interrupted_by_checking_only(self):
        held = []
        self.control.spawn = lambda fn, *a: held.append((fn, a))  # the worker never starts: the process "crashes" here
        result = self.control.approve('soma', self.approve_body(self.control.request('soma')))
        self.assertEqual(self.op(result)['stage'], 'checking')
        records = S.Records(self.dir / 'state')
        for passing, stage_verified in ((True, ('interrupted', True)), (False, ('interrupted', False))):
            if not passing:  # a second crashed operation, on another service, whose check now fails
                self.control.spawn = lambda fn, *a: held.append((fn, a))
                result = self.control.approve('syncthing', self.approve_body(self.control.request('syncthing')))
                self.probe_result = [S.mkcheck('x', 'syncthing', 'down', 'fail')]
            after = S.Controller(self.backend, lambda: dict(self.counts), records=records, trust=self.control.trust, gate=S.WorkGate(self.locks),
                                 probe=lambda service: list(self.probe_result), state=lambda service: {}, host='jacks-mac-pro',
                                 clock=self.clock, sleep=lambda s: None, spawn=lambda fn, *a: fn(*a))
            after.resume()
            op = self.op(result)
            self.assertEqual((op['stage'], op['verified']), stage_verified)
            self.assertIs(op['restarted'], False)
        self.assertEqual(self.backend.calls, [])  # zero executions, before or after
        incident = json.loads((self.dir / 'state' / 'incidents' / 'newest.json').read_text())
        self.assertEqual(incident['root_cause'], 'interrupted_before_restart')
        after.resume()
        self.assertEqual(self.backend.calls, [])  # terminal: nothing left to resume

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
        self.assertEqual(by['tailscaled']['restart'].get('blocked_reason'), 'owner_verified_control_broker_unavailable')
        self.assertTrue(by['canvas']['restart']['supported'])
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
        keys = {'soma': lambda: 'runner-device-key-123456'}
        try:
            for why in ('oversized reply', 'incomplete or non-JSON reply'):
                def fetch(url, why=why, **k): raise S.Unverified(why)
                (check,) = S._probe('soma', fetch=fetch, keys=keys)
                self.assertEqual((check['code'], check['result']), ('reachable_unverified', 'unverified'))
            def down(url, **k): raise OSError('connection refused')
            self.assertEqual(S._probe('soma', fetch=down, keys=keys)[0]['result'], 'fail')
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
        OWN_PORTS.add(server.server_address[1])
        try:
            for path in ('/short', '/big', '/list'):
                with self.assertRaises(S.Unverified, msg=path):
                    S.http_json(base + path, timeout=2)
            self.assertEqual(S.http_json(base + '/ok'), (200, {'service': 'soma-serve'}))
        finally:
            server.shutdown(); server.server_close()

    def test_unprobed_service_is_structured_unsupported(self):
        (check,) = S.capability_probe('not-a-service')
        self.assertEqual((check['check'], check['target'], check['code'], check['result']), ('not-a-service_capability', 'not-a-service', 'no_capability_probe', 'unsupported'))
        self.assertEqual(set(S.SERVICES), S.PROBED)  # every allowlisted service has a real probe

    def setUp(self):
        self.saved_paths, S.TAILSCALE_PATHS = S.TAILSCALE_PATHS, (sys.executable,)  # any existing file: run is faked
        self.status = {'BackendState': 'Running', 'Self': {'DNSName': 'jacks-mac-pro.tail.ts.net.', 'Online': True, 'TailscaleIPs': ['100.64.0.9', 'fd7a::9'],
                       'UserID': 7}, 'User': {'7': {'LoginName': 'owner@example.com'}}, 'Peer': {'a': {'Online': True}, 'b': {'Online': False}}}
        self.run = lambda argv, **k: Fake(returncode=0, stdout=json.dumps(self.status), stderr='')
        self.saved_fields, S.plist_fields = S.plist_fields, lambda service, load=None: {}  # the defaults, whatever is installed here

    def tearDown(self):
        S.TAILSCALE_PATHS = self.saved_paths
        S.plist_fields = self.saved_fields

    def test_soma_needs_the_runners_key_sends_it_and_never_records_it(self):
        os.environ['OMA_RUNNER_SOMA_SERVICE'] = 'http://127.0.0.1:1'
        try:
            self.assertEqual(S._probe('soma')[0]['code'], 'no_runner_key')
            sent = []
            def fetch(url, headers=None, **k):
                sent.append(headers)
                return 200, {'ok': True, 'service': 'soma-serve', 'machine': 'jacks-mac-pro', 'public': {'secret_token': 'zzz-public'}}
            (check,) = S._probe('soma', fetch=fetch, keys={'soma': lambda: 'runner-device-key-123456'})
            self.assertEqual(check['result'], 'pass')
            self.assertEqual(sent, [{'Authorization': 'Bearer runner-device-key-123456'}])
            self.assertNotIn('runner-device-key', json.dumps(check))
            self.assertNotIn('zzz-public', json.dumps(check))
            def refused(url, **k): raise urllib.error.HTTPError(url, 401, 'no', {}, None)
            (check,) = S._probe('soma', fetch=refused, keys={'soma': lambda: 'k' * 20})
            self.assertEqual((check['code'], check['result']), ('auth_refused', 'unverified'))
        finally:
            del os.environ['OMA_RUNNER_SOMA_SERVICE']

    def test_herd_and_tailshare_keep_only_their_identity_field(self):
        def fetch(url, **k):
            self.assertTrue(url.startswith('http://100.64.0.9:'), url)
            return 200, {'machine': 'jacks-mac-pro', 'name': 'jacks-mac-pro', 'sessions': [{'title': 'PRIVATE prompt text', 'cwd': '/Users/x/secret'}]}
        for service in ('herd', 'tailshare-serve'):
            (check,) = S._probe(service, fetch=fetch, run=self.run)
            self.assertEqual(check['result'], 'pass', service)
            self.assertNotIn('PRIVATE', json.dumps(check))
            self.assertNotIn('/Users/x', json.dumps(check))

    def test_tailscaled_keeps_backend_state_never_the_login(self):
        (check,) = S._probe('tailscaled', run=self.run)
        self.assertEqual((check['result'], check['fields']), ('pass', {'backend_state': 'Running', 'peers_online': 1}))
        self.assertNotIn('owner@example.com', json.dumps(check))
        self.status['BackendState'] = 'Stopped'
        self.assertEqual(S._probe('tailscaled', run=self.run)[0]['result'], 'fail')

    def test_imessage_needs_send_ready_and_never_records_handles_or_token(self):
        token = pathlib.Path(tempfile.mkdtemp()) / 'token'
        token.write_text('imessage-bearer-abcdef\n')
        saved, S.IMESSAGE_TOKEN = S.IMESSAGE_TOKEN, token
        try:
            body = {'ok': True, 'service': 'soma-imessage', 'sendReady': True, 'messagesAccess': True, 'watching': True,
                    'account': {'handles': ['+15550001111', 'me@example.com']}}
            sent = []
            def fetch(url, headers=None, **k):
                sent.append(headers)
                return 200, dict(body)
            (check,) = S._probe('imessage-api', fetch=fetch, run=self.run)
            self.assertEqual(check['result'], 'pass')
            self.assertEqual(sent[0], {'Authorization': 'Bearer imessage-bearer-abcdef'})
            for leak in ('+1555', 'me@example.com', 'imessage-bearer'):
                self.assertNotIn(leak, json.dumps(check))
            body['sendReady'] = False
            self.assertEqual(S._probe('imessage-api', fetch=fetch, run=self.run)[0]['result'], 'fail')
            token.unlink()
            self.assertEqual(S._probe('imessage-api', fetch=fetch, run=self.run)[0]['code'], 'no_token')
        finally:
            S.IMESSAGE_TOKEN = saved

    def test_open_health_routes_and_process_only_services(self):
        replies = {'/healthz': {'ok': True, 'uptime': 5}, '/health': {'ok': True, 'model': 'm', 'dim': 768, 'status': 'ok'}}
        fetch = lambda url, **k: (200, replies[urllib.parse.urlsplit(url).path])
        for service in ('canvas', 'embeddings', 'whisper'):
            self.assertEqual([c['result'] for c in S._probe(service, fetch=fetch, run=self.run)], ['pass'], service)
        replies['/health'] = {'ok': True, 'model': 'm', 'dim': 0, 'status': 'loading'}
        for service in ('embeddings', 'whisper'):
            self.assertEqual(S._probe(service, fetch=fetch, run=self.run)[0]['result'], 'fail', service)
        up = lambda service: {'loaded': True, 'state': 'running', 'pid': 9, 'last_exit': 0}
        down = lambda service: {'loaded': True, 'state': 'waiting', 'pid': None, 'last_exit': 1}
        self.assertEqual(S._probe('tailshare-receive', state=up)[0]['result'], 'pass')
        self.assertEqual(S._probe('tailshare-receive', state=down)[0]['result'], 'fail')
        saved, S.TRANSPORT_SOCKET = S.TRANSPORT_SOCKET, pathlib.Path(tempfile.mkdtemp()) / 'missing.sock'
        try:
            self.assertEqual([c['result'] for c in S._probe('imessage-transport', state=up)], ['pass', 'fail'])
        finally:
            S.TRANSPORT_SOCKET = saved

    def test_syncthing_folders_use_the_local_key_only_as_a_header(self):
        config = pathlib.Path(tempfile.mkdtemp()) / 'config.xml'
        config.write_text('<configuration><folder id="docs" paused="false"/><folder id="old" paused="true"/>'
                          '<gui><apikey>syncthing-api-key-xyz</apikey></gui></configuration>')
        states = {'docs': {'state': 'idle', 'errors': 0, 'pullErrors': 0, 'needFiles': 3}}
        asked = []
        def fetch(url, headers=None, **k):
            asked.append((url, headers))
            return 200, states[urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)['folder'][0]]
        check = S.syncthing_folders(fetch, config=config)
        self.assertEqual((check['result'], check['fields']['states']), ('pass', {'docs': 'idle'}))
        self.assertEqual(asked, [('http://127.0.0.1:8384/rest/db/status?folder=docs', {'X-API-Key': 'syncthing-api-key-xyz'})])
        self.assertNotIn('syncthing-api-key', json.dumps(check))
        states['docs'] = {'state': 'error', 'errors': 2}
        self.assertEqual(S.syncthing_folders(fetch, config=config)['result'], 'fail')
        self.assertEqual(S.syncthing_folders(fetch, config=config.parent / 'none.xml')['result'], 'unverified')


class MissingEvidence(Base):
    def setUp(self):
        super().setUp()
        S.MISSING_EVIDENCE = self.saved_missing

    def test_identity_or_process_only_services_carry_required_unverified_checks(self):
        for service in ('soma', 'canvas', 'tailshare-serve', 'tailshare-receive', 'herd', 'embeddings', 'imessage-transport', 'runner'):
            extra = S.missing_checks(service)
            self.assertTrue(extra, service)
            self.assertTrue(all(c['result'] == 'unverified' and c['fields']['required'] is True and c['fields']['missing'] for c in extra), service)
        self.assertEqual({c['check'] for c in S.missing_checks('runner')}, {'provider_auth', 'build_toolchain', 'source_freshness'})
        for service in ('syncthing', 'tailscaled', 'whisper', 'imessage-api'):
            self.assertEqual(S.missing_checks(service), [], service)

    def test_the_probe_appends_them_so_a_passing_identity_is_not_ready(self):
        checks = S.capability_probe('canvas', fetch=lambda url, **k: (200, {'ok': True}))
        self.assertEqual([c['result'] for c in checks], ['pass', 'unverified'])
        self.assertEqual(checks[-1]['code'], 'evidence_missing')

    def test_a_service_not_installed_here_is_refused_and_listed_separately(self):
        S.installed = lambda service: service != 'herd'
        op = self.op(self.control.approve('herd', self.approve_body(self.control.request('herd'))))
        self.assertEqual((op['stage'], self.backend.calls), ('blocked', []))
        by = {x['id']: x for x in self.control.inventory(budget=2)['services']}
        self.assertEqual((by['herd']['installed'], by['herd']['restart']['blocked_reason']), (False, 'not_installed'))
        self.assertEqual((by['syncthing']['optional'], by['syncthing']['loaded'], by['syncthing']['running']), (True, True, True))

    def test_a_restart_that_could_only_be_half_verified_is_refused_before_it_starts(self):
        op = self.op(self.control.approve('soma', self.approve_body(self.control.request('soma'))))
        self.assertEqual((op['stage'], op['verified'], self.backend.calls), ('blocked', False, []))
        self.assertIn('soma_sync', [c['check'] for c in op['checks']])
        incident = json.loads((self.dir / 'state' / 'incidents' / 'newest.json').read_text())
        self.assertEqual(incident['root_cause'], 'incomplete_capability_proof')
        by = {x['id']: x for x in self.control.inventory(budget=2)['services']}
        self.assertEqual((by['soma']['restart']['supported'], by['soma']['restart']['blocked_reason']), (False, 'incomplete_capability_proof'))
        self.assertTrue(by['syncthing']['restart']['supported'])


class Presence(unittest.TestCase):
    def setUp(self):
        self.saved = S.installed, S.plist_path, S.TAILSCALE_PATHS
        self.dir = pathlib.Path(tempfile.mkdtemp())
        S.plist_path = lambda service: self.dir / (S.SERVICES[service][1] + '.plist')
        S.TAILSCALE_PATHS = (sys.executable,)

    def tearDown(self):
        S.installed, S.plist_path, S.TAILSCALE_PATHS = self.saved

    def test_a_service_this_host_lacks_is_neutral_and_never_probed(self):
        def fetch(url, **k): raise AssertionError('probed a service that is not installed')
        (check,) = S.capability_probe('herd', fetch=fetch)
        self.assertEqual((check['code'], check['result'], check['fields']), ('not_installed', 'not_installed', {'optional': False}))
        (check,) = S.capability_probe('syncthing', fetch=fetch)
        self.assertEqual((check['code'], check['result'], check['fields']), ('disabled', 'not_installed', {'optional': True}))
        self.assertTrue(S.installed('runner'))

    def test_the_same_label_is_a_root_daemon_on_one_mac_and_a_user_agent_on_another(self):
        S.plist_path = self.saved[1]
        saved = S.SYSTEM_PLISTS, S.USER_PLISTS
        S.SYSTEM_PLISTS, S.USER_PLISTS = self.dir / 'daemons', self.dir / 'agents'
        try:
            S.SYSTEM_PLISTS.mkdir(); S.USER_PLISTS.mkdir()
            (S.SYSTEM_PLISTS / 'dev.herd.node.plist').write_text('x')        # Mac Pro
            (S.USER_PLISTS / 'com.tailshare.serve.plist').write_text('x')    # MacBook
            self.assertEqual(S.placement('herd'), ('system', 'dev.herd.node', 'privileged'))
            self.assertEqual(S.target('herd'), 'system/dev.herd.node')
            self.assertEqual(S.placement('tailshare-serve'), ('gui', 'com.tailshare.serve', 'gui'))  # restarted as the user, no root
            self.assertEqual(S.target('tailshare-serve'), 'gui/%d/com.tailshare.serve' % os.getuid())
            (S.USER_PLISTS / 'dev.omalauncher.runner.plist').write_text('x')
            self.assertEqual(S.placement('runner')[2], 'self')
            self.assertFalse(S.installed('whisper'))
            self.assertEqual(S.placement('whisper'), S.SERVICES['whisper'])  # absent: the table's default, and not installed
        finally:
            S.SYSTEM_PLISTS, S.USER_PLISTS = saved

    def test_the_port_comes_from_the_installed_plist_and_nothing_else_is_kept(self):
        plistlib = __import__('plistlib')
        (self.dir / 'com.jackkim.soma-canvas.plist').write_bytes(plistlib.dumps({'Label': 'com.jackkim.soma-canvas',
            'ProgramArguments': ['/bin/zsh', '-lc', 'npx tsx src/server/server.ts'],
            'EnvironmentVariables': {'CANVAS_PORT': '5861', 'CANVAS_OWNER_KEY': 'owner-secret-zzz', 'HOME': '/Users/x'}}))
        self.assertEqual(S.plist_fields('canvas'), {'port': '5861'})
        self.assertEqual(S.endpoint('canvas'), ('127.0.0.1', 5861))
        asked = []
        checks = S.capability_probe('canvas', fetch=lambda url, **k: (asked.append(url), (200, {'ok': True}))[1])
        self.assertEqual(asked, ['http://127.0.0.1:5861/healthz'])
        self.assertNotIn('owner-secret', json.dumps(checks))
        (self.dir / 'dev.omalauncher.whisper-server.plist').write_bytes(plistlib.dumps({'ProgramArguments': [
            '/opt/homebrew/bin/whisper-server', '--model', 'm.bin', '--host', '100.64.0.9', '--port', '8188']}))
        self.assertEqual(S.endpoint('whisper', run=lambda *a, **k: None), ('100.64.0.9', 8188))
        (self.dir / 'dev.omalauncher.embed-server.plist').write_bytes(plistlib.dumps({'ProgramArguments': ['x'],
            'EnvironmentVariables': {'EMBED_HOST': '8.8.8.8', 'EMBED_PORT': '99999'}}))  # not this Mac; not a port
        run = lambda argv, **k: Fake(returncode=0, stdout=json.dumps({'Self': {'TailscaleIPs': ['100.64.0.1']}}), stderr='')
        self.assertEqual(S.endpoint('embeddings', run=run), ('100.64.0.1', 8179))


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


class Inventory(unittest.TestCase):
    def test_a_slow_probe_never_holds_the_reply_and_its_row_is_honest(self):
        release, calls = threading.Event(), []
        def probe(service):
            calls.append(service)
            if service == 'herd':
                release.wait(5)
            return [S.mkcheck(service + '_x', service, 'ok', 'pass')]
        clock = iter(range(1000, 100000, 10))
        cache = S.ProbeCache(probe, lambda s: {'loaded': True, 'state': 'running', 'pid': 1}, lambda: next(clock))
        started = time.monotonic()
        rows = cache.snapshot(list(S.SERVICES), budget=0.3)
        self.assertLess(time.monotonic() - started, 1.5)
        self.assertEqual((rows['herd']['pending'], rows['herd']['checked_at'], rows['herd']['checks'][0]['result']), (True, None, 'unverified'))
        self.assertEqual((rows['soma']['pending'], rows['soma']['checks'][0]['result']), (False, 'pass'))
        self.assertIsNotNone(rows['soma']['checked_at'])
        cache.snapshot(['herd'], budget=0.05)  # still in flight: not probed twice
        self.assertEqual(calls.count('herd'), 1)
        release.set()
        rows = cache.snapshot(['herd'], budget=2)
        self.assertEqual(rows['herd']['checks'][0]['result'], 'pass')  # finished late, recorded as it was, not cut to failed
        self.assertIsNotNone(rows['herd']['checked_at'])

    def test_a_cached_row_keeps_its_own_time_while_a_new_probe_is_slow(self):
        slow, n = threading.Event(), {'n': 0}
        def probe(service):
            n['n'] += 1
            if n['n'] > 1:
                slow.wait(5)
            return [S.mkcheck('x', service, 'ok', 'pass')]
        times = iter([111, 999])
        cache = S.ProbeCache(probe, lambda s: {}, lambda: next(times))
        self.assertEqual(cache.snapshot(['canvas'], budget=2)['canvas']['checked_at'], 111)
        row = cache.snapshot(['canvas'], budget=0.1)['canvas']
        self.assertEqual((row['checked_at'], row['pending']), (111, True))
        slow.set()


class OwnerKeysForTheInstaller(unittest.TestCase):
    def entry(self, **over):
        private, key_id, public = owner_key()
        entry = {'key_id': key_id, 'algorithm': 'SHA256withECDSA-P256', 'public_key': public, 'label': 'Fold', 'device': 'galaxy-z-fold8',
                 'added_at': 5, 'revoked': False, 'private_key': 'never-kept'}
        entry.update(over)
        return entry

    def test_the_phones_algorithm_name_is_mapped_and_unknown_fields_dropped(self):
        out = json.loads(S.normalize_trust(json.dumps({'version': 1, 'keys': [self.entry()]})))
        self.assertEqual(out['keys'][0]['algorithm'], 'ecdsa-p256-sha256')
        self.assertNotIn('private_key', out['keys'][0])
        folder = pathlib.Path(tempfile.mkdtemp()).resolve()
        (folder / 'keys.json').write_text(json.dumps(out))
        os.chmod(folder / 'keys.json', 0o644)
        self.assertEqual(S.load_trust(folder / 'keys.json', require_root=False).state, 'ready')

    def test_bad_files_are_refused_not_copied(self):
        p384 = ec.generate_private_key(ec.SECP384R1()).public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        bad = [{'version': 2, 'keys': [self.entry()]}, {'version': 1, 'keys': []}, {'version': 1, 'keys': [self.entry(algorithm='rsa')]},
               {'version': 1, 'keys': [self.entry(key_id='0' * 64)]},
               {'version': 1, 'keys': [self.entry(public_key=base64.b64encode(p384).decode(), key_id=hashlib.sha256(p384).hexdigest())]}]
        twin = self.entry()
        bad.append({'version': 1, 'keys': [twin, dict(twin)]})
        for data in bad:
            with self.assertRaises(Exception, msg=str(data)[:80]):
                S.normalize_trust(json.dumps(data))

    def test_the_cli_prints_only_the_canonical_file(self):
        import subprocess
        folder = pathlib.Path(tempfile.mkdtemp())
        (folder / 'in.json').write_text(json.dumps({'version': 1, 'keys': [self.entry()]}))
        r = subprocess.run([sys.executable, '-I', str(HERE / 'soma_services.py'), 'normalize-trust', str(folder / 'in.json')], capture_output=True, text=True, timeout=20)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(json.loads(r.stdout)['keys'][0]['algorithm'], 'ecdsa-p256-sha256')
        (folder / 'bad.json').write_text('{"version": 1, "keys": [{"algorithm": "rsa"}]}')
        r = subprocess.run([sys.executable, '-I', str(HERE / 'soma_services.py'), 'normalize-trust', str(folder / 'bad.json')], capture_output=True, text=True, timeout=20)
        self.assertEqual((r.returncode, r.stdout), (1, ''))


class PrivilegedHelper(unittest.TestCase):
    """The Swift root helper, built here and run unprivileged in its test mode (SOMA_HELPER_TEST_DIR, ignored as root), where it
    prints the one command it would run. install/remove are never run by a test."""
    source, installer = HERE / 'soma-service-helper.swift', HERE / 'install-service-helper.sh'
    binary = None

    @classmethod
    def setUpClass(cls):
        import shutil, subprocess
        if os.geteuid() == 0 or not shutil.which('swiftc'):
            return
        folder = pathlib.Path(tempfile.mkdtemp())
        for name, flags in (('test', ['-D', 'SOMA_HELPER_TESTING']), ('installed', [])):  # the installer builds the second
            r = subprocess.run(['swiftc', '-O', *flags, str(cls.source), '-o', str(folder / name)], capture_output=True, text=True, timeout=600)
            assert r.returncode == 0, r.stderr[-2000:]
        cls.binary, cls.installed = folder / 'test', folder / 'installed'

    def setUp(self):
        if not self.binary:
            self.skipTest('needs swiftc and a non-root user')
        self.dir = pathlib.Path(tempfile.mkdtemp()).resolve()
        self.private, self.key_id, public = owner_key()
        (self.dir / 'connections-owner-keys.json').write_text(json.dumps({'version': 1, 'keys': [
            {'key_id': self.key_id, 'algorithm': 'ecdsa-p256-sha256', 'public_key': public, 'label': 'Fold', 'device': 'f', 'added_at': 1, 'revoked': False}]}))
        (self.dir / 'helper.json').write_text(json.dumps({'version': 1, 'host': 'jacks-mac-pro'}))
        for f in ('connections-owner-keys.json', 'helper.json'):
            os.chmod(self.dir / f, 0o644)

    def proof(self, service='herd', host='jacks-mac-pro', expires=None, plan=None, sign_service=None, **over):
        import secrets as _s
        approval, nonce = 'svc-' + _s.token_hex(16), _s.token_urlsafe(24)
        expires = expires if expires is not None else int(time.time() * 1000) + 120_000
        payload = S.canonical_payload(approval, nonce, sign_service or service, host, expires)
        body = {'approval': approval, 'nonce': nonce, 'service': service, 'host': host, 'expires_at': expires, 'owner_key_id': self.key_id}
        if plan is not None:
            items = plan + [base64.b64encode(payload.encode()).decode()]
            body['owner_payloads'] = items
            body['owner_signature'] = sign(self.private, S.plan_message(items))
        else:
            body['owner_signature'] = sign(self.private, payload)
        body.update(over)
        return body

    def run_helper(self, body, args=(), test_dir=True, raw=None, fail=None):
        import subprocess
        env = {'PATH': '/usr/bin:/bin', **({'SOMA_HELPER_TEST_DIR': str(self.dir)} if test_dir else {}), **({'SOMA_HELPER_FAIL': fail} if fail else {})}
        return subprocess.run([str(self.binary), *args], input=raw if raw is not None else json.dumps(body), capture_output=True, text=True, timeout=20, env=env)

    def test_a_signed_approval_runs_the_one_fixed_command_once(self):
        body = self.proof('herd')
        r = self.run_helper(body)
        self.assertEqual((r.returncode, r.stdout.strip()), (0, 'would run: /bin/launchctl kickstart -k system/dev.herd.node'), r.stderr)
        r = self.run_helper(body)
        self.assertEqual((r.returncode, r.stderr.strip()), (65, 'refused: approval_already_used'))
        self.assertEqual(stat.S_IMODE(os.stat(self.dir / 'used-approvals').st_mode), 0o700)

    def test_unsigned_or_self_asserted_calls_are_refused(self):
        unsigned = self.proof('tailscaled')
        del unsigned['owner_signature']
        for body in (unsigned, {'service': 'tailscaled', 'owner': True}, {'service': 'tailscaled'}, {**self.proof('tailscaled'), 'owner_signature': sign(owner_key()[0], 'x')}):
            r = self.run_helper(body)
            self.assertEqual(r.returncode, 65, body.keys())
            self.assertEqual(r.stdout, '')
        self.assertEqual(self.run_helper(None, raw='').returncode, 65)

    def test_the_signature_binds_service_host_expiry_and_key(self):
        cases = {
            'bad_signature': self.proof('tailscaled', sign_service='herd'),
            'wrong_host': self.proof('herd', host='other-mac'),
            'expired': self.proof('herd', expires=int(time.time() * 1000) - 1),
            'expired ': self.proof('herd', expires=int(time.time() * 1000) + 3_600_000),  # far-future expiry is refused too
            'owner_key': self.proof('herd', owner_key_id='0' * 64),
            'bad_request': self.proof('canvas'),  # a gui service is not the root helper's
        }
        for want, body in cases.items():
            r = self.run_helper(body)
            self.assertEqual((r.returncode, r.stderr.strip()), (65 if want != 'bad_request' else 65, 'refused: ' + want.strip()), want)

    def test_a_signed_plan_covers_its_entries_only(self):
        other = base64.b64encode(S.canonical_payload('svc-' + 'a' * 32, 'n' * 32, 'whisper', 'jacks-mac-pro', 1).encode()).decode()
        r = self.run_helper(self.proof('embeddings', plan=[other]))
        self.assertEqual(r.stdout.strip(), 'would run: /bin/launchctl kickstart -k system/dev.omalauncher.embed-server', r.stderr)
        body = self.proof('embeddings', plan=[other])
        body['owner_payloads'] = [other]  # this restart is not in the signed plan
        self.assertEqual(self.run_helper(body).stderr.strip(), 'refused: bad_plan')

    def test_no_arguments_no_test_mode_unsafe_trust(self):
        self.assertEqual(self.run_helper(self.proof('herd'), args=['herd']).returncode, 64)
        self.assertEqual(self.run_helper(self.proof('herd'), test_dir=False).returncode, 77)  # not root, no test dir: nothing
        os.chmod(self.dir / 'connections-owner-keys.json', 0o666)
        self.assertEqual(self.run_helper(self.proof('herd')).stderr.strip(), 'refused: unsafe:connections-owner-keys.json')
        os.chmod(self.dir / 'connections-owner-keys.json', 0o644)
        (self.dir / 'connections-owner-keys.json').rename(self.dir / 'real.json')
        (self.dir / 'connections-owner-keys.json').symlink_to(self.dir / 'real.json')
        self.assertEqual(self.run_helper(self.proof('herd')).stderr.strip(), 'refused: unsafe:connections-owner-keys.json')

    def test_a_use_record_not_durably_written_refuses_and_is_never_retried(self):
        for failure in ('write', 'fsync', 'dirsync'):
            body = self.proof('herd')
            r = self.run_helper(body, fail=failure)
            self.assertEqual((r.returncode, r.stdout, r.stderr.strip()), (65, '', 'refused: cannot_record_use'), failure)
            r = self.run_helper(body)  # the same approval, persistence now fine: still used, never retried
            self.assertEqual(r.stderr.strip(), 'refused: approval_already_used', failure)
        self.assertEqual(self.run_helper(self.proof('herd')).returncode, 0)

    def test_a_caller_that_never_finishes_its_input_cannot_hold_the_helper(self):
        import subprocess, signal
        env = {'PATH': '/usr/bin:/bin', 'SOMA_HELPER_TEST_DIR': str(self.dir)}
        started = time.monotonic()
        p = subprocess.Popen([str(self.binary)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
        p.stdin.write(b'{"approval": ')
        p.stdin.flush()
        try:
            code = p.wait(timeout=15)
        finally:
            p.kill()
            p.stdin.close(); p.stdout.close(); p.stderr.close()
        self.assertEqual(code, -signal.SIGALRM)
        self.assertLess(time.monotonic() - started, 10)

    def test_the_installed_build_has_no_test_mode(self):
        import subprocess
        env = {'PATH': '/usr/bin:/bin', 'SOMA_HELPER_TEST_DIR': str(self.dir)}
        r = subprocess.run([str(self.installed)], input=json.dumps(self.proof('herd')), capture_output=True, text=True, timeout=20, env=env)
        self.assertEqual((r.returncode, r.stdout, r.stderr.strip()), (77, '', 'refused: not_root'))
        self.assertNotIn(b'SOMA_HELPER_TEST_DIR', self.installed.read_bytes())
        self.assertNotIn(b'SOMA_HELPER_FAIL', self.installed.read_bytes())
        self.assertNotIn(b'would run', self.installed.read_bytes())
        self.assertIn(b'SOMA_HELPER_TEST_DIR', self.binary.read_bytes())  # the check above would catch it
        self.assertNotIn('SOMA_HELPER_TESTING', self.installer.read_text())

    def test_secrets_never_echo(self):
        body = self.proof('herd', host='other-mac')
        r = self.run_helper(body)
        for secret in (body['nonce'], body['owner_signature'][:16]):
            self.assertNotIn(secret, r.stdout + r.stderr)

    def test_the_helper_table_is_exactly_the_privileged_allowlist(self):
        import re
        table = dict(re.findall(r'^    "([a-z-]+)": "([A-Za-z0-9.-]+)",$', self.source.read_text(), re.M))
        self.assertEqual(table, {k: v[1] for k, v in S.SERVICES.items() if v[2] == 'privileged'})

class LocalRootControlPolicy(unittest.TestCase):
    def test_unreviewed_control_broker_cannot_be_installed_or_run(self):
        import subprocess
        for mode in ('install', 'remove', 'print-sudoers'):
            r = subprocess.run(['/bin/sh', str(HERE / 'install-service-helper.sh'), mode, 'jackkim'], capture_output=True, text=True, timeout=10)
            self.assertEqual(r.returncode, 78)
            self.assertNotIn('NOPASSWD', r.stdout)
        with self.assertRaises(S.Blocked):
            S.LiveBackend().privileged('tailscaled', {})



class LiveBackendIsNeverUsedHere(unittest.TestCase):
    def test_mock_backend_is_not_live(self):
        self.assertFalse(S.MockBackend.live)
        self.assertTrue(S.LiveBackend.live)


if __name__ == '__main__':
    unittest.main()
