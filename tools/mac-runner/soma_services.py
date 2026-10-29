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
import base64, concurrent.futures, contextlib, fcntl, hashlib, hmac, ipaddress, json, os, pathlib, plistlib, re, secrets, socket, stat, subprocess, tempfile, threading, time
import http.client, urllib.error, urllib.parse, urllib.request

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
PRIVILEGED_HELPER = pathlib.Path('/Library/PrivilegedHelperTools/soma-service-helper')  # install-service-helper.sh (Boss, by hand)


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


SYSTEM_PLISTS = pathlib.Path('/Library/LaunchDaemons')
USER_PLISTS = HOME / 'Library/LaunchAgents'


def placement(service):
    """(domain, label, mode) as installed on THIS host: the same label is a root daemon on one Mac and the owner's own
    agent on another (tailshare and herd are system daemons on the Mac Pro, user agents on the MacBook). A system daemon
    needs the root helper; a user agent is restarted as the user. Not installed anywhere: the table's default."""
    default_domain, label, mode = SERVICES[service]
    for domain, folder in (('system', SYSTEM_PLISTS), ('gui', USER_PLISTS)):
        if (folder / f'{label}.plist').exists():
            return domain, label, 'self' if mode == 'self' else ('privileged' if domain == 'system' else 'gui')
    return default_domain, label, mode


def target(service):
    domain, label, _ = placement(service)
    return f'system/{label}' if domain == 'system' else f'gui/{os.getuid()}/{label}'


def plist_path(service):
    domain, label, _ = placement(service)
    return (SYSTEM_PLISTS if domain == 'system' else USER_PLISTS) / f'{label}.plist'


OPTIONAL = {'syncthing'}  # per the approved inventory: absent means Disabled, never an outage


def installed(service):
    """Whether this host has the service at all. A label from another host's inventory that isn't here is not an outage."""
    return service == 'runner' or service not in SERVICES or plist_path(service).exists()


# Where each service listens, from its installed launchd plist. Only these argv flags and environment keys are ever read
# from it; the rest of the plist (other environment values may be secrets) is never kept, returned or logged.
ENDPOINTS = {  # service: (default host: 'tailnet' | 'loopback', default port, port flag, host flag, port env key, host env key)
    'canvas': ('loopback', 5860, None, None, 'CANVAS_PORT', None),
    'embeddings': ('tailnet', 8179, None, None, 'EMBED_PORT', 'EMBED_HOST'),
    'whisper': ('tailnet', 8178, '--port', '--host', None, None),
    'herd': ('tailnet', 8479, None, None, 'HERD_PORT', None),
    'tailshare-serve': ('tailnet', 8480, None, None, None, None),
    'imessage-api': ('tailnet', 8765, '--port', '--host', None, None),
    'syncthing': ('loopback', 8384, None, None, None, None),
}


def plist_fields(service, load=None):
    """The whitelisted endpoint fields of the installed plist, and nothing else."""
    _, _, port_flag, host_flag, port_env, host_env = ENDPOINTS[service]
    try:
        with open(plist_path(service), 'rb') as f:
            data = (load or plistlib.load)(f)
    except Exception:  # noqa: BLE001 -- unreadable: the defaults stand
        return {}
    args = [str(a) for a in data.get('ProgramArguments') or []][:64]
    env = data.get('EnvironmentVariables') or {}
    out = {}
    for flag, key in ((port_flag, 'port'), (host_flag, 'host')):
        if flag and flag in args[:-1]:
            out[key] = args[args.index(flag) + 1]
    for name, key in ((port_env, 'port'), (host_env, 'host')):
        if name and isinstance(env.get(name), str):
            out[key] = env[name]
    return out


def endpoint(service, run=subprocess.run, load=None):
    default_host, default_port, *_ = ENDPOINTS[service]
    fields = plist_fields(service, load)
    port = fields.get('port', '')
    port = int(port) if str(port).isdigit() and 0 < int(port) < 65536 else default_port
    host = None
    with contextlib.suppress(ValueError):
        ip = ipaddress.ip_address(fields.get('host', ''))
        if ip.is_loopback or ip in ipaddress.ip_network('100.64.0.0/10'):  # only this Mac's own addresses
            host = str(ip)
    if host is None:
        host = '127.0.0.1' if default_host == 'loopback' else tailnet_ip(run)
    return host, port


_HOST = None
TAILSCALE_PATHS = ('/opt/homebrew/bin/tailscale', '/usr/local/bin/tailscale', '/Applications/Tailscale.app/Contents/MacOS/Tailscale')


