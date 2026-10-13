#!/usr/bin/env python3
"""Proves oma-runner against fake-agent on this machine's loopback: python3 tools/mac-runner/test-runner.py

The spec's "First proof" list, as far as it goes without the phone and without a signed-in Claude Code.
"""
import json, os, pathlib, signal, socket, subprocess, sys, tempfile, time, urllib.error, urllib.request

HERE = pathlib.Path(__file__).resolve().parent
work = pathlib.Path(tempfile.mkdtemp(prefix='oma-runner-test-')).resolve()
root, outside = work / 'root', work / 'outside'
project = root / 'scratch'
project.mkdir(parents=True)
outside.mkdir()
(project / 'notes.md').write_text('x')
(work / 'token').write_text('k' * 40)
free = socket.socket()
free.bind(('127.0.0.1', 0))
port = free.getsockname()[1]
free.close()
base = 'http://127.0.0.1:%d' % port
environment = {**os.environ, 'OMA_RUNNER_PORT': str(port), 'OMA_RUNNER_TOKEN': str(work / 'token'), 'OMA_RUNNER_STATE': str(work / 'state'),
               'OMA_RUNNER_LOGS': str(work / 'logs'), 'OMA_RUNNER_ROOTS': str(root), 'OMA_RUNNER_AGENT': str(HERE / 'fake-agent'), 'OMA_RUNNER_BIND': '127.0.0.1'}
passed = 0


def start():
    process = subprocess.Popen([sys.executable, str(HERE / 'oma-runner'), 'serve'], env=environment, stdout=subprocess.PIPE, text=True)
    process.stdout.readline()
    return process


