#!/usr/bin/env python3
"""oma-keep: keeps a guest command running after the agent turn that started it has ended.

An agent's turn runs in a proot of its own that ends everything started in it when the turn ends, nohup and
setsid or not. The keeper runs in the launcher's long-lived Linux instead (Runtime starts `oma-keep serve`, as
it starts oma-door) and starts commands there when asked over a unix socket that only root in the guest can
open. What it keeps ends with it: when Linux stops or the launcher restarts, everything kept stops too.

  oma-keep run NAME [--] COMMAND [ARG...]   starts COMMAND (exactly these words, no shell) in this folder
  oma-keep list                             name, pid, state, uptime and command of each kept process
  oma-keep log NAME [LINES]                 the last LINES lines of its output (100)
  oma-keep stop NAME                        TERM to its process group, KILL 5 s later if still there
"""
import json, os, re, shlex, signal, socket, subprocess, sys, threading, time

DIR = os.environ.get('OMA_KEEP_DIR', '/root/.cache/oma-keep')
SOCKET = os.path.join(DIR, 'keep.sock')
NAME = re.compile(r'[a-z0-9-]{1,32}')
MAX_KEPT = 8
LOG_CAP = 1 << 20
STOP_GRACE = 5.0
MAX_REQUEST = 256 * 1024
LOG_LINES = 100

USAGE = """usage: oma-keep run NAME [--] COMMAND [ARG...]
       oma-keep list
       oma-keep log NAME [LINES]
       oma-keep stop NAME

Keeps COMMAND running after the agent turn or terminal that started it ends,
until it exits, `oma-keep stop NAME`, or Linux stops. COMMAND runs exactly as
given in the current folder, without a shell: write `bash -c '...'` for one.
NAME is 1 to 32 of a-z, 0-9 and -. At most 8 are kept at a time. Output goes
to ~/.cache/oma-keep/NAME.log, of which the last 1 MB is kept."""


class Usage(Exception):
    pass


class Refused(Exception):
    pass


def valid_name(name):
    return isinstance(name, str) and NAME.fullmatch(name) is not None


def parse(args):
    """The request a command line asks for, as sent to the keeper; `log` and `serve` are done by this process."""
    if not args:
        raise Usage(USAGE)
    op, rest = args[0], args[1:]
    if op == 'run':
        if not rest:
            raise Usage(USAGE)
        name, argv = rest[0], rest[1:]
        if argv[:1] == ['--']:
            argv = argv[1:]
        if not argv:
            raise Usage('oma-keep run: no command given')
        check_name(name)
        return {'op': 'run', 'name': name, 'argv': argv}
    if op == 'list' and not rest:
        return {'op': 'list'}
    if op == 'stop' and len(rest) == 1:
        check_name(rest[0])
        return {'op': 'stop', 'name': rest[0]}
    if op == 'log' and len(rest) in (1, 2):
        check_name(rest[0])
        lines = LOG_LINES
        if len(rest) == 2:
            if not rest[1].isdigit() or int(rest[1]) < 1:
                raise Usage('oma-keep log: LINES is a whole number above 0')
            lines = int(rest[1])
        return {'op': 'log', 'name': rest[0], 'lines': lines}
    if op == 'serve' and not rest:
        return {'op': 'serve'}
    raise Usage(USAGE)


def check_name(name):
    if not valid_name(name):
        raise Usage('oma-keep: a name is 1 to 32 of a-z, 0-9 and -, not %r' % name)


def check_argv(argv):
    """Words a run may be asked for: plain strings, no NUL, at least one."""
    if not isinstance(argv, list) or not argv or not all(isinstance(word, str) and '\0' not in word for word in argv):
        raise Refused('the command must be a list of words')
    if not argv[0]:
        raise Refused('the command is empty')


def to_forget(kept, name):
    """Which entry a run of [name] replaces or pushes out, or None. [kept] maps names to (running, started).

    A running entry is never pushed out: with MAX_KEPT running, the run is refused. An exited one is, oldest first,
    so that `list` still shows what ended until its room is needed.
    """
    if name in kept:
        if kept[name][0]:
            raise Refused('%s is already running; stop it first' % name)
        return name
    if len(kept) < MAX_KEPT:
        return None
    exited = [(started, entry) for entry, (running, started) in kept.items() if not running]
    if not exited:
        raise Refused('%d processes are kept already; stop one first' % MAX_KEPT)
    return min(exited)[1]


