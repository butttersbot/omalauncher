#!/usr/bin/env python3
"""Owner-approved service restart (soma_services): every execution is mocked; launchctl, sudo and the process are
never touched. Owner keys are generated in memory per test; the trust file lives in a temporary directory."""
import datetime
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
_real_open = _request.OpenerDirector.open


def _guarded_open(self, request, *a, **k):  # soma_services' own no-redirect opener goes through here
    _guarded_urlopen.__wrapped_check__(request)
    return _real_open(self, request, *a, **k)


def _check_url(request):
    url = urllib.parse.urlsplit(request.full_url if hasattr(request, 'full_url') else request)
    if url.hostname != '127.0.0.1' or url.port not in OWN_PORTS:
        raise AssertionError('a test tried to reach a real service: ' + url.geturl())


_guarded_urlopen.__wrapped_check__ = _check_url
_request.OpenerDirector.open = _guarded_open

spec = importlib.util.spec_from_file_location('soma_services', HERE / 'soma_services.py')
S = importlib.util.module_from_spec(spec)
spec.loader.exec_module(S)


# No test may read a real credential: secrets are read only from temporary test folders.
_real_read_secret = S._read_secret


def _guarded_read_secret(path):
    if str(pathlib.Path(path).resolve()).startswith(str(pathlib.Path.home())):
        raise AssertionError('a test tried to read a real credential: ' + str(path))
    return _real_read_secret(path)


S._read_secret = _guarded_read_secret


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


DOWN = [S.mkcheck('x_probe', 'x', 'unreachable', 'fail')]  # what a service reads until the test's fake restarts it