def call(method, path, body=None, token='k' * 40):
    request = urllib.request.Request(base + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Authorization': 'Bearer ' + token} if token else {})
    try:
        with urllib.request.urlopen(request, timeout=10) as reply:
            return reply.status, json.loads(reply.read())
    except urllib.error.HTTPError as problem:
        return problem.code, json.loads(problem.read())


def stream(thread, after=0, until=('result',), limit=20):
    """Follows the stream from an event id until one of the named events; the events seen."""
    request = urllib.request.Request(base + '/threads/%s/stream?after=%d' % (thread, after), headers={'Authorization': 'Bearer ' + 'k' * 40})
    seen = []
    with urllib.request.urlopen(request, timeout=limit) as reply:
        for line in reply:
            if line.startswith(b'data: '):
                seen.append(json.loads(line[6:]))
                if seen[-1]['type'] in until:
                    return seen
    return seen


def check(what, truth):
    global passed
    if not truth:
        sys.exit('FAILED: ' + what)
    passed += 1
    print('ok  ' + what)


def status_of(thread):
    return call('GET', '/threads/' + thread)[1]['thread']['status']


def settle(thread, status, seconds=5):
    deadline = time.time() + seconds
    while time.time() < deadline and status_of(thread) != status:
        time.sleep(0.05)
    return status_of(thread) == status


def starts():
    return [json.loads(line) for line in (project / 'starts.log').read_text().splitlines()]


runner = start()
try:
    check('no key: 401', call('GET', '/health', token=None)[0] == 401)
    check('wrong key: 401 and nothing else', call('GET', '/health', token='x' * 40) == (401, {'ok': False, 'error': 'no'}))
    check('/health answers', call('GET', '/health') == (200, {'ok': True, 'runner': 'oma-runner', 'threads': 0, 'working': 0, 'waiting': 0}))
    check('a project outside the roots is refused', call('POST', '/threads', {'name': 'x', 'project': str(outside)})[0] == 403)
    check('a path that climbs out of a root is refused', call('POST', '/threads', {'name': 'x', 'project': str(project / '..' / '..' / 'outside')})[0] == 403)
    (root / 'link').symlink_to(outside)
    check('a link that leads out of a root is refused', call('POST', '/threads', {'name': 'x', 'project': str(root / 'link')})[0] == 403)
    code, made = call('POST', '/threads', {'name': 'Scratch', 'project': str(project)})
    thread = made['thread']['id']
    check('a thread is made under a root, home mac', code == 200 and made['thread']['home'] == 'mac' and made['thread']['status'] == 'idle')

    check('the owner\'s words are taken at once', call('POST', '/threads/%s/messages' % thread, {'text': 'what files are here'})[1]['taken'] == 'now')
    events = stream(thread)
    check('they reach the agent unchanged', starts()[-1]['prompt'] == 'what files are here' and events[1] == {**events[1], 'type': 'message', 'text': 'what files are here'})
    check('the agent is run in manual mode', starts()[-1]['manual'])
    check('the thread answers what files are here', any(event['type'] == 'text' and 'notes.md' in event['text'] for event in events))
    check('it goes back to idle', settle(thread, 'idle'))
    listed = call('GET', '/threads')[1]['threads'][0]
    check('/threads gives summary, tail and when it last worked', listed['summary'] == 'what files are here' and 'notes.md' in listed['tail'] and listed['last_worked'])

    call('POST', '/threads/%s/messages' % thread, {'text': 'write the file'})
    events = stream(thread, after=listed['last_event'], until=('approval',))
    card = events[-1]
    check('a write shows a request naming the tool and the file', card['tool'] == 'Write' and card['input']['file_path'].endswith('written.txt') and card['nonce'])
    check('the thread waits for approval', status_of(thread) == 'waiting')
    time.sleep(1.5)
    check('it is not allowed by waiting', status_of(thread) == 'waiting' and not (project / 'written.txt').exists())
    path = '/threads/%s/approvals/%s' % (thread, card['approval'])
    check('an answer without the nonce is refused', call('POST', path, {'decision': 'allow', 'nonce': 'guess'})[0] == 403 and status_of(thread) == 'waiting')
    check('an answer without the key is refused', call('POST', path, {'decision': 'allow', 'nonce': card['nonce']}, token=None)[0] == 401)
    check('deny is taken', call('POST', path, {'decision': 'deny', 'nonce': card['nonce']})[0] == 200)
    stream(thread, after=card['id'])
    check('deny refuses the write', not (project / 'written.txt').exists())
    check('the same answer cannot be used again', call('POST', path, {'decision': 'allow', 'nonce': card['nonce']})[0] == 404)

    last = call('GET', '/threads/' + thread)[1]['thread']['last_event']
    call('POST', '/threads/%s/messages' % thread, {'text': 'write the file'})
    card = stream(thread, after=last, until=('approval',))[-1]
    call('POST', '/threads/%s/approvals/%s' % (thread, card['approval']), {'decision': 'allow', 'nonce': card['nonce']})
    stream(thread, after=card['id'])
    check('allow does it', (project / 'written.txt').read_text() == 'hi')
    check('the conversation is continued, not begun again', starts()[-1]['resumed'] and not starts()[0]['resumed'])

    settle(thread, 'idle')
    last = call('GET', '/threads/' + thread)[1]['thread']['last_event']
    first = call('POST', '/threads/%s/messages' % thread, {'text': 'slow one'})[1]
    second = call('POST', '/threads/%s/messages' % thread, {'text': 'two'})[1]
    check('a message sent while a task works waits its turn, and the answer says so', first == {'ok': True, 'taken': 'now'} and second == {'ok': True, 'taken': 'queued', 'position': 1})
    part = stream(thread, after=last, until=('text',))
    count = len(starts())
    # The link drops here (the stream above was closed mid-task). Pick it up from the last event seen.
    events = []
    while [event['type'] for event in events].count('result') < 2:
        events += stream(thread, after=(events or part)[-1]['id'])
    ids = [event['id'] for event in part + events]
    check('a dropped stream is picked up from the last event, nothing twice, nothing missing', ids == list(range(part[0]['id'], ids[-1] + 1)))
    check('the task did not start again', [entry['prompt'] for entry in starts()[count - 1:]] == ['slow one', 'two'])
    check('the waiting message was taken when the turn ended', [event['text'] for event in part + events if event['type'] == 'message'] == ['slow one', 'two'])
    check('the plain events list matches the stream', call('GET', '/threads/%s/events?after=%d' % (thread, part[-1]['id']))[1]['events'][:len(events)] == events)

    settle(thread, 'idle')
    last = call('GET', '/threads/' + thread)[1]['thread']['last_event']
    call('POST', '/threads/%s/messages' % thread, {'text': 'slow again'})
    call('POST', '/threads/%s/messages' % thread, {'text': 'never'})
    stream(thread, after=last, until=('text',))
    check('stop stops the task', call('POST', '/threads/%s/stop' % thread)[1]['stopped'])
    stopped = stream(thread, after=last, until=('stopped',))[-1]
    check('what was waiting its turn is dropped, what was said stays', stopped['dropped'] == 1 and settle(thread, 'idle') and starts()[-1]['prompt'] == 'slow again')

    last = call('GET', '/threads/' + thread)[1]['thread']['last_event']
    (project / 'written.txt').unlink()
    call('POST', '/threads/%s/messages' % thread, {'text': 'write the file'})
    card = stream(thread, after=last, until=('approval',))[-1]
    count = len(starts())
    runner.send_signal(signal.SIGTERM)
    runner.wait()
    runner = start()
    check('after the runner restarts the thread is interrupted, with its history', status_of(thread) == 'interrupted' and call('GET', '/threads/%s/events' % thread)[1]['events'][card['id'] - 1] == card)
    check('the waiting request is gone and cannot be allowed', call('POST', '/threads/%s/approvals/%s' % (thread, card['approval']), {'decision': 'allow', 'nonce': card['nonce']})[0] == 404)
    time.sleep(1)
    check('the task was not started again and nothing was written', len(starts()) == count and not (project / 'written.txt').exists())
    call('POST', '/threads/%s/messages' % thread, {'text': 'what files are here'})
    check('the interrupted thread takes the owner\'s next words', stream(thread, after=card['id'])[-1]['type'] == 'result' and starts()[-1]['resumed'])
    log = (work / 'logs' / 'requests.log').read_text()
    check('each request is logged, and the key never is', 'POST /threads/%s/messages 200' % thread in log and 'k' * 40 not in log and ' 401' in log)
    print('\nall %d checks passed' % passed)
finally:
    runner.kill()
