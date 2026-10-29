"""soma_services: the Mac runner's owner-approved service restart (Connections circuit breaker), imported by oma-runner.

Boss-approved scope (Phone A 20261028-215622, refined 220739; Phone B 20261028-222316/222952/223127):
  GET  /services                    inventory of a FIXED allowlist, launchd state, read-only probes, work and trust state
  POST /services/ID/restart {}      creates a one-time challenge for the owner's native Restore tap; changes nothing
  POST /services/ID/restart {approval, nonce, decision, owner_key_id, owner_signature[, owner_payloads]}
                                    executes only after an Android Keystore owner signature verifies against a PINNED
                                    root-owned public key; the challenge is consumed before any mutation
  GET  /services/operations/ID      the durable operation record (redacted)

Nothing here enrolls or replaces owner keys, runs a shell, takes a path or command from a request, or restarts a
service whose restart cannot be verified by that service's own capability check. Times are epoch milliseconds.
"""
import base64, contextlib, fcntl, hashlib, hmac, json, os, pathlib, re, secrets, socket, stat, subprocess, tempfile, threading, time
import urllib.error, urllib.request

HOME = pathlib.Path.home()
TRUST_FILE = pathlib.Path('/Library/Application Support/Soma/connections-owner-keys.json')
STATE = HOME / '.local/share/soma/connections'
LOCK_DIR = pathlib.Path(os.environ.get('SOMA_LANES_STATE') or HOME / '.local/state/soma-macbuild')
CHALLENGE_TTL_MS = 120_000
PAYLOAD_TAG = 'soma-service-restart-v1'
PLAN_TAG = 'soma-service-restart-plan-v1'
MAX_PLAN = 16
MAX_PAYLOAD = 2048
MAX_KEYS = 8
KEEP_RECORDS = 200
LAUNCHCTL = '/bin/launchctl'
PRIVILEGED_HELPER = pathlib.Path('/Library/Application Support/Soma/soma-service-helper')


def now_ms():
    return int(time.time() * 1000)


# id -> (launchd domain, label, restart mode). The labels are the audited ones on the Mac Pro (read-only inventory,
# 2026-10-28). Modes: 'gui' = launchctl kickstart -k in the runner user's own GUI domain; 'self' = the runner journals
# the operation and exits so launchd KeepAlive relaunches it; 'privileged' = a root daemon: only a Boss-installed,
# root-owned fixed helper may restart it, otherwise the restart is blocked.
SERVICES = {
    'runner': ('system', 'dev.omalauncher.runner', 'self'),
    'tailshare-serve': ('system', 'com.tailshare.serve', 'privileged'),
    'tailshare-receive': ('system', 'com.tailshare.receive', 'privileged'),
    'herd': ('system', 'dev.herd.node', 'privileged'),
    'embeddings': ('system', 'dev.omalauncher.embed-server', 'privileged'),
    'whisper': ('system', 'dev.omalauncher.whisper-server', 'privileged'),
    'soma': ('gui', 'dev.omalauncher.soma.serve', 'gui'),
    'canvas': ('gui', 'com.jackkim.soma-canvas', 'gui'),
    'imessage-api': ('gui', 'dev.omalauncher.soma-imessage.direct-api', 'gui'),
    'imessage-transport': ('gui', 'dev.omalauncher.soma-imessage.direct-transport', 'gui'),
    'syncthing': ('gui', 'homebrew.mxcl.syncthing', 'gui'),
    'tailscaled': ('system', 'homebrew.mxcl.tailscale', 'privileged'),
}
SERVICE_ID = re.compile(r'[a-z][a-z0-9-]{1,31}')


def target(service):
    domain, label, _ = SERVICES[service]
    return f'system/{label}' if domain == 'system' else f'gui/{os.getuid()}/{label}'


def plist_path(service):
    domain, label, _ = SERVICES[service]
    return (pathlib.Path('/Library/LaunchDaemons') if domain == 'system' else HOME / 'Library/LaunchAgents') / f'{label}.plist'