def host_identity():
    """This runner's fixed identity: the first label of its tailnet DNS name, else the short hostname, lowercase."""
    global _HOST
    if _HOST:
        return _HOST
    name = ''
    for tailscale in TAILSCALE_PATHS:
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
_SECRETS = [  # applied in order: whole credential lines, then a header's whole value, then generic key=value
    re.compile(r'(?im)^[^\n]*\b(?:set-cookie|cookie|authorization|proxy-authorization)\b[^\n]*$'),
    re.compile(r'(?i)authorization["\']?\s*[:=]\s*["\']?(?:bearer\s+|basic\s+)?[^\s"\',}]+'),
    re.compile(r'(?i)bearer\s+[A-Za-z0-9._~+/=-]{8,}'),
    re.compile(r'(?:sk-|xox[a-z]-|ghp_|gho_|github_pat_)[A-Za-z0-9_-]{8,}'),
    re.compile(r'(?i)"[a-z_]*(?:token|key|secret|password|nonce|signature|credential)[a-z_]*"\s*:\s*"[^"]*"'),
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


def stderr_tail(text, limit=400):
    """Redacted BEFORE the tail is taken, so a secret split by the cut can never survive half-redacted."""
    return redact(text or '')[-limit:]


def mkcheck(check, target, code, result, fields=None, stderr=None):
    """One check in the phone incident schema: {check, target, code, result, fields, stderr_tail}.
    result is pass | fail | unverified (reachable, evidence incomplete) | unsupported (no probe exists)."""
    return {'check': check, 'target': target, 'code': code, 'result': result, 'fields': redact(fields or {}),
            'stderr_tail': stderr_tail(stderr) if stderr else ''}


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


def _ancestor_problems(path, uids, lstat):
    """Every directory from the file's own up to / must be a real directory (no symlink), owned by a trusted uid and
    writable by nobody else: otherwise whoever can write an ancestor could swap in a directory holding their own keys."""
    problems = []
    for folder in [path.parent, *path.parent.parents]:
        try:
            info = lstat(folder)
        except OSError:
            return [f'{folder} unreadable']
        if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
            problems.append(f'{folder} is a symlink or not a directory')
        elif info.st_uid not in uids:
            problems.append(f'{folder} is not owned by root')
        elif info.st_mode & (stat.S_IWGRP | stat.S_IWOTH):
            problems.append(f'{folder} is group- or world-writable')
    return problems


def load_trust(path=TRUST_FILE, require_root=True, lstat=os.lstat):
    """The pinned owner public keys. Missing -> setup blocked (never trust-on-first-use); anything unsafe -> untrusted.
    require_root=False (tests only) also trusts the current user's own files and folders."""
    path = pathlib.Path(path)
    if not path.is_absolute() or '..' in path.parts:
        return Trust('untrusted', 'Owner trust path must be absolute and plain.')
    uids = {0} if require_root else {0, os.geteuid()}
    try:
        info = lstat(path)
    except FileNotFoundError:
        return Trust('missing', f'Owner trust is not provisioned ({path}). Restore stays blocked until Boss installs it.')
    except OSError as e:
        return Trust('untrusted', f'Owner trust unreadable: {e.strerror}.')
    problems = []
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
        problems.append('not a regular file (symlinks are refused)')
    if info.st_mode & (stat.S_IWGRP | stat.S_IWOTH):
        problems.append('group- or world-writable')
    if info.st_uid not in uids:
        problems.append('not owned by root')
    if info.st_size > 65_536:
        problems.append('larger than 64 KB')
    problems += _ancestor_problems(path, uids, lstat)
    if problems:
        return Trust('untrusted', 'Owner trust refused: ' + '; '.join(problems) + '.')
    try:  # read the very file that was checked: no symlink followed, same inode
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        try:
            opened = os.fstat(fd)
            if (opened.st_dev, opened.st_ino) != (info.st_dev, info.st_ino):
                return Trust('untrusted', 'Owner trust changed while it was being read.')
            raw = os.read(fd, 65_537)
        finally:
            os.close(fd)
    except OSError as e:
        return Trust('untrusted', f'Owner trust unreadable: {e.strerror}.')
    try:
        data = json.loads(raw)
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


ALGORITHM_NAMES = {'ecdsa-p256-sha256': 'ecdsa-p256-sha256', 'SHA256withECDSA-P256': 'ecdsa-p256-sha256'}  # the phone's export names


def normalize_trust(raw):
    """The owner-key file Boss gives the installer, checked and rewritten canonically: every key parses as P-256, its
    key_id is its SPKI digest, the algorithm maps to the one name load_trust accepts, and only known fields survive."""
    data = json.loads(raw)
    if not isinstance(data, dict) or data.get('version') != 1 or not isinstance(data.get('keys'), list) or not 0 < len(data['keys']) <= MAX_KEYS:
        raise ValueError('version 1 with 1 to %d keys expected' % MAX_KEYS)
    out, seen = [], set()
    for entry in data['keys']:
        algorithm = ALGORITHM_NAMES.get(entry.get('algorithm'))
        if not algorithm:
            raise ValueError('unsupported algorithm %r' % str(entry.get('algorithm'))[:40])
        der = base64.b64decode(entry['public_key'], validate=True)
        _spki_key(der)
        key_id = hashlib.sha256(der).hexdigest()
        if not hmac.compare_digest(key_id, str(entry.get('key_id', ''))) or key_id in seen:
            raise ValueError('key_id is not sha256(SPKI DER), or repeats')
        seen.add(key_id)
        out.append({'key_id': key_id, 'algorithm': algorithm, 'public_key': base64.b64encode(der).decode(),
                    'label': str(entry.get('label', ''))[:80], 'device': str(entry.get('device', ''))[:80],
                    'added_at': int(entry.get('added_at') or 0), 'revoked': entry.get('revoked') is True})
    text = json.dumps({'version': 1, 'keys': out}, indent=2) + '\n'
    if len(text) > 65_536:
        raise ValueError('larger than 64 KB')
    return text


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

    def __init__(self, lock_dir=LOCK_DIR, admission=None):
        self.lock_dir = pathlib.Path(lock_dir)
        self.closed = threading.Event()
        self.held = []
        # The lock every work admission holds while it checks admit() and enqueues (oma-runner: `changed`). Closing under
        # it means any admission that saw the gate open has already counted itself before the idle snapshot is taken.
        self.admission = admission if admission is not None else threading.Lock()
        self._owner = threading.Lock()  # one restart at a time; a contender never touches the owner's gate

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
        if not self._owner.acquire(blocking=False):
            raise Refused('restart_in_progress', 'Another owner-approved restart is running.', 409)
        files = []
        try:
            with self.admission:
                self.closed.set()
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
            self._owner.release()


# ---- execution backends -------------------------------------------------------------------------------------------
class Blocked(Exception):
    pass


class LiveBackend:
    """Fixed argv only; never a shell; never a path or command from a request."""
    live = True

    def kickstart_gui(self, service):
        domain, label, mode = placement(service)
        assert domain == 'gui' and mode == 'gui'
        r = subprocess.run([LAUNCHCTL, 'kickstart', '-k', target(service)], capture_output=True, text=True, timeout=30, check=False)
        if r.returncode != 0:
            raise RuntimeError(f'launchctl kickstart exited {r.returncode}: {stderr_tail(r.stderr, 300)}')

    def privileged(self, service, proof):
        # Independent owner proof is implemented in Swift; trusted work/admission caller enforcement is not ready.
        raise Blocked('owner_verified_control_broker_unavailable')

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

    def privileged(self, service, proof):
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
    # Only these whitelisted fields are parsed; launchctl's full output (environment variables!) is never kept.
    state = re.search(r'^\s*state = (\S+)', r.stdout, re.M)
    pid = re.search(r'^\s*pid = (\d+)', r.stdout, re.M)
    exit_code = re.search(r'^\s*last exit code = (-?\d+)', r.stdout, re.M)
    return {'loaded': True, 'state': state.group(1) if state else 'unknown', 'pid': int(pid.group(1)) if pid else None,
            'last_exit': int(exit_code.group(1)) if exit_code else None}


class Unverified(Exception):
    """Reachable (a reply arrived) but its evidence is incomplete: never a pass, never network-down."""


def http_json(url, timeout=4, headers=None):
    request = urllib.request.Request(url, headers={'Accept': 'application/json', **(headers or {})})
    with urllib.request.urlopen(request, timeout=timeout) as reply:  # noqa: S310 -- fixed local/tailnet URLs only
        try:
            body = reply.read(262_145)
        except (http.client.IncompleteRead, TimeoutError, ConnectionError):
            raise Unverified('reply cut off before its end') from None
        if len(body) > 262_144:
            raise Unverified('oversized reply')
        try:
            data = json.loads(body)
        except (json.JSONDecodeError, UnicodeDecodeError):
            raise Unverified('incomplete or non-JSON reply') from None
        if not isinstance(data, dict):
            raise Unverified('reply is not a JSON object')
        return reply.status, data


SYNCTHING_CONFIG = pathlib.Path.home() / 'Library/Application Support/Syncthing/config.xml'
SYNCTHING_FOLDER_OK = {'idle', 'scanning', 'scan-waiting', 'syncing', 'sync-waiting', 'sync-preparing', 'cleaning', 'clean-waiting'}


def syncthing_folders(fetch=http_json, config=SYNCTHING_CONFIG, base=('127.0.0.1', 8384)):
    """Each unpaused folder's state from Syncthing's own API. Its API key is read from the owner's 0600 config into memory
    for the request header only; it never enters a check, a record or an error."""
    import xml.etree.ElementTree as ET
    try:
        root = ET.fromstring(pathlib.Path(config).read_bytes()[:1_048_576])
    except (OSError, ET.ParseError):
        return mkcheck('syncthing_folders', 'syncthing', 'config_unreadable', 'unverified', {'reason': 'Syncthing config could not be read'})
    key = (root.findtext('gui/apikey') or '').strip()
    if not key:
        return mkcheck('syncthing_folders', 'syncthing', 'no_api_key', 'unverified', {'reason': 'Syncthing has no API key set'})
    folders = [f.get('id') for f in root.findall('folder') if f.get('paused', 'false') != 'true' and f.get('id')][:32]
    states, errors = {}, 0
    for folder in folders:
        status, data = fetch('http://%s:%d/rest/db/status?folder=' % base + urllib.parse.quote(folder, safe=''), headers={'X-API-Key': key})
        if status != 200:
            return mkcheck('syncthing_folders', 'syncthing', 'api_refused', 'fail', {'http_status': status})
        states[folder] = str(data.get('state', 'unknown'))[:24]
        errors += int(data.get('errors') or 0) + int(data.get('pullErrors') or 0)
    bad = {f: st for f, st in states.items() if st not in SYNCTHING_FOLDER_OK}
    ok = folders and not bad and not errors
    return mkcheck('syncthing_folders', 'syncthing', 'ok' if ok else ('no_folders' if not folders else 'folder_problem'),
                   'pass' if ok else 'fail', {'folders': len(folders), 'states': states, 'errors': errors})


def tailscale_status(run=subprocess.run):
    """`tailscale status --json`, parsed; only whitelisted fields ever leave the probe (it also names the owner's login)."""
    for tailscale in TAILSCALE_PATHS:
        if os.path.exists(tailscale):
            r = run([tailscale, 'status', '--json'], capture_output=True, text=True, timeout=8, check=False)
            if r.returncode != 0:
                raise RuntimeError('tailscale status exited %d: %s' % (r.returncode, stderr_tail(r.stderr, 200)))
            data = json.loads(r.stdout[:4_000_000])
            if not isinstance(data, dict):
                raise Unverified('tailscale status is not a JSON object')
            return data
    raise FileNotFoundError('tailscale CLI not found')


def tailnet_ip(run=subprocess.run):
    ips = (tailscale_status(run).get('Self') or {}).get('TailscaleIPs') or []
    four = [ip for ip in ips if re.fullmatch(r'100\.\d+\.\d+\.\d+', str(ip))]
    if not four:
        raise Unverified('this Mac has no tailnet IPv4 address')
    return four[0]


IMESSAGE_TOKEN = pathlib.Path.home() / 'Library/Application Support/soma-imessage/token'
TRANSPORT_SOCKET = pathlib.Path.home() / 'Library/Application Support/soma-imessage/rustpush/bridge.sock'


def _read_secret(path):
    """A local credential, for one request header only: never returned, logged or recorded."""
    with contextlib.suppress(OSError):
        return pathlib.Path(path).read_text()[:4096].strip() or None
    return None


def _running(service, state):
    st = state(service)
    ok = st.get('state') == 'running' and bool(st.get('pid'))
    return mkcheck('launchd_running', service, 'running' if ok else 'not_running', 'pass' if ok else 'fail',
                   {k: st.get(k) for k in ('state', 'pid', 'last_exit')})


def capability_probe(service, fetch=http_json, run=subprocess.run, state=None, keys=None):
    """What the service can show, plus a REQUIRED unverified check for every piece of end-to-end evidence it can't yet.
    A service this host doesn't have gets one neutral not_installed result and is never probed: absence is not an outage."""
    if not installed(service):
        return [mkcheck('installed', service, 'disabled' if service in OPTIONAL else 'not_installed', 'not_installed', {'optional': service in OPTIONAL})]
    return _probe(service, fetch, run, state, keys) + missing_checks(service)


def _probe(service, fetch=http_json, run=subprocess.run, state=None, keys=None):
    """Each service's own capability evidence, read-only. Bodies never leave the probe: only whitelisted fields do.
    keys: {name: callable -> secret or None} for bearer credentials the caller already holds (the runner's Soma key).
    A reachable service whose evidence is incomplete is unverified; refused auth is unverified; down is fail."""
    keys, state = keys or {}, state or launchd_state

    def check(name, code, ok, fields=None):
        return mkcheck(name, service, code if not ok else 'ok', 'pass' if ok else 'fail', fields or {})

    try:
        if service == 'runner':
            return [check('runner_self', 'ok', True, {'host': host_identity()})]
        if service == 'soma':
            base = os.environ.get('OMA_RUNNER_SOMA_SERVICE', '').rstrip('/')
            if not base:
                return [mkcheck('soma_identity', 'soma', 'not_configured', 'unverified', {'missing': 'OMA_RUNNER_SOMA_SERVICE'})]
            key = (keys.get('soma') or (lambda: None))()
            if not key:
                return [mkcheck('soma_identity', 'soma', 'no_runner_key', 'unverified', {'missing': "the runner's Soma device key"})]
            status, data = fetch(base + '/health', headers={'Authorization': 'Bearer ' + key})
            return [check('soma_identity', 'wrong_identity', status == 200 and data.get('service') == 'soma-serve' and data.get('ok') is True,
                          {'http_status': status, 'service': str(data.get('service', ''))[:40]})]
        if service == 'syncthing':
            base = endpoint(service, run)
            status, data = fetch('http://%s:%d/rest/noauth/health' % base)
            return [check('syncthing_health', 'unhealthy', status == 200 and data.get('status') == 'OK', {'http_status': status}),
                    syncthing_folders(fetch, base=base)]
        if service == 'canvas':
            status, data = fetch('http://%s:%d/healthz' % endpoint(service, run))
            return [check('canvas_health', 'unhealthy', status == 200 and data.get('ok') is True, {'http_status': status})]
        if service == 'embeddings':
            status, data = fetch('http://%s:%d/health' % endpoint(service, run))
            dim = data.get('dim')
            return [check('embed_model', 'model_missing', status == 200 and data.get('ok') is True and isinstance(dim, int) and dim > 0,
                          {'http_status': status, 'dim': dim if isinstance(dim, int) else None, 'model': str(data.get('model', ''))[:80]})]
        if service == 'whisper':
            status, data = fetch('http://%s:%d/health' % endpoint(service, run))  # whisper.cpp: 200 {"status":"ok"} once the model is loaded
            return [check('whisper_model', 'model_not_loaded', status == 200 and data.get('status') == 'ok', {'http_status': status})]
        if service in ('herd', 'tailshare-serve'):
            # Both admit only the owner's own tailnet devices (tailscale whois); this Mac is one. Only the identity field is kept.
            path, field = ('/v1/status?closed=0', 'machine') if service == 'herd' else ('/v1/hello', 'name')
            status, data = fetch('http://%s:%d%s' % (*endpoint(service, run), path))
            return [check(service + '_identity', 'wrong_identity', status == 200 and bool(data.get(field)), {'http_status': status, field: str(data.get(field, ''))[:64]})]
        if service == 'tailscaled':
            data = tailscale_status(run)
            me = data.get('Self') or {}
            peers = data.get('Peer') or {}
            ok = data.get('BackendState') == 'Running' and me.get('Online') is not False and bool(me.get('TailscaleIPs'))
            return [check('tailnet_backend', 'not_running', ok, {'backend_state': str(data.get('BackendState', ''))[:24],
                                                               'peers_online': sum(1 for p in peers.values() if isinstance(p, dict) and p.get('Online'))})]
        if service == 'imessage-api':
            token = _read_secret(IMESSAGE_TOKEN)
            if not token:
                return [mkcheck('imessage_health', service, 'no_token', 'unverified', {'missing': 'the iMessage service token file'})]
            status, data = fetch('http://%s:%d/v1/health' % endpoint(service, run), headers={'Authorization': 'Bearer ' + token})
            fields = {k: data.get(k) for k in ('ok', 'sendReady', 'messagesAccess', 'watching', 'readOnly') if isinstance(data.get(k), (bool, str))}
            return [check('imessage_health', 'not_ready', status == 200 and data.get('ok') is True and data.get('sendReady') is True,
                          {'http_status': status, **fields})]
        if service == 'imessage-transport':
            # Its health op is internal to the API process; a running process with its socket is the evidence here.
            sock = TRANSPORT_SOCKET
            is_socket = sock.exists() and stat.S_ISSOCK(os.stat(sock).st_mode)
            return [_running(service, state), check('transport_socket', 'socket_missing', is_socket, {})]
        if service == 'tailshare-receive':
            # A loop around `tailscale file get --wait`: it serves nothing, so the evidence is the process being up.
            return [_running(service, state)]
    except Unverified as e:
        return [mkcheck(service + '_probe', service, 'reachable_unverified', 'unverified', {'reason': str(e)})]
    except urllib.error.HTTPError as e:
        code = 'auth_refused' if e.code in (401, 403) else 'http_error'
        return [mkcheck(service + '_probe', service, code, 'unverified' if code == 'auth_refused' else 'fail', {'http_status': e.code})]
    except (urllib.error.URLError, OSError, RuntimeError, json.JSONDecodeError) as e:
        return [mkcheck(service + '_probe', service, 'unreachable', 'fail', {}, stderr=str(e))]
    return [mkcheck(service + '_capability', service, 'no_capability_probe', 'unsupported',
                    {'reason': 'No capability probe for this service; its restart cannot be verified, so it is blocked.'})]


# End-to-end evidence a service's restart needs beyond what its probe can show today. Each becomes a REQUIRED unverified
# check in every probe, so identity, health or process evidence alone never reads as Ready or Restored, and a restart is
# refused before it starts (nothing is restarted that could only be half-verified). Remove an entry only with a real probe.
MISSING_EVIDENCE = {
    'runner': [('provider_auth', 'agent provider sign-in validity, without reading token bodies'),
               ('build_toolchain', 'JDK, Android SDK/adb and Gradle wrapper usable by the build lanes'),
               ('source_freshness', 'age of the source mirrors the lanes build from')],
    'soma': [('soma_sync', 'a Soma sync cursor or ack; /health shows identity and public-URL liveness only')],
    'canvas': [('canvas_boards', 'a read-only board list/load with the runner\'s canvas key; /healthz shows the process only')],
    'tailshare-serve': [('tailshare_delivery', 'a delivery ack; /v1/hello shows identity only')],
    'tailshare-receive': [('receive_freshness', 'a worker heartbeat or last-delivery ack; the receive loop has no endpoint')],
    'herd': [('herd_reporter_age', "the reporter's last-report age; /v1/status shows identity and sessions only")],
    'embeddings': [('embed_inference', 'a model answer; /health checks the model file exists, not that it loads')],
    'imessage-transport': [('transport_traversal', 'an API health that crosses the transport socket; process and socket only today')],
}


def missing_checks(service):
    return [mkcheck(name, service, 'evidence_missing', 'unverified', {'required': True, 'missing': what})
            for name, what in MISSING_EVIDENCE.get(service, [])]


PROBED = {'runner', 'soma', 'syncthing', 'canvas', 'embeddings', 'whisper', 'herd', 'tailshare-serve', 'tailscaled',
          'imessage-api', 'imessage-transport', 'tailshare-receive'}


def verifiable(service):
    """A restart may start only if every piece of its required evidence can be checked afterwards."""
    return service in PROBED and not MISSING_EVIDENCE.get(service)


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


def operation_id(approval):
    """Bound to the approval, so a phone whose reply was lost can find the operation without ever replaying it."""
    return 'op-' + hashlib.sha256(('soma-service-operation-v1\n' + approval).encode()).hexdigest()[:32]


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

    def operation_for_approval(self, approval):
        if not re.fullmatch(r'svc-[0-9a-f]{32}', approval or ''):
            return None
        op = self.get_operation(operation_id(approval))
        return op if op and op.get('approval') == approval else None

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
                if op.get('stage') in ('checking', 'restoring', 'verifying'):
                    out.append(op)
        return out


INVENTORY_BUDGET_S = 2.5  # under the phone's 3.5 s read timeout, with room for the reply


class ProbeCache:
    """Inventory never waits on a slow dependency: every service is probed in parallel (one probe in flight per service), the
    reply waits at most its budget, and a row not back in time shows its last result with that result's own checked_at, or
    pending/unverified with none. A probe outliving the budget keeps running and fills the cache; it is never cut to failed."""

    def __init__(self, probe, state, clock, workers=6):
        self.probe, self.state, self.clock = probe, state, clock
        self.lock = threading.Lock()
        self.rows, self.running = {}, {}  # service -> its in-flight future
        self.pool = concurrent.futures.ThreadPoolExecutor(max_workers=workers, thread_name_prefix='service-probe')

    def _one(self, service):
        try:
            launchd = {'loaded': False, 'state': 'unknown', 'pid': None}
            with contextlib.suppress(Exception):
                launchd = self.state(service)
            try:
                checks = self.probe(service)
            except Exception as e:  # noqa: BLE001
                checks = [mkcheck(service + '_probe', service, 'probe_error', 'fail', {}, stderr=str(e))]
            with self.lock:
                self.rows[service] = {'checks': checks, 'checked_at': self.clock(), 'launchd': launchd}
        finally:
            with self.lock:
                self.running.pop(service, None)

    def snapshot(self, services, budget=None):
        with self.lock:
            for service in services:
                if service not in self.running:
                    self.running[service] = self.pool.submit(self._one, service)
            futures = [self.running[s] for s in services if s in self.running]  # new and already in flight alike
        concurrent.futures.wait(futures, timeout=INVENTORY_BUDGET_S if budget is None else budget)
        with self.lock:
            out = {}
            for service in services:
                row = self.rows.get(service)
                pending = service in self.running
                if row is None:
                    out[service] = {'checks': [mkcheck(service + '_probe', service, 'probe_pending', 'unverified', {})], 'checked_at': None,
                                    'pending': True, 'launchd': {'loaded': False, 'state': 'unknown', 'pid': None}}
                else:
                    out[service] = {**row, 'pending': pending}
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
        self.probes = ProbeCache(probe, state, clock)

    def inventory(self, budget=None):
        trust = self.trust()
        counts = None
        with contextlib.suppress(Exception):
            counts = self.work()
        services = []
        rows = self.probes.snapshot(list(SERVICES), budget)
        for service in SERVICES:
            domain, label, mode = placement(service)
            row = rows[service]
            launchd = row['launchd']
            blocked = None
            if not installed(service):
                blocked = 'not_installed'
            elif mode == 'privileged':
                blocked = 'owner_verified_control_broker_unavailable'
            elif not verifiable(service):
                blocked = 'incomplete_capability_proof' if MISSING_EVIDENCE.get(service) else 'no_capability_probe'
            services.append({'id': service, 'label': label, 'domain': domain, 'plist': str(plist_path(service)),
                             'installed': installed(service), 'optional': service in OPTIONAL, 'loaded': bool(launchd.get('loaded')),
                             'running': launchd.get('state') == 'running' and bool(launchd.get('pid')), 'state': launchd['state'], 'pid': launchd['pid'],
                             'restart': {'mode': mode, 'supported': blocked is None and trust.state == 'ready', **({'blocked_reason': blocked} if blocked else {})},
                             'probe': {'checks': row['checks'], 'checked_at': row['checked_at'], 'pending': row['pending']}})
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
        op = {'id': operation_id(challenge['id']), 'approval': challenge['id'], 'service': service, 'host': self.host,
              'stage': 'checking', 'verified': False, 'started_at': self.clock(), 'updated_at': self.clock(), 'checks': []}
        self._save(op)
        # The owner's proof, for the root helper only: held in memory for this one run, never written to a record.
        proof = {'approval': challenge['id'], 'nonce': challenge['nonce'], 'service': service, 'host': challenge['host'],
                 'expires_at': challenge['expires_at'], 'owner_key_id': body['owner_key_id'], 'owner_signature': body['owner_signature'],
                 **({'owner_payloads': body['owner_payloads']} if body.get('owner_payloads') is not None else {})}
        self.spawn(self._run, op, proof)
        return {'ok': True, 'operation': {k: op[k] for k in ('id', 'approval', 'service', 'host', 'stage')}}

    def _save(self, op, **changes):
        op.update(changes, updated_at=self.clock())
        self.records.operation(op)

    def _fail(self, op, stage, code, detail, actor='owner'):
        op['checks'].append(mkcheck('restart', op['service'], code, 'fail', {'stage': stage}))
        self._save(op, stage=stage)
        checks = op['checks'][-24:]
        incident = {'schema': 1, 'id': 'inc-' + secrets.token_hex(8), 'time': self.clock(), 'last_seen': self.clock(), 'occurrences': 1,
                    'service': op['service'], 'host': self.host, 'stage': stage, 'summary': detail, 'root_cause': code,
                    'operation': op['id'], 'checks': checks, 'checks_truncated': len(op['checks']) > len(checks),
                    'next_steps': [{'actor': actor, 'action': detail, 'reason': code}]}
        self.records.incident(incident)
        op['incident'] = incident['id']
        self._save(op)

    def _run(self, op, proof=None):
        service = op['service']
        _, _, mode = placement(service)
        try:
            if not installed(service):
                return self._fail(op, 'blocked', 'not_installed', 'This service is not installed on this host. Nothing was restarted.')
            if not verifiable(service):
                missing = [what for _, what in MISSING_EVIDENCE.get(service, [])]
                if missing:
                    op['checks'].extend(missing_checks(service))
                    return self._fail(op, 'blocked', 'incomplete_capability_proof', 'A restart could not be verified end to end (missing: '
                                      + '; '.join(missing) + '). Nothing was restarted.')
                return self._fail(op, 'blocked', 'no_capability_probe', 'This service has no capability check, so a restart could not be verified. Nothing was restarted.')
            with self.gate.exclusive(self.work):
                op['checks'].append(mkcheck('work_idle', 'runner', 'idle', 'pass', {'working': 0, 'waiting': 0, 'queued': 0, 'background': 0, 'locks_held': len(self.gate.held)}))
                self._save(op, stage='restoring')
                if mode == 'gui':
                    self.backend.kickstart_gui(service)
                elif mode == 'privileged':
                    self.backend.privileged(service, proof)
                else:
                    self._save(op, stage='verifying')
                    self.backend.exit_for_relaunch()  # launchd relaunches; resume() verifies from the journal
                    return
                self._save(op, stage='verifying')
                self._verify(op)
        except Refused as e:
            self._fail(op, 'blocked', e.code, e.message + ' Nothing was restarted.')
        except Blocked as e:
            refused = str(e).startswith('privileged_helper_refused')
            self._fail(op, 'blocked', str(e), 'The root helper did not accept the owner proof. Nothing was restarted.' if refused
                       else 'This root service needs the Boss-installed privileged helper. Nothing was restarted.')
        except Exception as e:  # noqa: BLE001
            self._fail(op, 'failed', 'restart_error', redact(str(e))[:300])

    def _verify(self, op, attempts=10):
        service = op['service']
        for _ in range(attempts):
            self.sleep(3)
            checks = []
            with contextlib.suppress(Exception):
                checks = self.probe(service)
            if checks and all(c['result'] == 'pass' for c in checks):  # unsupported or unverified is never success
                op['checks'].extend(checks)
                return self._save(op, stage='restored', verified=True)
        op['checks'].extend(checks or [mkcheck(service + '_probe', service, 'no_result', 'fail')])
        self._fail(op, 'failed', 'not_verified', 'The service restarted but its capability check did not pass. Check it before trying again.')

    def resume(self):
        """On start: finish operations a previous process left unfinished, by checking only -- never by restarting.
        Left at 'checking', the restart never started: it ends 'interrupted' with today's evidence (verified only if every
        check passes), so the phone's pending marker always resolves and nothing is retried. Left at 'restoring' or
        'verifying' (the runner's own relaunch, a crash mid-restart), the restart may have run: it is verified as usual."""
        for op in self.records.unfinished():
            self.spawn(self._interrupted if op.get('stage') == 'checking' else self._verify, op)

    def _interrupted(self, op):
        checks = []
        with contextlib.suppress(Exception):
            checks = self.probe(op['service'])
        op['checks'].extend(checks or [mkcheck(op['service'] + '_probe', op['service'], 'no_result', 'fail')])
        op['restarted'] = False
        ok = bool(checks) and all(c['result'] == 'pass' for c in checks)
        self._save(op, stage='interrupted', verified=ok)
        if not ok:
            self._fail(op, 'interrupted', 'interrupted_before_restart',
                       'The runner stopped before this restart began, and the service does not pass its checks now. Nothing was restarted; ask again.')


if __name__ == '__main__':  # install-service-helper.sh runs this as the invoking user, never as root
    import sys
    if len(sys.argv) == 3 and sys.argv[1] == 'normalize-trust':
        try:
            sys.stdout.write(normalize_trust(pathlib.Path(sys.argv[2]).read_bytes()[:65_537]))
        except Exception as e:  # noqa: BLE001
            sys.exit('owner keys refused: %s' % str(e)[:200])
    else:
        sys.exit('usage: soma_services.py normalize-trust KEYS.json')