def trimmed(data, cap=LOG_CAP):
    """A log that grew past [cap] cut to its last half cap, from the start of a line where there is one."""
    if len(data) <= cap:
        return data
    tail = data[-(cap // 2):]
    cut = tail.find(b'\n')
    return tail[cut + 1:] if 0 <= cut < len(tail) - 1 else tail


def stale_logs(logs, kept_names, keep=MAX_KEPT):
    """Logs to delete: of [logs] ({name: mtime}), those of names no longer kept beyond the [keep] newest."""
    gone = sorted((mtime, name) for name, mtime in logs.items() if name not in kept_names)
    return [name for _, name in gone[:max(0, len(gone) - keep)]]


def uptime(seconds):
    seconds = int(max(0, seconds))
    if seconds < 60:
        return '%ds' % seconds
    if seconds < 3600:
        return '%dm%02ds' % (seconds // 60, seconds % 60)
    if seconds < 86400:
        return '%dh%02dm' % (seconds // 3600, seconds % 3600 // 60)
    return '%dd%02dh' % (seconds // 86400, seconds % 86400 // 3600)


def log_path(name):
    return os.path.join(DIR, name + '.log')


# The keeper.

lock = threading.Lock()
kept = {}  # name -> Kept


class Kept:
    def __init__(self, name, argv, process, started):
        self.name, self.argv, self.process, self.started = name, argv, process, started

    @property
    def running(self):
        return self.process.poll() is None

    def row(self, now):
        running = self.running
        return {'name': self.name, 'pid': self.process.pid, 'running': running, 'code': self.process.returncode,
                'seconds': now - self.started if running else None, 'argv': self.argv}


def pump(entry, pipe):
    """Copies the command's output to its log, keeping the log under LOG_CAP."""
    path = log_path(entry.name)
    size = os.path.getsize(path)
    with open(path, 'ab') as log:
        while True:
            chunk = pipe.read1(65536)
            if not chunk:
                break
            log.write(chunk)
            log.flush()
            size += len(chunk)
            if size > LOG_CAP:
                with open(path, 'rb') as whole:
                    data = trimmed(whole.read())
                log.seek(0)
                log.truncate()
                log.write(data)
                log.flush()
                size = len(data)
    pipe.close()


def kill_group(process, grace):
    """TERM to the command's process group (what it started too), KILL after [grace] seconds if any is left."""
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    deadline = time.monotonic() + grace
    while time.monotonic() < deadline:
        process.poll()
        try:
            os.killpg(process.pid, 0)
        except ProcessLookupError:
            return
        time.sleep(0.1)
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait()


def run(request):
    name, argv = request.get('name'), request.get('argv')
    if not valid_name(name):
        raise Refused('a name is 1 to 32 of a-z, 0-9 and -')
    check_argv(argv)
    cwd = request.get('cwd')
    if not isinstance(cwd, str) or not os.path.isabs(cwd) or not os.path.isdir(cwd):
        cwd = os.environ.get('HOME', '/')
    with lock:
        forget = to_forget({entry.name: (entry.running, entry.started) for entry in kept.values()}, name)
        if forget is not None:
            del kept[forget]
        path = log_path(name)
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        os.write(fd, ('[oma-keep] %s in %s: %s\n' % (time.strftime('%Y-%m-%d %H:%M:%S'), cwd, shlex.join(argv))).encode())
        os.close(fd)
        try:
            process = subprocess.Popen(argv, cwd=cwd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                       stderr=subprocess.STDOUT, start_new_session=True)
        except OSError as e:
            raise Refused('cannot start %s: %s' % (argv[0], e.strerror or e))
        entry = Kept(name, argv, process, time.time())
        kept[name] = entry
        threading.Thread(target=pump, args=(entry, process.stdout), daemon=True).start()
        prune_logs()
    # A command that fails at once (a wrong flag, a port in use) is said now rather than found later in list.
    time.sleep(0.5)
    return {'ok': True, 'kept': entry.row(time.time()), 'log': log_path(name)}


def prune_logs():
    logs = {}
    for file in os.listdir(DIR):
        if file.endswith('.log') and valid_name(file[:-4]):
            logs[file[:-4]] = os.path.getmtime(os.path.join(DIR, file))
    for name in stale_logs(logs, set(kept)):
        os.unlink(log_path(name))


def stop(request):
    name = request.get('name')
    with lock:
        entry = kept.get(name) if valid_name(name) else None
    if entry is None:
        raise Refused('nothing is kept as %s' % name)
    kill_group(entry.process, STOP_GRACE)
    with lock:
        if kept.get(name) is entry:
            del kept[name]
    return {'ok': True, 'code': entry.process.returncode}


def answer(request):
    op = request.get('op') if isinstance(request, dict) else None
    try:
        if op == 'run':
            return run(request)
        if op == 'stop':
            return stop(request)
        if op == 'list':
            now = time.time()
            with lock:
                return {'ok': True, 'kept': [entry.row(now) for entry in sorted(kept.values(), key=lambda e: e.started)]}
        raise Refused('unknown request')
    except Refused as e:
        return {'ok': False, 'error': str(e)}
    except OSError as e:
        return {'ok': False, 'error': str(e)}


def handle(connection):
    with connection:
        try:
            connection.settimeout(5)
            data = b''
            while not data.endswith(b'\n'):
                chunk = connection.recv(65536)
                if not chunk:
                    break
                data += chunk
                if len(data) > MAX_REQUEST:
                    raise Refused('request too long')
            reply = answer(json.loads(data))
        except (Refused, ValueError) as e:
            reply = {'ok': False, 'error': str(e)}
        except OSError:
            return
        connection.settimeout(None)
        try:
            connection.sendall(json.dumps(reply).encode() + b'\n')
        except OSError:
            pass


def serve():
    os.makedirs(DIR, mode=0o700, exist_ok=True)
    os.chmod(DIR, 0o700)
    probe = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        probe.connect(SOCKET)
        print('oma-keep: a keeper is already running', file=sys.stderr)
        return 0
    except OSError:
        pass
    finally:
        probe.close()
    if os.path.lexists(SOCKET):
        os.unlink(SOCKET)
    server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    # The socket is never reachable by anyone but its owner, not even between bind and chmod.
    previous = os.umask(0o177)
    try:
        server.bind(SOCKET)
    finally:
        os.umask(previous)
    os.chmod(SOCKET, 0o600)
    server.listen(16)

    def leave(*_):
        with lock:
            entries = list(kept.values())
        for entry in entries:
            try:
                os.killpg(entry.process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        for entry in entries:
            kill_group(entry.process, 2)
        try:
            os.unlink(SOCKET)
        except OSError:
            pass
        os._exit(0)

    for sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(sig, leave)
    print('oma-keep: keeping at %s' % SOCKET, file=sys.stderr, flush=True)
    while True:
        connection, _ = server.accept()
        threading.Thread(target=handle, args=(connection,), daemon=True).start()


# The command.

def ask(request):
    client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    client.settimeout(STOP_GRACE + 10)
    try:
        client.connect(SOCKET)
    except OSError:
        raise Refused('the keeper is not running. It starts with the launcher\'s Linux (python3 is needed); '
                      'opening the home screen starts it again if it stopped.')
    with client:
        client.sendall(json.dumps(request).encode() + b'\n')
        data = b''
        while not data.endswith(b'\n'):
            chunk = client.recv(65536)
            if not chunk:
                break
            data += chunk
    reply = json.loads(data) if data else {'ok': False, 'error': 'the keeper gave no answer'}
    if not reply.get('ok'):
        raise Refused(reply.get('error', 'refused'))
    return reply


def state(row):
    return 'running' if row['running'] else 'exited %s' % row['code']


def tail(name, lines):
    path = log_path(name)
    try:
        with open(path, 'rb') as log:
            data = log.read()
    except FileNotFoundError:
        raise Refused('no log for %s' % name)
    return b''.join(data.splitlines(keepends=True)[-lines:]).decode('utf-8', 'replace')


def main(args):
    try:
        request = parse(args)
        if request['op'] == 'serve':
            return serve()
        if request['op'] == 'log':
            sys.stdout.write(tail(request['name'], request['lines']))
            return 0
        if request['op'] == 'run':
            request['cwd'] = os.getcwd()
        reply = ask(request)
        if request['op'] == 'run':
            row = reply['kept']
            print('%s: %s, pid %d. Log: %s' % (row['name'], state(row), row['pid'], reply['log']))
            if not row['running']:
                sys.stdout.write(tail(row['name'], 20))
                return 1
        elif request['op'] == 'list':
            rows = reply['kept']
            if not rows:
                print('Nothing is kept.')
            for row in rows:
                command = shlex.join(row['argv'])
                print('%-32s %7d  %-10s %7s  %s' % (row['name'], row['pid'], state(row), '-' if row['seconds'] is None else uptime(row['seconds']),
                                                     command if len(command) <= 120 else command[:117] + '...'))
        else:
            print('%s stopped.' % request['name'])
        return 0
    except Usage as e:
        print(e, file=sys.stderr)
        return 2
    except Refused as e:
        print('oma-keep: %s' % e, file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