_HOST = None
def host_identity():
    """This runner's fixed identity: the first label of its tailnet DNS name, else the short hostname, lowercase."""
    global _HOST
    if _HOST:
        return _HOST
    name = ''
    for tailscale in ('/opt/homebrew/bin/tailscale', '/usr/local/bin/tailscale', '/Applications/Tailscale.app/Contents/MacOS/Tailscale'):
        if not os.path.exists(tailscale):
            continue
        with contextlib.suppress(Exception):
            out = subprocess.run([tailscale, 'status', '--json'], capture_output=True, text=True, timeout=5, check=False).stdout
            name = (json.loads(out).get('Self') or {}).get('DNSName', '').split('.')[0]
        if name:
            break
    name = (name or socket.gethostname().split('.')[0]).lower()
    _HOST = name if re.fullmatch(r'[a-z0-9][a-z0-9-]{0,62}', name) else 'mac'
    return _HOST


# ---- redaction ----------------------------------------------------------------------------------------------------
_SECRETS = [  # applied in order: a header's whole value before the generic key=value rule can take only its first word
    re.compile(r'(?i)authorization["\']?\s*[:=]\s*["\']?(?:bearer\s+|basic\s+)?[^\s"\',}]+'),
    re.compile(r'(?i)bearer\s+[A-Za-z0-9._~+/=-]{8,}'),
    re.compile(r'(?:sk-|xox[a-z]-|ghp_|gho_|github_pat_)[A-Za-z0-9_-]{8,}'),
    re.compile(r'(?i)(?:token|api[_-]?key|key|secret|password|nonce|signature|cookie)["\']?\s*[:=]\s*["\']?[^\s"\',}]{4,}'),
]
_SECRET_FIELDS = {'nonce', 'owner_signature', 'signature', 'owner_payloads', 'payload', 'payload_b64', 'key', 'token', 'secret',
                  'authorization', 'private_key', 'public_key'}

def redact(value, depth=0):
    """Redacts at source and again at the final serializer: secret-looking fields are dropped, strings scrubbed."""
    if depth > 8:
        return None
    if isinstance(value, dict):
        return {k: redact(v, depth + 1) for k, v in value.items() if str(k).lower() not in _SECRET_FIELDS}
    if isinstance(value, (list, tuple)):
        return [redact(v, depth + 1) for v in value[:64]]
    if isinstance(value, str):
        for pattern in _SECRETS:
            value = pattern.sub('[redacted]', value)
        return value[:2000]
    return value


# ---- owner trust --------------------------------------------------------------------------------------------------
class Trust:
    def __init__(self, state, detail, keys=None):
        self.state, self.detail, self.keys = state, detail, keys or {}

    def public(self):
        return {'state': self.state, 'detail': self.detail, 'keys': sorted(self.keys)}


def _spki_key(der):
    from cryptography.hazmat.primitives.serialization import load_der_public_key
    from cryptography.hazmat.primitives.asymmetric import ec
    key = load_der_public_key(der)
    if not isinstance(key, ec.EllipticCurvePublicKey) or key.curve.name != 'secp256r1':
        raise ValueError('only ECDSA P-256 owner keys are accepted')
    return key