class Base(unittest.TestCase):
    up_before = False

    def scripted_probe(self, service):
        restarted = self.up_before or any(service in call for call in self.backend.calls)
        return list(self.probe_result if restarted else DOWN)

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
                                    probe=self.scripted_probe, state=lambda service: {'loaded': True, 'state': 'running', 'pid': 1},
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
        self.backend.privileged = lambda service, proof: (self.backend.calls.append(('privileged', service)), seen.append((service, proof)))
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
        saved_helper, S.helper_installed = S.helper_installed, lambda: True
        self.addCleanup(lambda: setattr(S, 'helper_installed', saved_helper))
        self.assertTrue({s['id']: s for s in self.control.inventory()['services']}['tailscaled']['restart']['supported'])
        S.helper_installed = saved_helper
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

    def test_a_redirect_is_never_followed_and_the_bearer_never_reaches_the_second_listener(self):
        import http.server, threading
        seen = []
        class Second(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                seen.append(dict(self.headers))
                self.send_response(200); self.send_header('Content-Length', '2'); self.end_headers(); self.wfile.write(b'{}')
            def log_message(self, *a): pass
        second = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Second)
        target = 'http://127.0.0.1:%d/health' % second.server_address[1]
        class First(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(302); self.send_header('Location', target); self.send_header('Content-Length', '0'); self.end_headers()
            def log_message(self, *a): pass
        first = http.server.ThreadingHTTPServer(('127.0.0.1', 0), First)
        for server in (first, second):
            OWN_PORTS.add(server.server_address[1])
            threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            with self.assertRaises(S.Unverified) as caught:
                S.http_json('http://127.0.0.1:%d/health' % first.server_address[1], headers={'Authorization': 'Bearer device-key-SECRET'})
            self.assertIn('redirected (302)', str(caught.exception))
            self.assertEqual(seen, [])  # the second listener never got a request, let alone the bearer
            os.environ['OMA_RUNNER_SOMA_SERVICE'] = 'http://127.0.0.1:%d' % first.server_address[1]
            (check,) = S._probe('soma', run=self.run, keys={'soma': lambda: 'device-key-SECRET'})
            self.assertEqual((check['code'], check['result']), ('reachable_unverified', 'unverified'))
            self.assertEqual(seen, [])
        finally:
            os.environ.pop('OMA_RUNNER_SOMA_SERVICE', None)
            for server in (first, second):
                server.shutdown(); server.server_close()

    def test_soma_is_asked_only_at_this_macs_own_address(self):
        def fetch(url, **k): raise AssertionError('asked an untrusted Soma: ' + url)
        for base in ('http://user:pw@127.0.0.1:8481', 'http://evil.example:8481', 'http://100.64.0.77:8481', 'file:///etc/passwd'):
            os.environ['OMA_RUNNER_SOMA_SERVICE'] = base
            try:
                (check,) = S._probe('soma', fetch=fetch, run=self.run, keys={'soma': lambda: 'k' * 20})
            finally:
                del os.environ['OMA_RUNNER_SOMA_SERVICE']
            self.assertEqual(check['code'], 'soma_url_untrusted', base)
        os.environ['OMA_RUNNER_SOMA_SERVICE'] = 'http://100.64.0.9:8481'  # this Mac's own tailnet address (self.run)
        try:
            (check,) = S._probe('soma', fetch=lambda url, **k: (200, {'ok': True, 'service': 'soma-serve'}), run=self.run, keys={'soma': lambda: 'k' * 20})
        finally:
            del os.environ['OMA_RUNNER_SOMA_SERVICE']
        self.assertEqual(check['result'], 'pass')

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
        self.saved_plist, S.plist_path = S.plist_path, lambda service: pathlib.Path(tempfile.gettempdir()) / 'no-such-dir' / 'none.plist'

    def tearDown(self):
        S.TAILSCALE_PATHS = self.saved_paths
        S.plist_fields = self.saved_fields
        S.plist_path = self.saved_plist

    def test_soma_needs_the_runners_key_sends_it_and_never_records_it(self):
        os.environ['OMA_RUNNER_SOMA_SERVICE'] = 'http://127.0.0.1:1'
        try:
                # With no key, Soma's refusal is its answer: up, and not a problem; nothing there at all is down.
            def refuses(url, headers=None, **k):
                self.assertIsNone(headers)
                raise urllib.error.HTTPError(url, 401, 'no', {}, None)
            got = S._probe('soma', fetch=refuses)[0]
            self.assertEqual((got['check'], got['code'], got['result']), ('soma_identity', 'answers_no_runner_key', 'quiet'))
            def nothing(url, **k):
                raise urllib.error.URLError('refused')
            self.assertEqual(S._probe('soma', fetch=nothing)[0]['result'], 'fail')
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
            return 200, {'machine': {'host': 'jacks-mac-pro', 'herd': '2.1'}, 'name': 'jacks-mac-pro', 'sessions': [{'title': 'PRIVATE prompt text', 'cwd': '/Users/x/secret'}]}
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
        replies = {'/healthz': {'ok': True, 'uptime': 5}, '/health': {'ok': True, 'model': 'm', 'dim': 768, 'status': 'ok'}, '/text': {'vectors': [[0.1] * 768]}}
        fetch = lambda url, **k: (200, replies[urllib.parse.urlsplit(url).path])
        self.assertEqual([(c['check'], c['result']) for c in S._probe('embeddings', fetch=fetch, run=self.run)], [('embed_model', 'pass'), ('embed_inference', 'pass')])
        self.assertEqual([c['result'] for c in S._probe('whisper', fetch=fetch, run=self.run)], ['pass'])
        # Canvas: its health passes; with no agent token configured its board load stays unverified, never Ready.
        self.assertEqual([(c['check'], c['result']) for c in S._probe('canvas', fetch=fetch, run=self.run)],
                         [('canvas_health', 'pass'), ('canvas_boards', 'unverified')])
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
        for service in ('soma', 'tailshare-serve', 'tailshare-receive'):
            extra = S.missing_checks(service)
            self.assertTrue(extra, service)
            self.assertTrue(all(c['result'] == 'unverified' and c['fields']['required'] is True and c['fields']['missing'] for c in extra), service)
        for service in ('runner', 'canvas', 'herd'):  # real probes now carry this evidence themselves
            self.assertEqual(S.missing_checks(service), [], service)
        for service in ('syncthing', 'tailscaled', 'whisper', 'imessage-api'):
            self.assertEqual(S.missing_checks(service), [], service)

    def test_the_probe_appends_them_so_a_passing_identity_is_not_ready(self):
        up = lambda service: {'loaded': True, 'state': 'running', 'pid': 9, 'last_exit': 0}
        saved, S.RECEIVE_INBOX = S.RECEIVE_INBOX, self.dir / 'no-inbox'
        self.addCleanup(lambda: setattr(S, 'RECEIVE_INBOX', saved))
        checks = S.capability_probe('tailshare-receive', state=up, run=lambda argv, **k: Fake(returncode=0, stdout='', stderr=''))
        self.assertEqual([(c['check'], c['result']) for c in checks], [('launchd_running', 'pass'), ('receive_freshness', 'unverified')])
        self.assertEqual(checks[-1]['code'], 'not_waiting')
        # A service with no real evidence for its entry still carries the placeholder.
        S.MISSING_EVIDENCE = {**self.saved_missing, 'whisper': [('whisper_extra', 'something not probed')]}
        got = S.capability_probe('whisper', fetch=lambda url, **k: (200, {'status': 'ok'}), run=lambda argv, **k: Fake(returncode=0, stdout='{}', stderr=''))
        self.assertEqual(got[-1]['code'], 'evidence_missing')

    def test_real_evidence_does_not_unlock_a_restart(self):
        for service in ('soma', 'tailshare-serve', 'tailshare-receive'):
            self.assertFalse(S.verifiable(service), service)

    def test_embeddings_and_the_transport_restart_only_when_down_and_only_on_the_owners_signature(self):
        for service in ('embeddings', 'imessage-transport'):
            self.assertTrue(S.verifiable(service), service)
        # No signature: refused, nothing restarted.
        challenge = self.control.request('imessage-transport')
        with self.assertRaises(S.Refused):
            self.control.approve('imessage-transport', {**self.approve_body(challenge), 'owner_signature': 'bm90IGhpcw=='})
        self.assertEqual(self.backend.calls, [])
        # Healthy: signed or not, left alone.
        self.up_before = True
        op = self.op(self.control.approve('imessage-transport', self.approve_body(self.control.request('imessage-transport'))))
        self.assertEqual((op['stage'], op['restarted'], self.backend.calls), ('restored', False, []))
        # Down, and signed: restarted, and only that one.
        self.up_before = False
        op = self.op(self.control.approve('imessage-transport', self.approve_body(self.control.request('imessage-transport'))))
        self.assertEqual((op['stage'], self.backend.calls), ('restored', [('gui', 'imessage-transport')]))

    def test_an_imessage_api_that_is_only_waiting_on_its_transport_is_not_down(self):
        saved_token, saved_endpoint = S.IMESSAGE_TOKEN, S.endpoint
        self.addCleanup(lambda: (setattr(S, 'IMESSAGE_TOKEN', saved_token), setattr(S, 'endpoint', saved_endpoint)))
        S.IMESSAGE_TOKEN, S.endpoint = self.dir / 'token', lambda service, run=None, load=None: ('127.0.0.1', 8799)
        S.IMESSAGE_TOKEN.write_text('im-SECRET')
        def api(**reply):
            return S._probe('imessage-api', fetch=lambda url, **k: (200, reply))[0]['result']
        self.assertEqual(api(ok=True, sendReady=True, sendTransport='rustpush'), 'pass')
        self.assertEqual(api(ok=True, sendReady=False, sendTransport='rustpush'), 'pass')  # the transport's check says it, on its own row
        self.assertEqual(api(ok=True, sendReady=False, readOnly=True), 'pass')
        self.assertEqual(api(ok=True, sendReady=False, sendTransport='other'), 'fail')
        self.assertEqual(api(ok=False, sendReady=True, sendTransport='rustpush'), 'fail')

    def test_a_service_not_installed_here_is_refused_and_listed_separately(self):
        S.installed = lambda service: service != 'herd'
        op = self.op(self.control.approve('herd', self.approve_body(self.control.request('herd'))))
        self.assertEqual((op['stage'], self.backend.calls), ('blocked', []))
        by = {x['id']: x for x in self.control.inventory(budget=2)['services']}
        self.assertEqual((by['herd']['installed'], by['herd']['restart']['blocked_reason']), (False, 'not_installed'))
        self.assertEqual((by['syncthing']['optional'], by['syncthing']['loaded'], by['syncthing']['running']), (True, True, True))

    def test_restore_leaves_a_service_that_is_already_healthy_alone(self):
        S.MISSING_EVIDENCE = {}
        self.up_before = True
        op = self.op(self.control.approve('canvas', self.approve_body(self.control.request('canvas'))))
        self.assertEqual((op['stage'], op['verified'], op['restarted'], self.backend.calls), ('restored', True, False, []))
        self.assertTrue(all(c['result'] == 'pass' for c in op['checks']))  # the phone only accepts "restored" on these
        # A build tool or an agent's error on the runner's row isn't the runner being down.
        checks = [S.mkcheck('runner_self', 'runner', 'ok', 'pass'), S.mkcheck('build_toolchain.jdk', 'runner', 'no_usable_jdk', 'fail'),
                  S.mkcheck('agent_turn.claude', 'claude', 'overloaded', 'fail')]
        self.assertEqual([c['check'] for c in S.healthy_now(checks)], ['runner_self'])
        # Quiet is not down, and is not what proves it up; really down is restarted.
        self.assertEqual(S.healthy_now([S.mkcheck('receive_freshness', 'x', 'q', 'quiet')]), [])
        self.assertEqual(S.healthy_now([S.mkcheck('x', 'x', 'down', 'fail')]), [])
        self.up_before = False
        op = self.op(self.control.approve('canvas', self.approve_body(self.control.request('canvas'))))
        self.assertEqual((op['stage'], self.backend.calls), ('restored', [('gui', 'canvas')]))

    def test_a_restart_that_could_only_be_half_verified_is_refused_before_it_starts(self):
        op = self.op(self.control.approve('soma', self.approve_body(self.control.request('soma'))))
        self.assertEqual((op['stage'], op['verified'], self.backend.calls), ('blocked', False, []))
        self.assertIn('soma_sync', [c['check'] for c in op['checks']])
        incident = json.loads((self.dir / 'state' / 'incidents' / 'newest.json').read_text())
        self.assertEqual(incident['root_cause'], 'incomplete_capability_proof')
        by = {x['id']: x for x in self.control.inventory(budget=2)['services']}
        self.assertEqual((by['soma']['restart']['supported'], by['soma']['restart']['blocked_reason']), (False, 'incomplete_capability_proof'))
        self.assertTrue(by['syncthing']['restart']['supported'])


class RunnerCapabilities(unittest.TestCase):
    """Mock-only: temporary homes, mirrors and SDKs, and a fake `run`; nothing real is read or run."""

    def setUp(self):
        self.home = pathlib.Path(tempfile.mkdtemp()).resolve()
        self.saved_env = os.environ.pop('CODEX_HOME', None)
        self.now = 1_800_000_000_000

    def tearDown(self):
        if self.saved_env is not None:
            os.environ['CODEX_HOME'] = self.saved_env

    def fake_run(self, answers):
        def run(argv, **k):
            for prefix, (code, out, err) in answers.items():
                if tuple(argv[:len(prefix)]) == prefix:
                    return Fake(returncode=code, stdout=out, stderr=err)
            raise AssertionError('unexpected command: %r' % (argv,))
        return run

    def test_toolchain_answers_are_run_not_assumed(self):
        sdk = self.home / 'sdk'
        for d in ('platform-tools', 'build-tools/36.0.0', 'platforms/android-36'):
            (sdk / d).mkdir(parents=True)
        (sdk / 'platform-tools/adb').write_text('')
        mirror = self.home / 'mirror'
        (mirror / 'gradle/wrapper').mkdir(parents=True)
        (mirror / 'gradle/wrapper/gradle-wrapper.properties').write_text('distributionUrl=https\\://services.gradle.org/distributions/gradle-9.8-bin.zip\n')
        jdk = self.home / 'jdk'
        (jdk / 'bin').mkdir(parents=True)
        (jdk / 'bin/java').write_text('')
        os.chmod(jdk / 'bin/java', 0o755)
        answers = {('/usr/libexec/java_home', '-v', '17'): (0, str(jdk) + '\n', ''), ('/usr/libexec/java_home',): (1, '', 'none'),
                   (str(jdk / 'bin/java'), '-version'): (0, '', 'openjdk version "17.0.12" 2024-07-16\n'),
                   (str(sdk / 'platform-tools/adb'), 'version'): (0, 'Android Debug Bridge version 1.0.41\n', '')}
        got = {c['check'].split('.', 1)[1]: c for c in S.toolchain_checks(run=self.fake_run(answers), env={'ANDROID_HOME': str(sdk)}, home=self.home, mirror=mirror)}
        self.assertEqual((got['jdk']['result'], got['jdk']['fields']['major']), ('pass', 17))
        self.assertEqual((got['jdk']['check'], got['jdk']['fields']['capability']), ('build_toolchain.jdk', 'Builds'))
        self.assertEqual((got['android_sdk']['result'], got['android_sdk']['fields']['platforms']), ('pass', ['android-36']))
        self.assertEqual((got['gradle_wrapper']['code'], got['gradle_wrapper']['result']), ('distribution_not_downloaded', 'unverified'))
        (self.home / '.gradle/wrapper/dists/gradle-9.8-bin/abc/gradle-9.8/bin').mkdir(parents=True)
        (self.home / '.gradle/wrapper/dists/gradle-9.8-bin/abc/gradle-9.8/bin/gradle').write_text('')
        answers[(str(sdk / 'platform-tools/adb'), 'version')] = (1, '', 'broken')
        got = {c['check'].split('.', 1)[1]: c for c in S.toolchain_checks(run=self.fake_run(answers), env={'ANDROID_HOME': str(sdk)}, home=self.home, mirror=mirror)}
        self.assertEqual(got['gradle_wrapper']['result'], 'pass')
        self.assertEqual((got['android_sdk']['code'], got['android_sdk']['result']), ('sdk_incomplete', 'fail'))
        (mirror / 'local.properties').write_text('sdk.dir=%s\n' % sdk)  # the builds' own setting wins, no environment needed
        answers[(str(sdk / 'platform-tools/adb'), 'version')] = (0, 'ok', '')
        got = {c['check'].split('.', 1)[1]: c for c in S.toolchain_checks(run=self.fake_run(answers), env={}, home=self.home, mirror=mirror)}
        self.assertEqual(got['android_sdk']['result'], 'pass')

    def test_the_source_mirror_reports_what_the_sync_agent_and_git_say(self):
        mirror = self.home / 'mirror'
        (mirror / '.git').mkdir(parents=True)
        (mirror / '.git/FETCH_HEAD').write_text('')
        status = self.home / 'status.txt'

        def git(branch, head, phone):
            return self.fake_run({('/usr/bin/git', '-C', str(mirror), 'rev-parse', '--git-common-dir'): (0, '.git\n', ''),
                                  ('/usr/bin/git', '-C', str(mirror), 'branch'): (0, branch + '\n', ''),
                                  ('/usr/bin/git', '-C', str(mirror), 'rev-parse', '--short', 'HEAD'): (0, head + '\n', ''),
                                  ('/usr/bin/git', '-C', str(mirror), 'rev-parse', '--short', '--verify'): (0, phone + '\n', '')})
        got = S.source_mirror(run=git('fold-sync', 'abc1234', 'abc1234'), mirror=mirror, status_file=status)
        self.assertEqual((got['code'], got['result']), ('no_sync_agent', 'not_installed'))  # e.g. the MacBook: an absence, not an Unknown
        status.write_text('Mac Fold source synced: abc1234')
        got = S.source_mirror(run=git('fold-sync', 'abc1234', 'abc1234'), mirror=mirror, status_file=status)
        self.assertEqual((got['code'], got['result']), ('synced', 'pass'))
        self.assertEqual((got['check'], got['fields']['capability']), ('source_freshness.mirror', 'Sync'))
        self.assertIsInstance(got['fields']['fetched_at'], int)
        got = S.source_mirror(run=git('fold-sync', 'abc1234', 'def5678'), mirror=mirror, status_file=status)
        self.assertEqual(got['result'], 'unverified')
        status.write_text('Paused: mirror branch changed; preserving local work.')
        got = S.source_mirror(run=git('soma-mesh', 'abc1234', 'def5678'), mirror=mirror, status_file=status)
        self.assertEqual((got['code'], got['result'], got['fields']['branch']), ('mirror_sync_paused', 'fail', 'soma-mesh'))
        self.assertIn('owner decides', got['fields']['next'])

    def transcript(self, path, *records):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(''.join(json.dumps(r) + '\n' for r in records))

    def at(self, ms):
        return datetime.datetime.fromtimestamp(ms / 1000, datetime.timezone.utc).isoformat().replace('+00:00', 'Z')

    def test_a_real_failed_turn_sets_an_agents_status_and_a_good_one_clears_it(self):
        S._agent_trouble.clear()
        self.addCleanup(S._agent_trouble.clear)
        self.assertEqual(S.agent_checks(), [])  # nothing heard is nothing wrong
        for words, code in (('Not logged in \u00b7 Please run /login', 'sign_in'), ('Failed to authenticate. API Error: 401 OAuth access token expired', 'sign_in'),
                            ("You've hit your weekly limit \u00b7 resets Oct 18", 'usage_limit'), ('API Error: 429 rate_limit_error', 'usage_limit'),
                            ('API Error: 529 overloaded_error', 'overloaded'), ('API Error: Connection lost mid-response', 'no_connection'),
                            ('stream disconnected before completion', 'no_connection'), ('API Error: 500 internal', 'server_error')):
            self.assertEqual(S.agent_error(words), code, words)
        # Not the service being down: nothing is set, and an earlier error stands.
        for words in ('Codex ended the turn: failed', "API Error: Opus's safeguards flagged this session", 'the build failed', ''):
            self.assertIsNone(S.agent_error(words), words)
        S.agent_turn_ended('claude', True, 'the build failed')
        self.assertEqual(S.agent_checks(), [])
        S.agent_turn_ended('claude', True, 'API Error: 529   overloaded_error', now=5)
        S.agent_turn_ended('codex', True, 'no words', limited=True, now=6)
        got = {c['target']: c for c in S.agent_checks()}
        self.assertEqual((got['claude']['check'], got['claude']['code'], got['claude']['result']), ('agent_turn.claude', 'overloaded', 'fail'))
        self.assertEqual(got['claude']['fields'], {'capability': 'Agents', 'agent': 'claude', 'error': 'API Error: 529 overloaded_error', 'at': 5})
        self.assertEqual(got['codex']['code'], 'usage_limit')
        S.agent_turn_ended('claude', True, 'Not logged in')
        self.assertEqual({c['target']: c['result'] for c in S.agent_checks()}, {'claude': 'auth', 'codex': 'fail'})
        S.agent_turn_ended('claude', False)  # a good turn clears only its own agent
        self.assertEqual([c['target'] for c in S.agent_checks()], ['codex'])

    def test_no_sign_in_check_and_no_transcript_is_read(self):
        (self.home / '.claude/projects/p').mkdir(parents=True)
        self.assertFalse(hasattr(S, 'provider_checks'))
        self.assertNotIn('provider_auth', ' '.join(S.CAPABILITY_SCOPES))
        import inspect
        source = inspect.getsource(S)
        for read in ('.claude/projects', 'rollout-', 'auth.json', '.credentials.json'):
            self.assertNotIn(read, source, read)

    def test_past_traffic_is_the_evidence_and_nothing_is_sent(self):
        saved = S.SOMA_SYNCED, S.TAILSHARE_MAIL, S.RECEIVE_INBOX
        self.addCleanup(lambda: (setattr(S, 'SOMA_SYNCED', saved[0]), setattr(S, 'TAILSHARE_MAIL', saved[1]), setattr(S, 'RECEIVE_INBOX', saved[2])))
        S.SOMA_SYNCED, S.TAILSHARE_MAIL, S.RECEIVE_INBOX = self.home / 'synced.json', self.home / 'mail', self.home / 'Inbox'
        self.assertEqual(S.soma_sync(self.now)['code'], 'no_sync_record')
        S.SOMA_SYNCED.write_text('{}')
        os.utime(S.SOMA_SYNCED, (self.now / 1000 - 60, self.now / 1000 - 60))
        got = S.soma_sync(self.now)
        self.assertEqual((got['code'], got['result'], got['fields']['last_synced_at']), ('synced_recently', 'pass', self.now - 60_000))
        os.utime(S.SOMA_SYNCED, (self.now / 1000 - 3600, self.now / 1000 - 3600))
        self.assertEqual((S.soma_sync(self.now)['code'], S.soma_sync(self.now)['result']), ('no_recent_sync', 'quiet'))
        # Delivery: the newest kept message, read or not; a quiet stretch is only quiet.
        self.assertEqual(S.tailshare_delivery(self.now)['code'], 'no_delivery_record')
        (S.TAILSHARE_MAIL / 'read').mkdir(parents=True)
        (S.TAILSHARE_MAIL / 'read/msg-1.json').write_text('{"text": "PRIVATE"}')
        os.utime(S.TAILSHARE_MAIL / 'read/msg-1.json', (self.now / 1000 - 3600, self.now / 1000 - 3600))
        got = S.tailshare_delivery(self.now)
        self.assertEqual((got['code'], got['result']), ('delivered_recently', 'pass'))
        self.assertNotIn('PRIVATE', json.dumps(got))
        got = S.tailshare_delivery(self.now + 2 * S.RECENT_PROOF_MS)
        self.assertEqual((got['code'], got['result']), ('no_recent_delivery', 'quiet'))
        # Receive: its waiter under this worker, and a file lately; a waiter under another process doesn't count.
        up = lambda service: {'state': 'running', 'pid': 42}
        ps = lambda out: self.fake_run({('/bin/ps',): (0, out, '')})
        waiter = ' 42 /Applications/T/Tailscale file get --wait --conflict=rename --verbose /x\n'
        got = S.receive_freshness(ps(waiter), up, self.now)
        self.assertEqual((got['code'], got['result'], got['fields']['waiting']), ('waiting_no_recent_file', 'quiet', True))
        self.assertEqual(S.receive_freshness(ps(waiter.replace('42', '43')), up, self.now)['code'], 'not_waiting')
        S.RECEIVE_INBOX.mkdir()
        (S.RECEIVE_INBOX / 'a.txt').write_text('PRIVATE')
        os.utime(S.RECEIVE_INBOX / 'a.txt', (self.now / 1000 - 3600, self.now / 1000 - 3600))
        got = S.receive_freshness(ps(waiter), up, self.now)
        self.assertEqual((got['code'], got['result']), ('received_recently', 'pass'))
        self.assertEqual(S.receive_freshness(ps(''), up, self.now)['result'], 'unverified')

    def test_the_model_really_answers(self):
        asked = []
        def fetch(url, **k):
            asked.append((urllib.parse.urlsplit(url).path, k.get('body')))
            return 200, {'vectors': [[0.1] * 256]}
        got = S.embed_inference(fetch, ('127.0.0.1', 8100), 256)
        self.assertEqual((got['code'], got['result'], got['fields']['size']), ('model_answered', 'pass', 256))
        self.assertEqual(asked, [('/text', {'texts': ['soma connections check']})])
        self.assertEqual(S.embed_inference(fetch, ('127.0.0.1', 8100), 768)['result'], 'fail')
        self.assertEqual(S.embed_inference(lambda url, **k: (200, {'vectors': []}), ('127.0.0.1', 8100), 256)['result'], 'fail')
        def down(url, **k):
            raise urllib.error.HTTPError(url, 500, 'x', {}, None)
        self.assertEqual(S.embed_inference(down, ('127.0.0.1', 8100), 256)['code'], 'no_model_answer')

    def test_the_transport_is_asked_through_the_apis_health(self):
        saved_token, saved_endpoint = S.IMESSAGE_TOKEN, S.endpoint
        self.addCleanup(lambda: (setattr(S, 'IMESSAGE_TOKEN', saved_token), setattr(S, 'endpoint', saved_endpoint)))
        S.IMESSAGE_TOKEN, S.endpoint = self.home / 'token', lambda service, run=None, load=None: ('127.0.0.1', 8799)
        self.assertEqual(S.transport_traversal(None, None)['code'], 'no_token')
        S.IMESSAGE_TOKEN.write_text('im-SECRET')
        reply = {'sendTransport': 'rustpush', 'sendReady': True, 'readOnly': False, 'sendProblem': None}
        asked = []
        def fetch(url, headers=None, **k):
            asked.append(headers)
            return 200, reply
        got = S.transport_traversal(fetch, None)
        self.assertEqual((got['code'], got['result']), ('transport_answered', 'pass'))
        self.assertEqual(asked, [{'Authorization': 'Bearer im-SECRET'}])
        self.assertNotIn('SECRET', json.dumps(got))
        reply.update(sendReady=False, sendProblem='bridge said no')
        got = S.transport_traversal(fetch, None)
        self.assertEqual((got['code'], got['result'], got['fields']['send_problem']), ('transport_not_ready', 'fail', 'bridge said no'))
        reply.update(readOnly=True)  # sendReady is false by the read-only switch, whatever the transport says
        self.assertEqual(S.transport_traversal(fetch, None)['code'], 'transport_not_asked')
        reply.update(readOnly=False, sendTransport='applescript')
        self.assertEqual(S.transport_traversal(fetch, None)['result'], 'unverified')
        def no_api(url, **k):
            raise urllib.error.URLError('refused')
        got = S.transport_traversal(no_api, None)
        self.assertEqual((got['code'], got['result']), ('api_unanswered', 'unverified'))

    def test_the_tailscale_cli_is_called_by_its_real_path_with_a_term_and_a_gui_complaint_is_not_an_outage(self):
        saved, S.TAILSCALE_PATHS = S.TAILSCALE_PATHS, (str(self.home / 'tailscale'),)
        self.addCleanup(lambda: setattr(S, 'TAILSCALE_PATHS', saved))
        (self.home / 'real-tailscale').write_text('')
        os.symlink(self.home / 'real-tailscale', self.home / 'tailscale')
        seen = []
        def run(argv, env=None, **k):
            seen.append((argv[0], env.get('TERM')))
            return Fake(returncode=0, stdout='{"Self": {}}', stderr='')
        term = os.environ.pop('TERM', None)
        self.addCleanup(lambda: term is None or os.environ.__setitem__('TERM', term))
        self.assertEqual(S.tailscale_status(run), {'Self': {}})
        self.assertEqual(seen, [(os.path.realpath(self.home / 'real-tailscale'), 'dumb')])
        gui = lambda argv, **k: Fake(returncode=0, stdout='The Tailscale GUI failed to start: CLIError error 3\n', stderr='')
        with self.assertRaises(S.Unverified) as caught:
            S.tailscale_status(gui)
        self.assertIn('GUI failed', str(caught.exception))
        endpoint = S.endpoint
        S.endpoint = lambda service, run=None, load=None: S.tailnet_ip(run)
        self.addCleanup(lambda: setattr(S, 'endpoint', endpoint))
        saved_plist, S.plist_path = S.plist_path, lambda service: self.home / 'x.plist'
        self.addCleanup(lambda: setattr(S, 'plist_path', saved_plist))
        (check,) = S._probe('herd', fetch=lambda url, **k: (200, {}), run=gui)
        self.assertEqual((check['code'], check['result']), ('reachable_unverified', 'unverified'))

    def test_build_locks_are_read_not_taken(self):
        locks = self.home / 'locks'
        locks.mkdir()
        (locks / 'walks.lock').write_text('')
        import fcntl
        with open(locks / 'walks.lock', 'a') as held:
            fcntl.flock(held, fcntl.LOCK_EX)
            got = S.build_locks(S.WorkGate(locks))
        self.assertEqual((got['check'], got['result'], got['fields']), ('build_toolchain.locks', 'pass', {'capability': 'Builds', 'locks': 1, 'held': ['walks']}))


class CanvasAndHerd(unittest.TestCase):
    def setUp(self):
        self.saved = S.plist_path
        self.dir = pathlib.Path(tempfile.mkdtemp()).resolve()
        S.plist_path = lambda service: self.dir / 'canvas.plist'

    def tearDown(self):
        S.plist_path = self.saved

    def test_canvas_loads_a_board_with_its_own_token_which_never_leaves(self):
        import plistlib
        server = self.dir / 'soma-canvas'
        (server / 'data').mkdir(parents=True)
        (self.dir / 'canvas.plist').write_bytes(plistlib.dumps({'ProgramArguments': ['/bin/zsh', '-lc', 'cd %s && npx tsx src/server/server.ts' % server],
                                                               'EnvironmentVariables': {'PATH': '/usr/bin'}}))
        self.assertEqual(S.canvas_boards(lambda *a, **k: None, ('127.0.0.1', 5860))['code'], 'no_agent_token')
        # The real plist's command starts with an export, and a board's id can have dashes.
        real = self.dir / 'real-plist.plist'
        real.write_bytes(plistlib.dumps({'ProgramArguments': ['/bin/zsh', '-lc', 'export PATH="/x:$PATH"; cd %s && NODE_ENV=production exec npx tsx src/server/server.ts' % server]}))
        S.plist_path = lambda service: real
        (server / 'data/agent-token.txt').write_text('canvas-agent-SECRET\n')
        dashed = S.canvas_boards(lambda url, **k: (200, {'apps': [{'id': 'soma-canvas'}]}) if url.endswith('/api/apps') else (200, {}), ('127.0.0.1', 5861))
        self.assertEqual((dashed['code'], dashed['result']), ('ok', 'pass'))
        (server / 'data/agent-token.txt').unlink()
        S.plist_path = lambda service: self.dir / 'canvas.plist'
        (server / 'data/agent-token.txt').write_text('canvas-agent-SECRET\n')
        asked = []
        def fetch(url, headers=None, **k):
            asked.append((urllib.parse.urlsplit(url).path, headers))
            return (200, {'apps': [{'id': 'abcdefgh23456789', 'title': 'PRIVATE board'}]}) if url.endswith('/api/apps') else (200, {'shapes': []})
        got = S.canvas_boards(fetch, ('127.0.0.1', 5861))
        self.assertEqual((got['code'], got['result'], got['fields']), ('ok', 'pass', {'boards': 1, 'board_loaded': True, 'http_status': 200}))
        self.assertEqual([a[0] for a in asked], ['/api/apps', '/api/boards/abcdefgh23456789/snapshot'])
        self.assertEqual(asked[0][1], {'x-canvas-token': 'canvas-agent-SECRET'})
        self.assertNotIn('SECRET', json.dumps(got))
        self.assertNotIn('PRIVATE', json.dumps(got))
        got = S.canvas_boards(lambda url, **k: (200, {'apps': []}), ('127.0.0.1', 5861))
        self.assertEqual((got['code'], got['result']), ('no_boards', 'unverified'))

    def test_herd_report_is_made_when_asked_and_keeps_counts_only(self):
        saved = S.TAILSCALE_PATHS
        S.TAILSCALE_PATHS = (sys.executable,)
        run = lambda argv, **k: Fake(returncode=0, stdout=json.dumps({'Self': {'TailscaleIPs': ['100.64.0.9']}}), stderr='')
        try:
            (check,) = S._probe('herd', fetch=lambda url, **k: (200, {'machine': {'host': 'h', 'herd': '2.1'}, 'sessions': [{'title': 'PRIVATE'}] * 3}), run=run)
            self.assertEqual((check['result'], check['fields']['sessions'], check['fields']['reported_now']), ('pass', 3, True))
            self.assertNotIn('PRIVATE', json.dumps(check))
            (check,) = S._probe('herd', fetch=lambda url, **k: (200, {'machine': 'h'}), run=run)
            self.assertEqual(check['result'], 'fail')
        finally:
            S.TAILSCALE_PATHS = saved


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

    def test_the_asking_devices_key_reaches_its_probe_and_is_never_cached(self):
        got = []
        def probe(service, keys=None):
            got.append((service, keys and keys['soma']()))
            return [S.mkcheck('x', service, 'ok', 'pass')]
        cache = S.ProbeCache(probe, lambda s: {}, lambda: 1)
        rows = cache.snapshot(['soma'], budget=2, keys={'soma': lambda: 'device-key-SECRET'})
        self.assertEqual(got, [('soma', 'device-key-SECRET')])
        self.assertNotIn('SECRET', json.dumps(rows))
        self.assertNotIn('SECRET', json.dumps(cache.rows))
        cache.snapshot(['soma'], budget=2)
        self.assertEqual(got[-1], ('soma', None))  # the next asker without a key lends nothing

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


CLIENT = """import json, socket, subprocess, sys
sock_path, mode = sys.argv[1], sys.argv[2]
proof = sys.stdin.read()
if mode == 'child':  # the runner's own child, same interpreter, asks instead
    r = subprocess.run([sys.executable, '-I', __file__, sock_path, 'direct'], input=proof, capture_output=True, text=True)
    sys.stdout.write(r.stdout)
else:
    s = socket.socket(socket.AF_UNIX)
    s.settimeout(30)
    s.connect(sock_path)
    if mode != 'stall':
        s.sendall(proof.encode())
        s.shutdown(socket.SHUT_WR)
    data = b''
    while True:
        chunk = s.recv(4096)
        if not chunk:
            break
        data += chunk
    sys.stdout.write(data.decode())
"""


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

    def test_forged_unsigned_replayed_and_wrong_service_requests_restart_nothing(self):
        # Forged: another key's signature under the owner's key id, and the owner's signature over something else.
        forged = self.proof('herd')
        forged['owner_signature'] = sign(owner_key()[0], S.canonical_payload(forged['approval'], forged['nonce'], 'herd', 'jacks-mac-pro', forged['expires_at']))
        other_nonce = self.proof('herd')
        other_nonce['nonce'] = 'n' * 32
        changed_expiry = self.proof('herd')
        changed_expiry['expires_at'] -= 1000
        garbage = {**self.proof('herd'), 'owner_signature': 'bm90IGEgc2lnbmF0dXJl'}
        for name, body in (('forged', forged), ('nonce', other_nonce), ('expiry', changed_expiry), ('garbage', garbage)):
            r = self.run_helper(body)
            self.assertEqual((r.returncode, r.stdout, r.stderr.strip()), (65, '', 'refused: bad_signature'), name)
        # Unsigned, with a key that isn't pinned, and a revoked key.
        unsigned = self.proof('herd')
        unsigned.pop('owner_signature')
        self.assertEqual((self.run_helper(unsigned).returncode, self.run_helper(unsigned).stdout), (65, ''))
        trust = json.loads((self.dir / 'connections-owner-keys.json').read_text())
        trust['keys'][0]['revoked'] = True
        (self.dir / 'connections-owner-keys.json').write_text(json.dumps(trust))
        self.assertEqual(self.run_helper(self.proof('herd')).stderr.strip(), 'refused: owner_key')
        trust['keys'][0]['revoked'] = False
        (self.dir / 'connections-owner-keys.json').write_text(json.dumps(trust))
        # Wrong service: a signature for one service on a request for another, a service the helper doesn't have, and its own request edited.
        self.assertEqual(self.run_helper(self.proof('tailscaled', sign_service='embeddings')).stderr.strip(), 'refused: bad_signature')
        for service in ('canvas', 'runner', 'soma', '../../bin/sh', 'herd; reboot'):
            r = self.run_helper(self.proof(service))
            self.assertEqual((r.returncode, r.stdout, r.stderr.strip()), (65, '', 'refused: bad_request'), service)
        # Replayed: the same one, alone and from inside a signed plan; and each entry of a plan only for itself.
        body = self.proof('herd')
        self.assertEqual(self.run_helper(body).returncode, 0)
        self.assertEqual(self.run_helper(body).stderr.strip(), 'refused: approval_already_used')
        planned = self.proof('whisper', plan=[])
        self.assertEqual(self.run_helper(planned).returncode, 0)
        r = self.run_helper(planned)
        self.assertEqual((r.returncode, r.stdout, r.stderr.strip()), (65, '', 'refused: approval_already_used'))
        # A plan's signature doesn't stretch to a restart that isn't in it, however it is dressed.
        extra = self.proof('whisper', plan=[])
        outsider = base64.b64encode(S.canonical_payload('svc-' + 'b' * 32, 'm' * 32, 'tailscaled', 'jacks-mac-pro', extra['expires_at']).encode()).decode()
        r = self.run_helper({**extra, 'owner_payloads': extra['owner_payloads'] + [outsider]})
        self.assertEqual((r.returncode, r.stderr.strip()), (65, 'refused: bad_signature'))

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

class HelperInstaller(unittest.TestCase):
    """Run for real in a test's own folder, with no root: everything but the ownership and the sudo smoke test."""
    installer = HERE / 'install-service-helper.sh'

    def setUp(self):
        import hashlib, shutil
        if os.geteuid() == 0 or not shutil.which('swiftc'):
            self.skipTest('needs swiftc and a non-root user')
        self.dir = pathlib.Path(tempfile.mkdtemp()).resolve()
        self.root = self.dir / 'root'
        self.sha = hashlib.sha256((HERE / 'soma-service-helper.swift').read_bytes()).hexdigest()
        _, self.key_id, public = owner_key()
        self.key = {'schema': 1, 'algorithm': 'ecdsa-p256-sha256', 'key_id': self.key_id, 'public_key': public, 'auth_per_use': True}

    def run_it(self, *args, root=True, user='jackkim', key=None):
        import subprocess
        (self.dir / 'key.json').write_text(json.dumps(key if key is not None else self.key))
        env = {'PATH': '/usr/bin:/bin', **({'SOMA_INSTALL_TEST_ROOT': str(self.root), 'SOMA_INSTALL_TEST_USER': user} if root else {})}
        return subprocess.run(['/bin/sh', str(self.installer), *args], capture_output=True, text=True, timeout=300, env=env)

    def test_install_lays_out_a_build_with_no_test_mode_the_one_rule_and_a_key_the_runner_accepts(self):
        r = self.run_it('install', 'jacks-mac-pro', str(self.dir / 'key.json'), self.sha)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn(self.key_id, r.stdout)
        rule = (self.root / 'etc/sudoers.d/soma-service-helper').read_text()
        self.assertEqual(rule, 'jackkim ALL=(root) NOPASSWD: /Library/PrivilegedHelperTools/soma-service-helper ""\n')
        self.assertEqual(stat.S_IMODE((self.root / 'etc/sudoers.d/soma-service-helper').stat().st_mode), 0o440)
        support = self.root / 'Library/Application Support/Soma'
        self.assertEqual(json.loads((support / 'helper.json').read_text()), {'version': 1, 'host': 'jacks-mac-pro'})
        binary = self.root / 'Library/PrivilegedHelperTools/soma-service-helper'
        self.assertEqual(stat.S_IMODE(binary.stat().st_mode), 0o755)
        self.assertNotIn(b'SOMA_HELPER_TEST_DIR', binary.read_bytes())
        self.assertEqual(S.load_trust(support / 'connections-owner-keys.json', require_root=False, lstat=self.owned_ok).state, 'ready')
        r = self.run_it('remove')
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertFalse(binary.exists() or (self.root / 'etc/sudoers.d/soma-service-helper').exists())
        self.assertTrue((support / 'connections-owner-keys.json').exists())

    def owned_ok(self, path):  # above the test's own folder, /tmp's folders are not this user's: only the file's own rules are tested here
        info = os.lstat(path)
        if str(path).startswith(str(self.dir)):
            return info
        return os.lstat('/usr')

    def test_nothing_is_installed_from_a_changed_source_or_a_bad_key(self):
        r = self.run_it('install', 'jacks-mac-pro', str(self.dir / 'key.json'), '0' * 64)
        self.assertEqual(r.returncode, 65)
        self.assertIn("isn't the reviewed one", r.stderr)
        self.assertFalse(self.root.exists())
        for name, key in (('wrong id', {**self.key, 'key_id': 'a' * 64}), ('json in the key', {**self.key, 'public_key': 'abc", "revoked": true, "x": "'}),
                          ('another algorithm', {**self.key, 'algorithm': 'rsa'}), ('no key', {'schema': 1})):
            r = self.run_it('trust', str(self.dir / 'key.json'), key=key)
            self.assertEqual(r.returncode, 65, name)
            self.assertFalse((self.root / 'Library/Application Support/Soma/connections-owner-keys.json').exists(), name)

    def test_it_needs_root_a_plain_user_and_a_plain_host(self):
        self.assertEqual(self.run_it('trust', str(self.dir / 'key.json'), root=False).returncode, 77)
        self.assertEqual(self.run_it('install', 'Bad Host', str(self.dir / 'key.json'), self.sha).returncode, 64)
        self.assertEqual(self.run_it('install', 'jacks-mac-pro', str(self.dir / 'key.json'), self.sha, user='root').returncode, 64)
        self.assertEqual(self.run_it('remove', 'x').returncode, 64)
        r = self.run_it('print-sudoers', 'jackkim', root=False)
        self.assertEqual(r.stdout, 'jackkim ALL=(root) NOPASSWD: /Library/PrivilegedHelperTools/soma-service-helper ""\n')
        self.assertEqual(self.run_it('print-sudoers', 'jack kim', root=False).returncode, 64)


class LiveBackendAndTheHelper(unittest.TestCase):
    def setUp(self):
        self.saved = S.helper_installed
        self.addCleanup(lambda: setattr(S, 'helper_installed', self.saved))

    def test_no_helper_means_blocked_and_sudo_is_never_asked(self):
        S.helper_installed = lambda: False
        def run(*a, **k):
            raise AssertionError('sudo was asked')
        with self.assertRaises(S.Blocked) as caught:
            S.LiveBackend().privileged('herd', {}, run=run)
        self.assertEqual(str(caught.exception), 'privileged_helper_missing')

    def test_the_proof_goes_on_stdin_with_a_fixed_command_and_refusals_come_back_as_blocked(self):
        S.helper_installed = lambda: True
        asked = []
        def run(argv, input=None, **k):
            asked.append((argv, input))
            return Fake(returncode=0, stdout='', stderr='')
        S.LiveBackend().privileged('herd', {'owner_signature': 'SIG'}, run=run)
        self.assertEqual(asked, [(['/usr/bin/sudo', '-n', '/Library/PrivilegedHelperTools/soma-service-helper'], '{"owner_signature": "SIG"}')])
        refused = lambda argv, **k: Fake(returncode=65, stdout='', stderr='refused: approval_already_used\n')
        with self.assertRaises(S.Blocked) as caught:
            S.LiveBackend().privileged('herd', {}, run=refused)
        self.assertEqual(str(caught.exception), 'privileged_helper_refused:approval_already_used')
        no_rule = lambda argv, **k: Fake(returncode=1, stdout='', stderr='sudo: a password is required\n')
        with self.assertRaises(S.Blocked) as caught:
            S.LiveBackend().privileged('herd', {}, run=no_rule)
        self.assertEqual(str(caught.exception), 'privileged_helper_missing')
        broke = lambda argv, **k: Fake(returncode=3, stdout='', stderr='launchctl said no\n')
        with self.assertRaises(RuntimeError):
            S.LiveBackend().privileged('herd', {}, run=broke)

    def test_only_a_root_owned_helper_counts(self):
        S.helper_installed = self.saved
        mine = pathlib.Path(tempfile.mkdtemp()) / 'helper'
        mine.write_text('')
        saved, S.PRIVILEGED_HELPER = S.PRIVILEGED_HELPER, mine
        self.addCleanup(lambda: setattr(S, 'PRIVILEGED_HELPER', saved))
        self.assertFalse(S.helper_installed())
        class Owned:
            st_mode, st_uid = stat.S_IFREG | 0o755, 0
        self.assertTrue(S.helper_installed(lstat=lambda p: Owned))
        Owned.st_mode = stat.S_IFREG | 0o775
        self.assertFalse(S.helper_installed(lstat=lambda p: Owned))
        Owned.st_mode, Owned.st_uid = stat.S_IFLNK | 0o755, 0
        self.assertFalse(S.helper_installed(lstat=lambda p: Owned))


class LiveBackendIsNeverUsedHere(unittest.TestCase):
    def test_mock_backend_is_not_live(self):
        self.assertFalse(S.MockBackend.live)
        self.assertTrue(S.LiveBackend.live)


if __name__ == '__main__':
    unittest.main()
