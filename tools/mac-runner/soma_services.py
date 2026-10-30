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
import base64, concurrent.futures, contextlib, datetime, fcntl, hashlib, hmac, ipaddress, json, os, pathlib, plistlib, re, secrets, socket, stat, subprocess, sys, tempfile, threading, time
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
PRIVILEGED_HELPER = pathlib.Path('/Library/PrivilegedHelperTools/soma-service-helper')  # install-service-helper.sh, Boss, by hand
SUDO = '/usr/bin/sudo'
HELPER_SERVICES = {'tailshare-serve', 'tailshare-receive', 'herd', 'embeddings', 'whisper'}  # soma-service-helper.swift's table, which says why not tailscaled


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


def _tailscale_json(tailscale, run, timeout):
    """`tailscale status --json` by its real path and with a TERM: the menu-bar app's CLI, reached through a symlink or
    with no TERM (a launchd job has none), prints "The Tailscale GUI failed to start" and exits 0."""
    return run([os.path.realpath(tailscale), 'status', '--json'], capture_output=True, text=True, timeout=timeout, check=False,
               env={**os.environ, 'TERM': os.environ.get('TERM') or 'dumb'})


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
            out = _tailscale_json(tailscale, subprocess.run, 5).stdout
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


CAPABILITY_SCOPES = {'agent_turn.': 'Agents', 'build_toolchain.': 'Builds', 'source_freshness.': 'Sync'}


def mkcheck(check, target, code, result, fields=None, stderr=None):
    """One check in the phone incident schema: {check, target, code, result, fields, stderr_tail}.
    result is pass | fail | auth | unverified (reachable, evidence incomplete) | unsupported (no probe exists) | not_installed
    (nothing to check on this host) | quiet (answering, and no real traffic lately to prove more with; not a problem)."""
    scope = next((name for prefix, name in CAPABILITY_SCOPES.items() if check.startswith(prefix)), None)
    if scope:  # the runner's own scoped rows say which capability they belong to (a JDK problem is Builds, not Agents)
        fields = {'capability': scope, **(fields or {})}
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

    def privileged(self, service, proof, run=subprocess.run):
        # The helper checks the owner's signature itself and takes the proof on stdin only, so it's in no argv or log.
        if not helper_installed() or service not in HELPER_SERVICES:
            raise Blocked('privileged_helper_missing')
        r = run([SUDO, '-n', str(PRIVILEGED_HELPER)], input=json.dumps(proof), capture_output=True, text=True, timeout=30, check=False)
        if r.returncode == 0:
            return
        if r.returncode == 65:
            raise Blocked('privileged_helper_refused:' + stderr_tail(r.stderr, 80).removeprefix('refused: ').strip())
        if 'password' in r.stderr:  # sudo's own refusal: its rule isn't there, and nothing ran
            raise Blocked('privileged_helper_missing')
        raise RuntimeError('the root helper exited %d: %s' % (r.returncode, stderr_tail(r.stderr, 200)))

    def exit_for_relaunch(self):
        os._exit(75)


def helper_installed(lstat=os.lstat):
    """The helper is there and only root could have put it there. Whether sudo lets this user run it shows when it is asked."""
    with contextlib.suppress(OSError):
        info = lstat(PRIVILEGED_HELPER)
        return stat.S_ISREG(info.st_mode) and info.st_uid == 0 and not info.st_mode & (stat.S_IWGRP | stat.S_IWOTH)
    return False


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


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None  # a 3xx comes back as an HTTPError: reachable, never followed (a bearer must not travel elsewhere)


# Every probe goes straight to its fixed address: no redirects followed, and no proxy from the environment.
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect)


def http_json(url, timeout=4, headers=None, limit=262_144, body=None):
    extra = {'Content-Type': 'application/json'} if body is not None else {}
    request = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Accept': 'application/json', **extra, **(headers or {})})
    try:
        reply_cm = _OPENER.open(request, timeout=timeout)  # noqa: S310 -- fixed local/tailnet URLs only
    except urllib.error.HTTPError as e:
        if 300 <= e.code < 400:
            e.close()
            raise Unverified('redirected (%d); not followed' % e.code) from None
        raise
    with reply_cm as reply:
        try:
            body = reply.read(limit + 1)
        except (http.client.IncompleteRead, TimeoutError, ConnectionError):
            raise Unverified('reply cut off before its end') from None
        if len(body) > limit:
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