def load_trust(path=TRUST_FILE, require_root=True):
    """The pinned owner public keys. Missing -> setup blocked (never trust-on-first-use); anything unsafe -> untrusted."""
    path = pathlib.Path(path)
    try:
        info = os.lstat(path)
    except FileNotFoundError:
        return Trust('missing', f'Owner trust is not provisioned ({path}). Restore stays blocked until Boss installs it.')
    except OSError as e:
        return Trust('untrusted', f'Owner trust unreadable: {e.strerror}.')
    try:
        parent = os.lstat(path.parent)
    except OSError:
        return Trust('untrusted', 'Owner trust directory unreadable.')
    problems = []
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
        problems.append('not a regular file (symlinks are refused)')
    if info.st_mode & (stat.S_IWGRP | stat.S_IWOTH):
        problems.append('group- or world-writable')
    if stat.S_ISLNK(parent.st_mode) or parent.st_mode & (stat.S_IWGRP | stat.S_IWOTH):
        problems.append('directory is a symlink or group/world-writable')
    if require_root and (info.st_uid != 0 or parent.st_uid != 0):
        problems.append('not owned by root')
    if info.st_size > 65_536:
        problems.append('larger than 64 KB')
    if problems:
        return Trust('untrusted', 'Owner trust refused: ' + '; '.join(problems) + '.')
    try:
        data = json.loads(path.read_bytes())
        if data.get('version') != 1 or not isinstance(data.get('keys'), list) or len(data['keys']) > MAX_KEYS:
            raise ValueError('version 1 with at most %d keys expected' % MAX_KEYS)
        keys = {}
        for entry in data['keys']:
            if entry.get('revoked') is True:
                continue
            if entry.get('algorithm') != 'ecdsa-p256-sha256':
                raise ValueError('unsupported algorithm')
            der = base64.b64decode(entry['public_key'], validate=True)
            key_id = hashlib.sha256(der).hexdigest()
            if not hmac.compare_digest(key_id, str(entry.get('key_id', ''))):
                raise ValueError('key_id is not sha256(SPKI DER)')
            keys[key_id] = _spki_key(der)
    except Exception as e:  # noqa: BLE001 -- any malformed trust is refused, never partially used
        return Trust('untrusted', f'Owner trust refused: {str(e)[:160]}.')
    if not keys:
        return Trust('missing', 'Owner trust has no active keys. Restore stays blocked.')
    return Trust('ready', f'{len(keys)} pinned owner key(s).', keys)


def verify_signature(public_key, message: bytes, signature_der: bytes) -> bool:
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec
    try:
        public_key.verify(signature_der, message, ec.ECDSA(hashes.SHA256()))
        return True
    except (InvalidSignature, ValueError):
        return False


# ---- challenges ---------------------------------------------------------------------------------------------------
class Refused(Exception):
    def __init__(self, code, message, status=403):
        super().__init__(message)
        self.code, self.message, self.status = code, message, status


def canonical_payload(approval, nonce, service, host, expires_at):
    return '\n'.join([PAYLOAD_TAG, approval, nonce, service, host, str(int(expires_at))])


def plan_message(payloads_b64):
    return '\n'.join([PLAN_TAG, str(len(payloads_b64))] + sorted(payloads_b64))


class Challenges:
    """One open challenge per service. A challenge is consumed by its first approval attempt, valid or not."""

    def __init__(self, host=None, clock=now_ms):
        self.lock = threading.Lock()
        self.open = {}  # service -> challenge
        self.host = host or host_identity()
        self.clock = clock

    def create(self, service):
        if service not in SERVICES:
            raise Refused('unknown_service', 'Not an allowlisted service.', 404)
        approval, nonce = 'svc-' + secrets.token_hex(16), secrets.token_urlsafe(24)
        expires_at = self.clock() + CHALLENGE_TTL_MS
        payload = canonical_payload(approval, nonce, service, self.host, expires_at)
        challenge = {'id': approval, 'nonce': nonce, 'expires_at': expires_at, 'ttl_ms': CHALLENGE_TTL_MS, 'service': service,
                     'host': self.host, 'payload': payload, 'payload_b64': base64.b64encode(payload.encode()).decode(), 'key_required': True}
        with self.lock:
            self.open[service] = challenge
        return dict(challenge)

    def settle(self, service, body, trust):
        """Returns the consumed challenge if the owner signature verifies; raises Refused otherwise. Never executes."""
        if service not in SERVICES:
            raise Refused('unknown_service', 'Not an allowlisted service.', 404)
        if not isinstance(body, dict):
            raise Refused('bad_request', 'A JSON object is required.', 400)
        approval, nonce, decision = body.get('approval'), body.get('nonce'), body.get('decision')
        if not isinstance(approval, str) or not isinstance(nonce, str) or decision not in ('allow', 'deny'):
            raise Refused('bad_request', 'approval, nonce and decision allow|deny are required.', 400)
        with self.lock:
            challenge = self.open.get(service)
            if not challenge or not hmac.compare_digest(challenge['id'], approval):
                raise Refused('no_challenge', 'No open challenge for this service and approval.', 404)
            # Consumed here, before any check that could fail: a challenge never gets a second attempt.
            del self.open[service]
        if not hmac.compare_digest(challenge['nonce'], nonce):
            raise Refused('nonce_mismatch', 'Nonce does not match the challenge.')
        if self.clock() > challenge['expires_at']:
            raise Refused('expired', 'The challenge expired. Ask again.', 410)
        if decision == 'deny':
            return None
        if trust.state != 'ready':
            raise Refused('owner_trust_' + trust.state, trust.detail, 423)
        key_id, signature = body.get('owner_key_id'), body.get('owner_signature')
        if not isinstance(key_id, str) or key_id not in trust.keys or not isinstance(signature, str):
            raise Refused('owner_key', 'An owner signature from a pinned owner key is required.')
        try:
            signature_der = base64.b64decode(signature, validate=True)
        except Exception:  # noqa: BLE001
            raise Refused('bad_signature', 'Signature is not base64.') from None
        own = challenge['payload_b64']
        plan = body.get('owner_payloads')
        if plan is None:
            message = challenge['payload'].encode()
        else:
            if not isinstance(plan, list) or not 1 <= len(plan) <= MAX_PLAN or len(set(plan)) != len(plan) or own not in plan:
                raise Refused('bad_plan', 'owner_payloads must be 1..16 distinct payloads including this challenge.', 400)
            for item in plan:
                if not isinstance(item, str):
                    raise Refused('bad_plan', 'Plan payloads must be base64 strings.', 400)
                try:
                    decoded = base64.b64decode(item, validate=True)
                except Exception:  # noqa: BLE001
                    raise Refused('bad_plan', 'Plan payloads must be base64.', 400) from None
                if len(decoded) > MAX_PAYLOAD or not decoded.startswith((PAYLOAD_TAG + '\n').encode()):
                    raise Refused('bad_plan', 'Plan entries must be restart challenges of at most 2 KB.', 400)
            message = plan_message(plan).encode()
        if not verify_signature(trust.keys[key_id], message, signature_der):
            raise Refused('bad_signature', 'Owner signature did not verify.')
        return challenge


# ---- work gate ----------------------------------------------------------------------------------------------------
class WorkGate:
    """Admission is closed and every build/walk lock is held from the idle check until verification ends."""

    def __init__(self, lock_dir=LOCK_DIR):
        self.lock_dir = pathlib.Path(lock_dir)
        self.closed = threading.Event()
        self.held = []

    def locks(self):
        return sorted(p for p in self.lock_dir.glob('*.lock')) if self.lock_dir.is_dir() else []

    def lock_state(self):
        state = []
        for path in self.locks():
            held = False
            with contextlib.suppress(OSError), open(path, 'a') as f:
                try:
                    fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    fcntl.flock(f, fcntl.LOCK_UN)
                except BlockingIOError:
                    held = True
            state.append({'name': path.stem, 'held': held})
        return state

    def admit(self):
        return not self.closed.is_set()

    @contextlib.contextmanager
    def exclusive(self, work):
        """work() -> dict(working, waiting, queued, background) or None if unknown. Raises Refused when not provably idle."""
        self.closed.set()
        files = []
        try:
            for path in self.locks():
                f = open(path, 'a')
                try:
                    fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    f.close()
                    raise Refused('busy_lock', f'Build/walk lock {path.stem} is held; restart blocked.', 409) from None
                files.append(f)
            counts = work()
            if counts is None:
                raise Refused('work_unknown', 'Active work cannot be verified; restart blocked.', 409)
            busy = {k: v for k, v in counts.items() if v}
            if busy:
                raise Refused('busy_work', 'Active or queued work must finish first: ' + ', '.join(f'{k} {v}' for k, v in busy.items()), 409)
            self.held = files
            yield
        finally:
            for f in files:
                with contextlib.suppress(OSError):
                    fcntl.flock(f, fcntl.LOCK_UN)
                    f.close()
            self.held = []
            self.closed.clear()