# ---- how agents are doing, from their real turns: no check of its own, no ping, no quota -----------------------------------
RECENT_PROOF_MS = 24 * 3600_000  # a real delivery this recent is proof; older, the reading says how old and is only quiet
# The usual ways a turn fails because the agent's service does, in the order they are tried. Only a failed turn's words are
# read; anything else (a stopped turn, a tool's failure, a refusal of the request) says nothing about the service.
AGENT_ERRORS = (
    ('sign_in', r'not logged in|/login\b|failed to authenticate|authentication[ _]failed|invalid api key|\b401\b|unauthori[sz]ed|token (has )?expired|codex login'),
    ('usage_limit', r'rate.?limit|usage limit|usage credits|weekly limit|hit your .{0,40}limit|\b429\b|quota|billing'),
    ('overloaded', r'overloaded|\b529\b'),
    ('no_connection', r'connection (lost|error|refused|reset)|econn\w+|network error|fetch failed|stream disconnected|could not resolve|timed out'),
    ('server_error', r'api error|server[ _]error|\b50[0-4]\b|bad gateway|service unavailable'),
)
_agent_trouble = {}  # agent -> (code, the error's own words, when)
_agent_trouble_lock = threading.Lock()


def agent_error(text):
    """Which common API error these words are, or None."""
    if re.search(r'safeguards|invalid_request|prompt is too long', str(text or ''), re.I):
        return None  # the service answering no to this one request
    return next((code for code, words in AGENT_ERRORS if re.search(words, str(text or ''), re.I)), None)


def agent_turn_ended(agent, failed, text='', limited=False, now=None):
    """A real turn of this agent ended. A good one clears its trouble; a failed one with a common API error sets it."""
    with _agent_trouble_lock:
        if not failed:
            _agent_trouble.pop(agent, None)
            return
        code = 'usage_limit' if limited else agent_error(text)
        if code:
            _agent_trouble[agent] = (code, ' '.join(str(text).split())[:160], now or now_ms())


def agent_checks():
    """Only an agent in trouble has a row; nothing heard is nothing wrong."""
    with _agent_trouble_lock:
        trouble = sorted(_agent_trouble.items())
    return [mkcheck('agent_turn.' + agent, agent, code, 'auth' if code == 'sign_in' else 'fail', {'agent': agent, 'error': words, 'at': at})
            for agent, (code, words, at) in trouble]