# ---- execution backends -------------------------------------------------------------------------------------------
class Blocked(Exception):
    pass


class LiveBackend:
    """Fixed argv only; never a shell; never a path or command from a request."""
    live = True

    def kickstart_gui(self, service):
        domain, label, mode = SERVICES[service]
        assert domain == 'gui' and mode == 'gui'
        r = subprocess.run([LAUNCHCTL, 'kickstart', '-k', target(service)], capture_output=True, text=True, timeout=30, check=False)
        if r.returncode != 0:
            raise RuntimeError(f'launchctl kickstart exited {r.returncode}: {redact(r.stderr.strip())[:300]}')

    def privileged(self, service):
        helper = PRIVILEGED_HELPER
        try:
            info = os.lstat(helper)
        except OSError:
            raise Blocked('privileged_helper_missing') from None
        if stat.S_ISLNK(info.st_mode) or info.st_uid != 0 or info.st_mode & (stat.S_IWGRP | stat.S_IWOTH):
            raise Blocked('privileged_helper_untrusted')
        r = subprocess.run(['/usr/bin/sudo', '-n', str(helper), service], capture_output=True, text=True, timeout=60, check=False)
        if r.returncode != 0:
            raise Blocked('privileged_helper_refused')

    def exit_for_relaunch(self):
        os._exit(75)


class MockBackend:
    """Tests only. Records calls; never touches launchctl, sudo or the process."""
    live = False

    def __init__(self, privileged_installed=False, fail=False):
        self.calls, self.privileged_installed, self.fail = [], privileged_installed, fail

    def kickstart_gui(self, service):
        self.calls.append(('gui', service))
        if self.fail:
            raise RuntimeError('mock failure')

    def privileged(self, service):
        if not self.privileged_installed:
            raise Blocked('privileged_helper_missing')
        self.calls.append(('privileged', service))

    def exit_for_relaunch(self):
        self.calls.append(('exit', 'runner'))


# ---- probes (read-only) -------------------------------------------------------------------------------------------
def launchd_state(service, runner=subprocess.run):
    r = runner([LAUNCHCTL, 'print', target(service)], capture_output=True, text=True, timeout=10, check=False)
    if r.returncode != 0:
        return {'loaded': False, 'state': 'not_loaded', 'pid': None}
    state = re.search(r'^\s*state = (\S+)', r.stdout, re.M)
    pid = re.search(r'^\s*pid = (\d+)', r.stdout, re.M)
    return {'loaded': True, 'state': state.group(1) if state else 'unknown', 'pid': int(pid.group(1)) if pid else None}


def http_json(url, timeout=4):
    request = urllib.request.Request(url, headers={'Accept': 'application/json'})
    with urllib.request.urlopen(request, timeout=timeout) as reply:  # noqa: S310 -- fixed local/tailnet URLs only
        body = reply.read(262_145)
        if len(body) > 262_144:
            raise ValueError('oversized')
        return reply.status, json.loads(body)


def capability_probe(service, fetch=http_json):
    """Each service's own capability evidence. Unsupported is an honest structured result, never a pass."""
    def check(code, ok, detail):
        return {'code': code, 'state': 'pass' if ok else 'fail', 'detail': detail}
    try:
        if service == 'soma':
            base = os.environ.get('OMA_RUNNER_SOMA_SERVICE', '').rstrip('/')
            if not base:
                return [{'code': 'soma_url', 'state': 'unsupported', 'detail': 'OMA_RUNNER_SOMA_SERVICE is not configured.'}]
            status, data = fetch(base + '/health')
            return [check('soma_identity', status == 200 and data.get('service') == 'soma-serve', 'Soma service identity.')]
        if service == 'syncthing':
            status, data = fetch('http://127.0.0.1:8384/rest/noauth/health')
            return [check('syncthing_health', status == 200 and data.get('status') == 'OK', 'Syncthing REST health.'),
                    {'code': 'syncthing_folders', 'state': 'unsupported', 'detail': 'Folder state needs the Syncthing API key; not read by this probe.'}]
        if service == 'runner':
            return [{'code': 'runner_self', 'state': 'pass', 'detail': 'This runner answered after start.'}]
    except (urllib.error.URLError, OSError, ValueError, json.JSONDecodeError) as e:
        return [{'code': service + '_probe', 'state': 'fail', 'detail': redact(str(e))[:200]}]
    return [{'code': service + '_capability', 'state': 'unsupported',
             'detail': 'No capability probe for this service yet; restart cannot be verified, so it is blocked.'}]


def verifiable(service):
    return service in ('soma', 'syncthing', 'runner')


# ---- durable records ----------------------------------------------------------------------------------------------
def _write_private(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    os.chmod(path.parent, 0o700)
    fd, temp = tempfile.mkstemp(dir=path.parent, prefix='.' + path.name)
    try:
        with os.fdopen(fd, 'w') as f:
            os.fchmod(f.fileno(), 0o600)
            json.dump(redact(data), f, indent=1, sort_keys=True)
            f.flush(); os.fsync(f.fileno())
        os.replace(temp, path)
    except BaseException:
        with contextlib.suppress(OSError):
            os.unlink(temp)
        raise


def _prune(folder):
    files = sorted((p for p in folder.glob('*.json') if p.name != 'newest.json'), key=lambda p: p.stat().st_mtime)
    for old in files[:-KEEP_RECORDS]:
        with contextlib.suppress(OSError):
            old.unlink()


class Records:
    def __init__(self, root=STATE):
        self.root = pathlib.Path(root)
        self.lock = threading.Lock()

    def operation(self, op):
        with self.lock:
            _write_private(self.root / 'operations' / f"{op['id']}.json", op)
            _prune(self.root / 'operations')

    def get_operation(self, op_id):
        if not re.fullmatch(r'op-[0-9a-f]{32}', op_id or ''):
            return None
        path = self.root / 'operations' / f'{op_id}.json'
        try:
            return redact(json.loads(path.read_text()))
        except (OSError, json.JSONDecodeError):
            return None

    def incident(self, incident):
        with self.lock:
            folder = self.root / 'incidents'
            _write_private(folder / f"{incident['id']}.json", incident)
            _write_private(folder / 'newest.json', incident)
            _prune(folder)

    def unfinished(self):
        out = []
        for path in (self.root / 'operations').glob('op-*.json'):
            with contextlib.suppress(OSError, json.JSONDecodeError):
                op = json.loads(path.read_text())
                if op.get('stage') in ('restoring', 'verifying'):
                    out.append(op)
        return out


# ---- the controller -----------------------------------------------------------------------------------------------
class Controller:
    def __init__(self, backend, work, records=None, trust=load_trust, gate=None, probe=capability_probe, state=launchd_state,
                 host=None, clock=now_ms, sleep=time.sleep, spawn=None):
        self.backend, self.work, self.records = backend, work, records or Records()
        self.spawn = spawn or (lambda fn, *args: threading.Thread(target=fn, args=args, name='service-restart', daemon=True).start())
        self.trust, self.gate, self.probe, self.state = trust, gate or WorkGate(), probe, state
        self.challenges = Challenges(host=host, clock=clock)
        self.host, self.clock, self.sleep = self.challenges.host, clock, sleep

    def inventory(self):
        trust = self.trust()
        counts = None
        with contextlib.suppress(Exception):
            counts = self.work()
        services = []
        for service, (domain, label, mode) in SERVICES.items():
            launchd = {'loaded': False, 'state': 'unknown', 'pid': None}
            with contextlib.suppress(Exception):
                launchd = self.state(service)
            blocked = None
            if mode == 'privileged' and not PRIVILEGED_HELPER.exists():
                blocked = 'privileged_helper_missing'
            elif not verifiable(service):
                blocked = 'no_capability_probe'
            services.append({'id': service, 'label': label, 'domain': domain, 'plist': str(plist_path(service)),
                             'installed': plist_path(service).exists(), 'state': launchd['state'], 'pid': launchd['pid'],
                             'restart': {'mode': mode, 'supported': blocked is None and trust.state == 'ready', **({'blocked_reason': blocked} if blocked else {})},
                             'probe': {'checks': self.probe(service)}})
        return redact({'ok': True, 'host': self.host, 'checked_at': self.clock(), 'owner_trust': {'state': trust.state, 'detail': trust.detail},
                       'work': {**(counts or {'unknown': True}), 'locks': self.gate.lock_state(), 'admission': 'open' if self.gate.admit() else 'closed'},
                       'services': services})

    def request(self, service):
        challenge = self.challenges.create(service)
        return challenge  # the caller returns it to the owner's device; it carries the nonce on purpose

    def approve(self, service, body):
        challenge = self.challenges.settle(service, body, self.trust())
        if challenge is None:
            return {'ok': True, 'cancelled': True}
        op = {'id': 'op-' + secrets.token_hex(16), 'approval': challenge['id'], 'service': service, 'host': self.host,
              'stage': 'checking', 'verified': False, 'started_at': self.clock(), 'updated_at': self.clock(), 'checks': []}
        self._save(op)
        self.spawn(self._run, op)
        return {'ok': True, 'operation': {k: op[k] for k in ('id', 'approval', 'service', 'host', 'stage')}}

    def _save(self, op, **changes):
        op.update(changes, updated_at=self.clock())
        self.records.operation(op)

    def _fail(self, op, stage, code, detail):
        op['checks'].append({'code': code, 'state': 'fail', 'detail': detail})
        self._save(op, stage=stage)
        incident = {'id': 'inc-' + secrets.token_hex(8), 'time': self.clock(), 'host': self.host, 'service': op['service'],
                    'operation': op['id'], 'checks': op['checks'], 'root_cause': code,
                    'next_steps': [{'actor': 'owner', 'action': detail}]}
        self.records.incident(incident)
        op['incident'] = incident['id']
        self._save(op)

    def _run(self, op):
        service = op['service']
        _, _, mode = SERVICES[service]
        try:
            if not verifiable(service):
                return self._fail(op, 'blocked', 'no_capability_probe', 'This service has no capability check, so a restart could not be verified. Nothing was restarted.')
            with self.gate.exclusive(self.work):
                op['checks'].append({'code': 'work_idle', 'state': 'pass', 'detail': 'No active, waiting, queued or background work; build/walk locks held.'})
                self._save(op, stage='restoring')
                if mode == 'gui':
                    self.backend.kickstart_gui(service)
                elif mode == 'privileged':
                    self.backend.privileged(service)
                else:
                    self._save(op, stage='verifying')
                    self.backend.exit_for_relaunch()  # launchd relaunches; resume() verifies from the journal
                    return
                self._save(op, stage='verifying')
                self._verify(op)
        except Refused as e:
            self._fail(op, 'blocked', e.code, e.message + ' Nothing was restarted.')
        except Blocked as e:
            self._fail(op, 'blocked', str(e), 'This root service needs the Boss-installed privileged helper. Nothing was restarted.')
        except Exception as e:  # noqa: BLE001
            self._fail(op, 'failed', 'restart_error', redact(str(e))[:300])

    def _verify(self, op, attempts=10):
        service = op['service']
        for _ in range(attempts):
            self.sleep(3)
            checks = []
            with contextlib.suppress(Exception):
                checks = self.probe(service)
            if checks and all(c['state'] == 'pass' for c in checks if c['state'] != 'unsupported') and any(c['state'] == 'pass' for c in checks):
                op['checks'].extend(checks)
                return self._save(op, stage='restored', verified=True)
        op['checks'].extend(checks or [{'code': service + '_probe', 'state': 'fail', 'detail': 'No passing capability check after restart.'}])
        self._fail(op, 'failed', 'not_verified', 'The service restarted but its capability check did not pass. Check it before trying again.')

    def resume(self):
        """On start: finish operations a previous process left mid-restart (the runner's own relaunch, a lost route)."""
        for op in self.records.unfinished():
            self.spawn(self._verify, op)