# ---- the runner's own capabilities: build tools, locks, source (all read-only) ---------------------------------------------
def _run_quiet(run, argv, timeout):
    try:
        return run(argv, capture_output=True, text=True, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return None


SOURCE_MIRROR = HOME / 'Projects/omalauncher-fold'
SYNC_STATUS = HOME / 'agents/luna/phone-source-sync/status.txt'


def toolchain_checks(run=subprocess.run, env=None, home=None, mirror=None):
    """What a build lane needs, each actually run where it can be without building: a JDK 17+ that answers -version, an
    Android SDK whose adb answers `adb version` (which starts no server), and the Gradle wrapper's distribution already
    on disk. Nothing is installed or downloaded."""
    env, home, mirror = env or os.environ, pathlib.Path(home or HOME), pathlib.Path(mirror or SOURCE_MIRROR)
    out = []
    javas = []
    for version in ('17', '21'):
        r = _run_quiet(run, ['/usr/libexec/java_home', '-v', version], 10)
        if r is not None and r.returncode == 0 and r.stdout.strip():
            javas.append(r.stdout.strip() + '/bin/java')
    javas += ['/opt/homebrew/opt/openjdk@17/bin/java', '/usr/local/opt/openjdk@17/bin/java']
    major = None
    for java in javas:
        if os.access(java, os.X_OK):
            r = _run_quiet(run, [java, '-version'], 15)
            found = re.search(r'version "(\d+)', (r.stderr + r.stdout) if r is not None else '')
            if r is not None and r.returncode == 0 and found and int(found.group(1)) >= 17:
                major = int(found.group(1))
                break
    out.append(mkcheck('build_toolchain.jdk', 'runner', 'ok' if major else 'no_usable_jdk', 'pass' if major else 'fail', {'major': major}))
    sdk = None
    local = mirror / 'local.properties'  # what the builds themselves use (sdk.dir), before any environment guess
    configured = re.search(r'^sdk\.dir=(.+)$', local.read_text(), re.M) if local.exists() else None
    for candidate in ((configured.group(1).strip() if configured else None), env.get('ANDROID_HOME'), env.get('ANDROID_SDK_ROOT'),
                      str(home / 'Library/Android/sdk'), '/opt/homebrew/share/android-commandlinetools'):
        if candidate and pathlib.Path(candidate, 'platform-tools/adb').exists():
            sdk = pathlib.Path(candidate)
            break
    if sdk is None:
        out.append(mkcheck('build_toolchain.android_sdk', 'runner', 'no_sdk', 'fail', {}))
    else:
        r = _run_quiet(run, [str(sdk / 'platform-tools/adb'), 'version'], 10)
        build_tools = sorted(p.name for p in (sdk / 'build-tools').glob('*'))[-4:] if (sdk / 'build-tools').is_dir() else []
        platforms = sorted(p.name for p in (sdk / 'platforms').glob('android-*'))[-4:] if (sdk / 'platforms').is_dir() else []
        ok = r is not None and r.returncode == 0 and bool(build_tools) and bool(platforms)
        out.append(mkcheck('build_toolchain.android_sdk', 'runner', 'ok' if ok else 'sdk_incomplete', 'pass' if ok else 'fail',
                           {'adb_answers': r is not None and r.returncode == 0, 'build_tools': build_tools, 'platforms': platforms}))
    props = mirror / 'gradle/wrapper/gradle-wrapper.properties'
    found = re.search(r'distributionUrl=.*/(gradle-[\w.-]+)\.zip', props.read_text()) if props.exists() else None
    if not found:
        out.append(mkcheck('build_toolchain.gradle_wrapper', 'runner', 'no_wrapper', 'unverified', {'mirror': str(mirror)}))
    else:
        name = found.group(1)
        unpacked = name.rsplit('-', 1)[0] if name.endswith(('-bin', '-all')) else name
        present = any(p.exists() for p in (home / '.gradle/wrapper/dists' / name).glob('*/%s/bin/gradle' % unpacked))
        out.append(mkcheck('build_toolchain.gradle_wrapper', 'runner', 'distribution_present' if present else 'distribution_not_downloaded',
                           'pass' if present else 'unverified', {'distribution': name}))
    return out


def build_locks(gate=None):
    state = (gate or WorkGate()).lock_state()
    return mkcheck('build_toolchain.locks', 'runner', 'readable', 'pass', {'locks': len(state), 'held': [x['name'] for x in state if x['held']][:16]})


def source_mirror(run=subprocess.run, mirror=None, status_file=None):
    """The source the lanes build from: the Mac mirror that mac-sync.py fast-forwards to the phone's branch every minute,
    if this Mac has that agent. Evidence: its branch, HEAD against the fetched phone branch, the last fetch time, and the
    sync agent's own status line. No fetch is made here; freshness is reported as it is, never judged by a made-up limit."""
    mirror, status_file = pathlib.Path(mirror or SOURCE_MIRROR), pathlib.Path(status_file or SYNC_STATUS)

    def git(*args):
        r = _run_quiet(run, ['/usr/bin/git', '-C', str(mirror), *args], 10)
        return r.stdout.strip() if r is not None and r.returncode == 0 else None

    if not (mirror / '.git').exists():
        return mkcheck('source_freshness.mirror', 'runner', 'no_mirror', 'unverified', {'mirror': str(mirror), 'next': 'clone the mirror at this path'})
    common = git('rev-parse', '--git-common-dir')
    fetch_head = (mirror / common / 'FETCH_HEAD') if common else None
    fields = {'branch': git('branch', '--show-current'), 'head': git('rev-parse', '--short', 'HEAD'),
              'phone_branch': git('rev-parse', '--short', '--verify', '--quiet', 'refs/remotes/fork/phone/fold-development'),
              'fetched_at': int(fetch_head.stat().st_mtime * 1000) if fetch_head and fetch_head.exists() else None}
    try:
        status = status_file.read_text()[:200].strip()
        fields.update(sync_status=status[:80], sync_status_since=int(status_file.stat().st_mtime * 1000))
    except OSError:
        return mkcheck('source_freshness.mirror', 'runner', 'no_sync_agent', 'not_installed',
                       {**fields, 'next': 'this Mac has no mirror sync agent; builders bring the mirror up to date before each build'})
    if status.startswith('Paused'):
        return mkcheck('source_freshness.mirror', 'runner', 'mirror_sync_paused', 'fail',
                       {**fields, 'next': "mac-sync.py is paused (the mirror left branch fold-sync or has local edits); an agent must not move another session's branch -- the owner decides"})
    synced = status.startswith('Mac Fold source synced') and fields['branch'] == 'fold-sync' and fields['head'] and fields['head'] == fields['phone_branch']
    return mkcheck('source_freshness.mirror', 'runner', 'synced' if synced else 'mirror_behind_or_unknown', 'pass' if synced else 'unverified', fields)


CANVAS_TOKEN_FILE = 'agent-token.txt'


def canvas_boards(fetch, host_port, load=None):
    """A real board load with Canvas's own configured agent token (its data folder, from the installed plist): list the
    boards, then load one board's snapshot. Only counts leave; the token never does."""
    folder = None
    with contextlib.suppress(Exception):
        with open(plist_path('canvas'), 'rb') as f:
            data = (load or plistlib.load)(f)
        env = data.get('EnvironmentVariables') or {}
        if isinstance(env.get('CANVAS_DATA_DIR'), str):
            folder = pathlib.Path(env['CANVAS_DATA_DIR'])
        else:
            args = [str(a) for a in data.get('ProgramArguments') or []]
            command = args[-1] if args else ''
            found = re.search(r'(?:^|[;&|\s])cd\s+([^;&|\s]+)\s*&&', command)
            workdir = data.get('WorkingDirectory') or (found and os.path.expanduser(found.group(1)))
            if workdir:
                folder = pathlib.Path(workdir) / 'data'
    token = _read_secret(folder / CANVAS_TOKEN_FILE) if folder else None
    if not token:
        return mkcheck('canvas_boards', 'canvas', 'no_agent_token', 'unverified', {'missing': 'Canvas agent token (data/agent-token.txt beside its server)'})
    status, data = fetch('http://%s:%d/api/apps' % host_port, headers={'x-canvas-token': token})
    apps = data.get('apps') if isinstance(data.get('apps'), list) else None
    if status != 200 or apps is None:
        return mkcheck('canvas_boards', 'canvas', 'board_list_failed', 'fail', {'http_status': status})
    if not apps:
        return mkcheck('canvas_boards', 'canvas', 'no_boards', 'unverified', {'boards': 0})
    board = apps[0].get('id') if isinstance(apps[0], dict) else None
    if not isinstance(board, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', board):
        return mkcheck('canvas_boards', 'canvas', 'board_list_shape', 'unverified', {'boards': len(apps)})
    status, snapshot = fetch('http://%s:%d/api/boards/%s/snapshot' % (*host_port, board), headers={'x-canvas-token': token}, limit=16_000_000)
    return mkcheck('canvas_boards', 'canvas', 'ok' if status == 200 else 'board_load_failed', 'pass' if status == 200 else 'fail',
                   {'boards': len(apps), 'board_loaded': status == 200, 'http_status': status})


def embed_inference(fetch, host_port, dim):
    """The model really answers: one short fixed text in, a vector of the model's size out. Nothing is stored by the server."""
    try:
        status, data = fetch('http://%s:%d/text' % host_port, timeout=30, limit=1_048_576, body={'texts': ['soma connections check']})
    except (urllib.error.URLError, OSError, Unverified) as e:
        return mkcheck('embed_inference', 'embeddings', 'no_model_answer', 'fail', {}, stderr=str(e))
    vectors = data.get('vectors')
    size = len(vectors[0]) if isinstance(vectors, list) and vectors and isinstance(vectors[0], list) else None
    ok = status == 200 and bool(size) and (not isinstance(dim, int) or size == dim)
    return mkcheck('embed_inference', 'embeddings', 'model_answered' if ok else 'no_model_answer', 'pass' if ok else 'fail', {'http_status': status, 'size': size})


def transport_traversal(fetch, run):
    """The iMessage API's health asks the transport over its socket when it says sendReady (its send path, op health), and it
    sends nothing. A read-only API or one not on this transport can't say, and neither can an API that doesn't answer."""
    token = _read_secret(IMESSAGE_TOKEN)
    if not token:
        return mkcheck('transport_traversal', 'imessage-transport', 'no_token', 'unverified', {'missing': 'the iMessage service token file'})
    try:
        status, data = fetch('http://%s:%d/v1/health' % endpoint('imessage-api', run), headers={'Authorization': 'Bearer ' + token})
    except (urllib.error.URLError, OSError, ValueError, RuntimeError, Unverified) as e:
        return mkcheck('transport_traversal', 'imessage-transport', 'api_unanswered', 'unverified', {'reason': 'the iMessage API, which asks the transport, did not answer'}, stderr=str(e))
    fields = {'http_status': status, 'send_transport': str(data.get('sendTransport', ''))[:24], 'send_problem': str(data.get('sendProblem') or '')[:120] or None}
    if status != 200 or data.get('sendTransport') != 'rustpush' or data.get('readOnly') is True:
        return mkcheck('transport_traversal', 'imessage-transport', 'transport_not_asked', 'unverified', {**fields, 'read_only': data.get('readOnly') is True})
    ok = data.get('sendReady') is True
    return mkcheck('transport_traversal', 'imessage-transport', 'transport_answered' if ok else 'transport_not_ready', 'pass' if ok else 'fail', fields)


SOMA_SYNCED = HOME / '.config/soma/canvas-synced.json'
SOMA_SYNC_PROOF_MS = 10 * 60_000  # Soma exchanges every 30 s while it runs
TAILSHARE_MAIL = HOME / '.local/share/tailshare/mail'
RECEIVE_INBOX = HOME / 'Inbox'


def _newest_file_ms(*folders):
    newest = None
    for folder in folders:
        with contextlib.suppress(OSError):
            with os.scandir(folder) as entries:
                for entry in entries:
                    with contextlib.suppress(OSError):
                        if entry.is_file() and not entry.name.startswith('.'):
                            at = int(entry.stat().st_mtime * 1000)
                            newest = max(newest or 0, at)
    return newest


def soma_sync(now=None):
    """Soma on this Mac keeps the service's copy only after a round the service answered, so the file's time is its last
    real exchange. Nothing is sent. A Soma window not open here is only quiet."""
    now = now or now_ms()
    try:
        at = int(SOMA_SYNCED.stat().st_mtime * 1000)
    except OSError:
        return mkcheck('soma_sync', 'soma', 'no_sync_record', 'quiet', {'missing': "Soma's sync record on this Mac; it is written when a Soma window here syncs"})
    fresh = now - at < SOMA_SYNC_PROOF_MS
    return mkcheck('soma_sync', 'soma', 'synced_recently' if fresh else 'no_recent_sync', 'pass' if fresh else 'quiet', {'last_synced_at': at})


def tailshare_delivery(now=None):
    """A real message this service took in and kept: the newest in its mail folders, read or not. No test message is sent,
    so a quiet night is only quiet, with its age, never a problem."""
    now = now or now_ms()
    at = _newest_file_ms(TAILSHARE_MAIL / 'new', TAILSHARE_MAIL / 'read')
    if not at:
        return mkcheck('tailshare_delivery', 'tailshare-serve', 'no_delivery_record', 'quiet', {'missing': 'any message delivered to this Mac'})
    fresh = now - at < RECENT_PROOF_MS
    return mkcheck('tailshare_delivery', 'tailshare-serve', 'delivered_recently' if fresh else 'no_recent_delivery', 'pass' if fresh else 'quiet', {'last_delivery_at': at})


def receive_freshness(run, state, now=None):
    """The worker's `tailscale file get --wait` is alive and waiting, and a file really landed in the Inbox lately. Waiting alone
    doesn't prove a file would arrive, and no test file is sent, so that much is only quiet, with the last arrival's time."""
    now = now or now_ms()
    pid = state('tailshare-receive').get('pid')
    r = _run_quiet(run, ['/bin/ps', '-ax', '-o', 'ppid=,command='], 5)
    waiting = False
    for line in (r.stdout.splitlines() if r is not None and r.returncode == 0 else []):
        parent, _, command = line.strip().partition(' ')
        waiting = waiting or (bool(pid) and parent == str(pid) and 'file get' in command and '--wait' in command)
    at = _newest_file_ms(RECEIVE_INBOX)
    fields = {'waiting': waiting, 'last_received_at': at}
    if waiting and at and now - at < RECENT_PROOF_MS:
        return mkcheck('receive_freshness', 'tailshare-receive', 'received_recently', 'pass', fields)
    return mkcheck('receive_freshness', 'tailshare-receive', 'waiting_no_recent_file' if waiting else 'not_waiting', 'quiet' if waiting else 'unverified', fields)


def tailscale_status(run=subprocess.run):
    """`tailscale status --json`, parsed; only whitelisted fields ever leave the probe (it also names the owner's login)."""
    for tailscale in TAILSCALE_PATHS:
        if os.path.exists(tailscale):
            r = _tailscale_json(tailscale, run, 8)
            if r.returncode != 0:
                raise RuntimeError('tailscale status exited %d: %s' % (r.returncode, stderr_tail(r.stderr, 200)))
            try:
                data = json.loads(r.stdout[:4_000_000])
            except ValueError:
                raise Unverified('the tailscale CLI answered without its status (%s)' % stderr_tail(r.stdout or r.stderr, 80)) from None
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
    checks = _probe(service, fetch, run, state, keys) + evidence_checks(service, fetch, run, state or launchd_state)
    return checks + [c for c in missing_checks(service) if c['check'] not in {x['check'] for x in checks}]


def evidence_checks(service, fetch, run, state):
    """Evidence beyond the service's own probe. What reads a past delivery or sync (soma, tailshare) can't vouch for a restart
    until the next real one happens, which is why those stay in MISSING_EVIDENCE."""
    if service == 'soma':
        return [soma_sync()]
    if service == 'tailshare-serve':
        return [tailshare_delivery()]
    if service == 'tailshare-receive':
        return [receive_freshness(run, state)]
    if service == 'imessage-transport':
        return [transport_traversal(fetch, run)]
    return []


def _probe(service, fetch=http_json, run=subprocess.run, state=None, keys=None):
    """Each service's own capability evidence, read-only. Bodies never leave the probe: only whitelisted fields do.
    keys: {name: callable -> secret or None} for bearer credentials the caller already holds (the runner's Soma key).
    A reachable service whose evidence is incomplete is unverified; refused auth is unverified; down is fail."""
    keys, state = keys or {}, state or launchd_state

    def check(name, code, ok, fields=None):
        return mkcheck(name, service, code if not ok else 'ok', 'pass' if ok else 'fail', fields or {})

    try:
        if service == 'runner':
            return ([check('runner_self', 'ok', True, {'host': host_identity()})] + agent_checks() + toolchain_checks(run)
                    + [build_locks(), source_mirror(run)])
        if service == 'soma':
            base = os.environ.get('OMA_RUNNER_SOMA_SERVICE', '').rstrip('/')
            if not base:
                return [mkcheck('soma_identity', 'soma', 'not_configured', 'unverified', {'missing': 'OMA_RUNNER_SOMA_SERVICE'})]
            split = urllib.parse.urlsplit(base)
            trusted = split.scheme in ('http', 'https') and not split.username and not split.password and split.hostname is not None
            if trusted and split.hostname not in ('127.0.0.1', '::1', 'localhost'):
                trusted = split.hostname == tailnet_ip(run)  # only this Mac's own Soma, never another host
            if not trusted:
                return [mkcheck('soma_identity', 'soma', 'soma_url_untrusted', 'unverified', {'reason': 'Soma URL is not this Mac (loopback or its own tailnet address)'})]
            key = (keys.get('soma') or (lambda: None))()
            if not key:
                # Whether it is up needs no key: a refusal is its answer. Who it is, each phone checks with its own key.
                try:
                    status, data = fetch(base + '/health')
                except urllib.error.HTTPError as e:
                    if e.code not in (401, 403):
                        raise
                    return [mkcheck('soma_identity', 'soma', 'answers_no_runner_key', 'quiet', {'http_status': e.code})]
                return [check('soma_identity', 'wrong_identity', status == 200 and data.get('service') == 'soma-serve', {'http_status': status})]
            status, data = fetch(base + '/health', headers={'Authorization': 'Bearer ' + key})
            return [check('soma_identity', 'wrong_identity', status == 200 and data.get('service') == 'soma-serve' and data.get('ok') is True,
                          {'http_status': status, 'service': str(data.get('service', ''))[:40]})]
        if service == 'syncthing':
            base = endpoint(service, run)
            status, data = fetch('http://%s:%d/rest/noauth/health' % base)
            return [check('syncthing_health', 'unhealthy', status == 200 and data.get('status') == 'OK', {'http_status': status}),
                    syncthing_folders(fetch, base=base)]
        if service == 'canvas':
            host_port = endpoint(service, run)
            status, data = fetch('http://%s:%d/healthz' % host_port)
            return [check('canvas_health', 'unhealthy', status == 200 and data.get('ok') is True, {'http_status': status}), canvas_boards(fetch, host_port)]
        if service == 'embeddings':
            base = endpoint(service, run)
            status, data = fetch('http://%s:%d/health' % base)
            dim = data.get('dim')
            model = check('embed_model', 'model_missing', status == 200 and data.get('ok') is True and isinstance(dim, int) and dim > 0,
                          {'http_status': status, 'dim': dim if isinstance(dim, int) else None, 'model': str(data.get('model', ''))[:80]})
            return [model, embed_inference(fetch, base, dim)]
        if service == 'whisper':
            status, data = fetch('http://%s:%d/health' % endpoint(service, run))  # whisper.cpp: 200 {"status":"ok"} once the model is loaded
            return [check('whisper_model', 'model_not_loaded', status == 200 and data.get('status') == 'ok', {'http_status': status})]
        if service == 'herd':
            # Herd's reporter builds /v1/status when asked: it reads the session files and the running agents right then. A
            # 200 with a machine and a session list IS a report made now (age 0), not a cached one. Only counts are kept.
            status, data = fetch('http://%s:%d/v1/status?closed=0' % endpoint(service, run))
            me, sessions = data.get('machine'), data.get('sessions')
            ok = status == 200 and isinstance(me, dict) and bool(me.get('herd')) and isinstance(sessions, list)
            return [check('herd_reporter', 'no_report', ok, {'http_status': status, 'reported_now': ok, 'sessions': len(sessions) if isinstance(sessions, list) else None,
                                                              'herd_version': str((me or {}).get('herd', ''))[:16] if isinstance(me, dict) else None})]
        if service == 'tailshare-serve':
            # Admits only the owner's own tailnet devices (tailscale whois); this Mac is one. Only the identity field is kept.
            status, data = fetch('http://%s:%d/v1/hello' % endpoint(service, run))
            return [check('tailshare-serve_identity', 'wrong_identity', status == 200 and bool(data.get('name')), {'http_status': status, 'name': str(data.get('name', ''))[:64]})]
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
            # An API that is up but can't send because of the transport, or because it was set read-only, is not down: restarting
            # it would be restarting a healthy service. transport_traversal reports the transport itself.
            not_its_fault = data.get('ok') is True and (data.get('readOnly') is True or data.get('sendTransport') == 'rustpush')
            return [check('imessage_health', 'not_ready', status == 200 and (data.get('sendReady') is True or not_its_fault) and data.get('ok') is True,
                          {'http_status': status, **fields})]
        if service == 'imessage-transport':
            # A running process with its socket; transport_traversal is what crosses that socket.
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
# A check of the same name from evidence_checks or the service's probe replaces the placeholder in readings.
MISSING_EVIDENCE = {}
# These only prove themselves by real traffic, and none is ever made up: a restart of one (Boss, 2026-10-30) ends "restarted, can't
# confirm yet" once it answers, and is confirmed when real traffic has passed since. service: (its check, that check's time field).
TRAFFIC_PROOF = {
    'soma': ('soma_sync', 'last_synced_at'),
    'tailshare-serve': ('tailshare_delivery', 'last_delivery_at'),
    'tailshare-receive': ('receive_freshness', 'last_received_at'),
}
CONFIRMED_NOTE_MS = 24 * 3600_000  # how long a row keeps saying its restart was confirmed


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

    def _one(self, service, keys=None):
        try:
            launchd = {'loaded': False, 'state': 'unknown', 'pid': None}
            with contextlib.suppress(Exception):
                launchd = self.state(service)
            try:
                checks = self.probe(service, keys=keys) if keys else self.probe(service)
            except Exception as e:  # noqa: BLE001
                checks = [mkcheck(service + '_probe', service, 'probe_error', 'fail', {}, stderr=str(e))]
            with self.lock:
                self.rows[service] = {'checks': checks, 'checked_at': self.clock(), 'launchd': launchd}
        finally:
            with self.lock:
                self.running.pop(service, None)

    def snapshot(self, services, budget=None, keys=None):
        """keys: the asking device's own credentials for this one probe round (held only by its probes, never cached)."""
        with self.lock:
            for service in services:
                if service not in self.running:
                    self.running[service] = self.pool.submit(self._one, service, keys)
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


def healthy_now(service, checks):
    """The service's own checks, if it is up, else nothing. A scoped row (a build tool, an agent's error) is not the service being
    down, and a restart wouldn't change it; the phone's restoreTriage reads them the same way. Quiet is an answer with no
    traffic to show; what is handed back is what the phone accepts for "restored": passes only."""
    own = [c for c in checks if not (c.get('fields') or {}).get('capability') and c.get('result') != 'not_installed']
    if not own or any(c.get('result') not in ('pass', 'quiet') for c in own):
        return []
    return [c for c in own if c.get('result') == 'pass'] or [mkcheck(service + '_answers', service, 'answering', 'pass')]


def answers_after_restart(service, checks):
    """A service that proves itself by traffic is back as far as can be told: everything but that traffic passes (Soma's refusal
    of a keyless asker is an answer), and the receive worker is waiting again."""
    name, _ = TRAFFIC_PROOF[service]
    own = [c for c in checks if not (c.get('fields') or {}).get('capability') and c.get('check') != name]
    traffic = next((c for c in checks if c.get('check') == name), None)
    if service == 'tailshare-receive' and not (traffic and traffic.get('fields', {}).get('waiting')):
        return False
    return bool(own) and all(c.get('result') in ('pass', 'quiet') for c in own)


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
        self.lock = threading.Lock()  # the restart notes

    def inventory(self, budget=None, keys=None):
        trust = self.trust()
        counts = None
        with contextlib.suppress(Exception):
            counts = self.work()
        services = []
        rows = self.probes.snapshot(list(SERVICES), budget, keys)
        notes = self.restart_notes(rows)
        for service in SERVICES:
            domain, label, mode = placement(service)
            row = rows[service]
            launchd = row['launchd']
            blocked = None
            if not installed(service):
                blocked = 'not_installed'
            elif mode == 'privileged' and service not in HELPER_SERVICES:
                blocked = 'never_through_the_helper'
            elif mode == 'privileged' and not helper_installed():
                blocked = 'owner_verified_control_broker_unavailable'
            elif not verifiable(service):
                blocked = 'incomplete_capability_proof' if MISSING_EVIDENCE.get(service) else 'no_capability_probe'
            services.append({'id': service, 'label': label, 'domain': domain, 'plist': str(plist_path(service)),
                             'installed': installed(service), 'optional': service in OPTIONAL, 'loaded': bool(launchd.get('loaded')),
                             'running': launchd.get('state') == 'running' and bool(launchd.get('pid')), 'state': launchd['state'], 'pid': launchd['pid'],
                             'restart': {'mode': mode, 'supported': blocked is None and trust.state == 'ready', **({'blocked_reason': blocked} if blocked else {})},
                             **({'restart_note': notes[service]} if service in notes else {}),
                             'probe': {'checks': row['checks'], 'checked_at': row['checked_at'], 'pending': row['pending']}})
        return redact({'ok': True, 'host': self.host, 'checked_at': self.clock(), 'owner_trust': {'state': trust.state, 'detail': trust.detail},
                       'work': {**(counts or {'unknown': True}), 'locks': self.gate.lock_state(), 'admission': 'open' if self.gate.admit() else 'closed'},
                       'services': services})

    def request(self, service):
        challenge = self.challenges.create(service)
        return challenge  # the caller returns it to the owner's device; it carries the nonce on purpose

    def approve(self, service, body, keys=None):
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
        self.spawn(self._run, op, proof, keys)  # the approving device's own key verifies Soma afterwards; memory only
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

    def _run(self, op, proof=None, keys=None):
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
            # What the owner tapped may have recovered since: a healthy service is left alone.
            now_checks = self._probe_now(service, keys)
            own = healthy_now(service, now_checks)
            if own:
                op['restarted'] = False
                op['outcome'] = 'already_healthy'
                op['checks'].extend(own)
                return self._save(op, stage='restored', verified=True)
            if not any(c.get('result') == 'fail' and not (c.get('fields') or {}).get('capability') for c in now_checks):
                return self._fail(op, 'blocked', 'not_confirmed_down', 'This service could not be shown to be down just now. Nothing was restarted.')
            if mode == 'privileged' and service not in HELPER_SERVICES:
                return self._fail(op, 'blocked', 'never_through_the_helper', 'This one runs as root from a file an agent can change, so it is never restarted from here. Nothing was restarted.')
            with self.gate.exclusive(self.work):
                op['checks'].append(mkcheck('work_idle', 'runner', 'idle', 'pass', {'working': 0, 'waiting': 0, 'queued': 0, 'background': 0, 'locks_held': len(self.gate.held)}))
                self._save(op, stage='restoring')
                if mode == 'gui':
                    self.backend.kickstart_gui(service)
                    self._note_restart(service)
                elif mode == 'privileged':
                    self.backend.privileged(service, proof)
                    self._note_restart(service)
                else:
                    self._save(op, stage='verifying')
                    self.backend.exit_for_relaunch()  # launchd relaunches; resume() verifies from the journal
                    return
                self._save(op, stage='verifying')
                self._verify(op, keys=keys)
        except Refused as e:
            self._fail(op, 'blocked', e.code, e.message + ' Nothing was restarted.')
        except Blocked as e:
            refused = str(e).startswith('privileged_helper_refused')
            self._fail(op, 'blocked', str(e), 'The root helper did not accept the owner proof. Nothing was restarted.' if refused
                       else 'This root service needs the Boss-installed privileged helper. Nothing was restarted.')
        except Exception as e:  # noqa: BLE001
            self._fail(op, 'failed', 'restart_error', redact(str(e))[:300])

    def _note_restart(self, service):
        """Said as soon as it has happened: one slow to come back still isn't vouched for by the traffic from before it."""
        if service not in TRAFFIC_PROOF:
            return
        with self.lock:
            notes = self._restart_times()
            notes[service] = {'at': self.clock()}
            _write_private(self.records.root / 'restart-notes.json', notes)

    def _restart_times(self):
        """When each traffic-proved service was last restarted, and nothing else: an agent can write this file, so nothing in it is
        believed beyond that, and whether traffic has passed since is worked out fresh every time."""
        with contextlib.suppress(OSError, ValueError):
            notes = json.loads((self.records.root / 'restart-notes.json').read_text())
            if isinstance(notes, dict):
                return {service: {'at': note['at']} for service, note in notes.items() if service in TRAFFIC_PROOF and isinstance(note, dict)
                        and type(note.get('at')) is int and note['at'] <= self.clock() + 60_000}
        return {}

    def restart_notes(self, rows):
        """Each traffic-proved service's last restart: unconfirmed until real traffic is newer than it, then confirmed for a day."""
        with self.lock:
            times, out, expired = self._restart_times(), {}, False
            for service, note in list(times.items()):
                name, field = TRAFFIC_PROOF[service]
                check = next((c for c in rows.get(service, {}).get('checks', []) if c.get('check') == name), None)
                last = (check or {}).get('fields', {}).get(field)
                # A stamp from the future (a clock jump, a file's own date) isn't traffic since the restart.
                if type(last) is int and note['at'] < last <= self.clock() + 60_000:
                    if self.clock() - last > CONFIRMED_NOTE_MS:
                        expired = True
                        del times[service]
                        continue
                    out[service] = {'at': note['at'], 'state': 'confirmed', 'confirmed_at': last}
                else:
                    out[service] = {'at': note['at'], 'state': 'unconfirmed'}
            if expired:
                _write_private(self.records.root / 'restart-notes.json', times)
            return out

    def _probe_now(self, service, keys=None):
        with contextlib.suppress(Exception):
            return self.probe(service, keys=keys) if keys else self.probe(service)
        return []

    def _verify(self, op, attempts=10, keys=None):
        service = op['service']
        for _ in range(attempts):
            self.sleep(3)
            checks = []
            with contextlib.suppress(Exception):
                checks = self.probe(service, keys=keys) if keys else self.probe(service)
            if service in TRAFFIC_PROOF and answers_after_restart(service, checks):
                # Not "restored": an old phone reads this stage as a restore that stopped, and a new one reads the outcome.
                op['checks'].extend(checks)
                op['restarted'] = True
                op['outcome'] = 'restarted_unconfirmed'
                return self._save(op, stage='interrupted', verified=False)
            if service not in TRAFFIC_PROOF and checks and all(c['result'] == 'pass' for c in checks):  # unsupported or unverified is never success
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
